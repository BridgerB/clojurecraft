(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, pickup when the player comes
   within the inflated pickup box after the 10-tick delay, window 0 (the player's inventory
   with its 2x2 crafting grid) with vanilla click rules and state ids, block placement, and the
   containers a right-click opens: a crafting table, and a furnace that cooks by the game's
   smelting and fuel tables every tick, open or not. Gravel and sand fall: a block of a falling
   type whose neighbour below changed falls two ticks later if the cell under it is free, and
   lands where the game's gravity brings it.

   Fault knobs are inputs, not hidden state: :sim/drop-clicks is a set of click ordinals
   (0-based) the server silently loses, :sim/drop-breaks a set of FINISH ordinals it answers
   with the ack alone, the block left standing (a lagging server queues an early FINISH
   behind another and drops it), and the loot table's chances (gravel's flint) roll from
   :sim/rng, a pure generator whose start is the :seed input, so a run's luck replays. :sim/violations records anything a real server would
   punish or a careful client must never do: a click on an empty result slot, a close with a
   loaded cursor.

   Two ways to drive it: run folds bot and model together, deterministic and fast (generated
   worlds); connect puts the model on a thread behind a channel pair shaped like conn/open!'s,
   so main's own loop (its clock, its effects, its tap) runs against it with no Java process."
  (:refer-clojure :exclude [send])
  (:require [clojure.core.async :as async]
            [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memo :as memo]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def dig-ms 3000)                       ; a log by hand (hardness 2), the fallback for an unknown block
(def break-threshold 0.7)               ; the server breaks once this share of the block's time has passed

(def decode-once
  "A column's bytes → the column: chunk/decode remembered by the bytes' identity (memo/by), for a
   world given as bytes alone (a hand-built fixture column)."
  (memo/by [:identical] 64 chunk/decode))

(defn column-key
  "The chunk column [cx cz] block or position [x y z] stands in."
  [[x _ z]]
  [(bit-shift-right (long (math/floor x)) 4) (bit-shift-right (long (math/floor z)) 4)])

(defn column-of
  "The decoded column k of the world: the one the world came with (:sim/decoded, decoded once
   when the world was read and shared by every run of it), else its bytes decoded."
  [sim k]
  (or (get (:sim/decoded sim) k)
      (some-> (get (:sim/columns sim) k)
              decode-once)))

(defn block-at
  "The server's view of a block: placed blocks, broken positions (air), else the world's column
   (:sim/columns); nil where the world has no column."
  [sim pos]
  #_{:splint/disable [style/prefer-condp]} ; two maps asked about one pos: a condp would hide that
  (cond (contains? (:sim/placed sim) pos) (get-in sim [:sim/placed pos])
        (contains? (:sim/broken sim) pos) 0
        :else
        (let [k (column-key pos)]
          (when-let [column (column-of sim k)] (chunk/block-at {k column} pos)))))

(declare disturb full sync-window stream-from)

(defn held-slot
  "The window-0 slot of the hotbar place the client last selected (set-carried-item), the
   first when it never selected one."
  [sim]
  (inventory/slot [:hotbar (:sim/held sim 0)]))

(defn held-item
  "The item id in the slot the client last selected (set-carried-item), or nil for an empty
   hand."
  [sim]
  (get-in sim [:sim/inv (held-slot sim) :item]))

(defn full-ms
  "ms the server takes to break block id with the held item by its own clock: the formula the
   client uses (dig/ms, no penalties: the model has no view of water or the ground, which errs
   on the side of accepting a careful client), a log by hand for a block the tables do not
   know, times :sim/slow-breaks (a server whose ticks fall behind; 1 when absent)."
  [sim id held]
  (* (:sim/slow-breaks sim 1) (or (dig/ms id held {}) dig-ms)))

(defn break-ms
  "ms the server requires between START and FINISH on block id with the held item to break it
   at the FINISH: the 70% threshold the real server applies to full-ms."
  [sim id held]
  (* break-threshold (full-ms sim id held)))

(def ^:const roll-bits 53)             ; a double's mantissa: the bits of a roll kept from the generator's 64

(defn roll
  "[sim' r]: the model's next roll, r in [0, 1), from :sim/rng by one 64-bit linear congruential
   step (Knuth's MMIX constants); pure, so the same :seed rolls the same luck."
  [sim]
  (let [s (unchecked-add (unchecked-multiply (long (:sim/rng sim)) 6364136223846793005)
                         1442695040888963407)]
    [(assoc sim :sim/rng s)
     (/ (double (unsigned-bit-shift-right s (- 64 roll-bits))) (math/pow 2 roll-bits))]))

(defn loot
  "[sim' item] for breaking block id with held: the item (an id) its loot table drops, or nil
   when the held item does not harvest the block or nothing drops. The table's tries go in
   order (dig/drop-tries); one of chance 1.0 always drops, one below takes a roll and drops
   when the roll is under its chance (gravel: flint one time in ten, else gravel)."
  [sim id held]
  (if (dig/harvest? id held)
    (reduce (fn [[sim _] [item chance]]
              (if (>= chance 1.0)
                (reduced [sim (get blocks/items item)])
                (let [[sim r] (roll sim)]
                  (if (< r chance) (reduced [sim (get blocks/items item)]) [sim nil]))))
            [sim nil]
            (dig/drop-tries id))
    [sim nil]))

(defn wear
  "The inventory after one accepted dig with the tool in slot: its durability counts down from
   the material's, and the slot empties at zero (the tool broke). Items with no tool are
   untouched."
  [inv slot]
  (let [{:keys [item durability] :as it} (get inv slot)
        tool (blocks/tool item)]
    (cond (nil? tool) inv
          :else
          (let [left (dec (or durability (:durability (blocks/materials (:tier tool)))))]
            (if (pos? left) (assoc inv slot (assoc it :durability left)) (dissoc inv slot))))))
(def pickup-delay 500)                ; ms before a dropped item can be picked up (vanilla: 10 ticks)
(def keep-alive-every 15000)          ; ms between the model's keep-alives (vanilla: 15 s)
(def max-stack 64)                    ; items per slot

(defn stack-size
  "How many of item one slot holds: one for a tool (blocks/tool: the game's tools never stack,
   each wears on its own), max-stack for anything else. With every item stacking to 64, a spare
   pickaxe went into the slot of the one in use, and the dig that broke one emptied the slot of
   both (issue #36: the sim's runs lost their spare at the moment it was needed)."
  [item]
  (if (blocks/tool item) 1 max-stack))
(def max-air 300)                     ; a full breath, as the game's
(def drown-every 20)                  ; ticks between drowning hurts at zero air
(def drown-damage 2.0)                ; health taken by each
(def max-health 20.0)                 ; a full life, the health a spawn and a respawn give
(def air-regained 4)                  ; breath a tick gives back with the eyes out of water
(def drop-rise 0.25)                  ; how far over the bottom of a broken block its drop appears

(defn init
  "A fresh server before the handshake, from {:columns {[cx cz] bytes} :spawn [x y z]} (a world:
   its chunk columns as a real server sent them, a capture of one; or {:column bytes}, the world
   of the one column [0 0]), with optional :decoded {[cx cz] column} (the same columns decoded,
   once, when the world was read, so the runs of one world share them); the whole world goes to
   the client at login and stays, unless the
   optional :view-distance r streams the columns within r chunks as the player moves, as a
   server does. Then the optional
   :keep-alive-every ms, :drop-clicks #{ordinal}, :drop-breaks #{ordinal}, :inventory {slot item}
   (a slot is a named place, [:hotbar 0] as inventory/slot reads it, or a window-0 slot
   number), :lag-ticks,
   :slow-breaks f (blocks take f times their time to break by the server's clock, a loaded
   server's: a FINISH at the client's time is acked but the break comes later, full-ms),
   :respawn [x y z] (where a new life begins; the spawn when absent, as a gym landing may differ
   from the world spawn), :seed (where the loot rolls start, 0 when absent), :tick-seed (where
   a run's tick randomness starts; 0.5 every tick when absent), :kill-at ms (the
   player dies then, a fault as an input: a death mid-task), :dimension (the dimension the
   player joins in, the overworld when absent: a run that starts in the Nether is a gym's tp
   there) and :nether {:column bytes :at [x y z]} (the Nether a portal leads to: its one column
   and where the player arrives)."
  [{:keys [column columns decoded view-distance spawn keep-alive-every drop-clicks drop-breaks
           inventory lag-ticks slow-breaks respawn seed tick-seed kill-at dimension nether]
    :or {keep-alive-every keep-alive-every}}]
  (cond->
    {:sim/phase :handshake
     :sim/lag-ticks (or lag-ticks 0)
     :sim/inv (into {} (for [[k it] inventory] [(if (vector? k) (inventory/slot k) k) it]))
     :sim/state-id 1
     :sim/held 0
     :sim/placed {}
     :sim/furnaces {}
     :sim/falls {}
     :sim/falling {}
     :sim/next-window 1
     :sim/clicks 0
     :sim/drop-clicks (or drop-clicks #{})
     :sim/breaks 0
     :sim/drop-breaks (or drop-breaks #{})
     :sim/rng (or seed 0)
     :sim/violations []
     :sim/keep-alive-every keep-alive-every
     :sim/now 0
     :sim/out []
     :sim/columns (or columns {[0 0] column})
     :sim/view #{}
     :sim/spawn spawn
     :sim/respawn (or respawn spawn)
     :sim/broken #{}
     :sim/air max-air
     :sim/health max-health
     :sim/respawns 0
     :sim/items {}
     :sim/next-eid 100
     :sim/next-keep-alive keep-alive-every
     :sim/keep-alives-pending #{}}
    decoded (assoc :sim/decoded decoded)
    slow-breaks (assoc :sim/slow-breaks slow-breaks)
    tick-seed (assoc :sim/tick-seed tick-seed)
    view-distance (assoc :sim/view-distance view-distance)
    kill-at (assoc :sim/kill-at kill-at)
    dimension (assoc :sim/dimension dimension)
    nether (assoc :sim/nether nether)))

(defn send "Queue pkt for the client in :sim/out." [sim pkt] (update sim :sim/out conj pkt))

(defn in-view
  "The world's columns the client holds with the player where it stands: every one (the whole
   world, sent once at login and never forgotten: a captured run's world is tens of megabytes,
   one value every run shares), or, with :sim/view-distance r, those within r chunks of the
   player's column (a square, as a server counts it)."
  [sim]
  (let [[cx cz] (column-key (:sim/player-pos sim (:sim/spawn sim)))
        r (:sim/view-distance sim)
        columns (:sim/columns sim)]
    (cond (nil? r) (set (keys columns))
          :else
          (set (for [x (range (- cx r) (inc (+ cx r)))
                     z (range (- cz r) (inc (+ cz r)))
                     :when (contains? columns [x z])]
                 [x z])))))

(defn packed
  "A chunk column [cx cz] as forget-level-chunk packs it: x in the low 32 bits, z in the high
   (terrain/chunk-key reads it back)."
  [[cx cz]]
  (bit-or (bit-and (long cx) 0xFFFFFFFF) (bit-shift-left (long cz) 32)))

(defn stream
  "The model as a server keeps a player's chunks: every column that came into view since the last
   look is sent, in one batch, and every one that left it is forgotten, as the player walks, is
   teleported or respawns. Nearest first, so the column under the feet comes before the rest.
   Nothing to do while the player stays in the column it was streamed from (:sim/view-at)."
  [sim]
  (let [here (column-key (:sim/player-pos sim (:sim/spawn sim)))]
    (cond (= here (:sim/view-at sim)) sim
          :else (stream-from sim here))))

(defn stream-from
  "stream's work for a player in column here: send what came into view, forget what left it."
  [sim here]
  (let [now (in-view sim)
        sent (:sim/view sim)
        gone (sort (remove now sent))
        fresh (sort-by (fn [[x z]] [(+ (abs (- x (first here))) (abs (- z (second here)))) x z])
                       (remove sent now))
        sim
        (reduce (fn [sim k] (send sim {:packet/name :forget-level-chunk :pos (packed k)})) sim gone)
        sim (reduce (fn [sim [x z :as k]]
                      (send sim
                            {:packet/name :level-chunk-with-light
                             :x x
                             :z z
                             :heightmaps []
                             :data (get-in sim [:sim/columns k])
                             ;; decoded already, as the socket's reader thread attaches it: the
                             ;; one column every run of this world shares, so no run decodes it
                             :chunk/column (column-of sim k)}))
                    sim
                    fresh)]
    (cond-> (assoc sim :sim/view now :sim/view-at here)
      (seq fresh) (send {:packet/name :chunk-batch-finished :batch-size (count fresh)}))))

(defmulti on-packet
  "The server's answer to one client packet, by [phase packet-name]; unknown packets are
   ignored."
  {:arglists '([sim pkt])}
  (fn [sim pkt] [(:sim/phase sim) (:packet/name pkt)]))
(defmethod on-packet :default [sim _] sim)

(defmethod on-packet [:handshake :intention] [sim _] (assoc sim :sim/phase :login))
(defmethod on-packet [:login :hello]
  [sim {:keys [username uuid]}]
  (send sim {:packet/name :login-finished :uuid uuid :username username}))
(defmethod on-packet [:login :login-acknowledged]
  [sim _]
  (-> sim
      (assoc :sim/phase :configuration)
      (send {:packet/name :select-known-packs})))
(defmethod on-packet [:configuration :select-known-packs]
  [sim _]
  (send sim {:packet/name :finish-configuration}))
(defn login
  "The login packet for a player joining in the dimension named dim: the settings a vanilla
   server sends, then the spawn info (the dimension type's registry id and the dimension's name)."
  [dim]
  {:packet/name :login
   :entity-id 1
   :hardcore? false
   :levels [dimension/overworld dimension/nether dimension/end]
   :max-players 20
   :view-distance 6
   :simulation-distance 6
   :reduced-debug? false
   :death-screen? true
   :limited-crafting? false
   :dimension-type (:dimension/id (dimension/by-name dim))
   :dimension dim
   :seed 0
   :game-type (packet/value :game-type :survival)
   :previous-game-type (packet/value :game-type :none)
   :debug? false
   :flat? false})

(defmethod on-packet [:configuration :finish-configuration]
  [sim _]
  (let [[x y z] (:sim/spawn sim)]
    (-> sim
        (assoc :sim/phase :play)
        (send (login (:sim/dimension sim dimension/overworld)))
        (send {:packet/name :player-position
               :teleport-id 1
               :x x
               :y y
               :z z
               :dx 0.0
               :dy 0.0
               :dz 0.0
               :yaw 0.0
               :pitch 0.0
               :flags 0})
        (send {:packet/name :container-set-content
               :window-id 0
               :state-id (:sim/state-id sim)
               :items (mapv #(get-in sim [:sim/inv %])
                            (range (inventory/slot-count (inventory/layouts :inventory))))
               :carried nil})
        stream)))

(defmethod on-packet [:play :move-player-pos-rot]
  [sim {:keys [x y z]}]
  (stream (assoc sim :sim/player-pos [x y z])))
(defmethod on-packet [:play :move-player-pos]
  [sim {:keys [x y z]}]
  (stream (assoc sim :sim/player-pos [x y z])))
(defmethod on-packet [:play :keep-alive]
  [sim {:keys [id]}]
  (update sim :sim/keep-alives-pending disj id))

(defn break-block
  "Block id at pos is broken by the player holding held in slot: gone (placed or not), its
   block update, its drop as an item entity, the tool worn, the neighbours disturbed."
  [sim pos id held slot]
  (let [eid (:sim/next-eid sim)
        [x y z] pos
        [ix iy iz :as item-pos] [(+ x 0.5) (+ y drop-rise) (+ z 0.5)]
        [sim item] (loot sim id held)
        drop (when item {:item item :count 1})
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        sim (-> sim
                (update :sim/placed dissoc pos) ; a placed block, broken, is gone too
                (update :sim/broken conj pos)
                (send {:packet/name :block-update :pos pos :state blocks/air})
                (update :sim/inv wear slot)
                (disturb pos))
        sim (sync-window sim :inventory before (:sim/cursor sim) false)]
    (if drop
      (-> sim
          (assoc :sim/next-eid (inc eid))
          (assoc-in [:sim/items eid] {:pos item-pos :spawned (:sim/now sim) :item drop})
          (send {:packet/name :add-entity
                 :entity-id eid
                 :uuid nil
                 :type blocks/item-entity-type
                 :x ix
                 :y iy
                 :z iz}))
      sim)))

(defn finish-dig
  "The client says it finished breaking pos: broken, with its drop, when it started breaking
   that block at least the block's break time ago (break-ms), the block update before the ack
   (observed live on 26.1.2); else the real server keeps breaking it by its own clock
   (ServerPlayerGameMode's delayed destroy: :sim/delayed, finished by tick-delayed) and only
   acks now. A FINISH for another block than the one being broken is acked and nothing else.
   A FINISH whose ordinal is in :sim/drop-breaks gets the ack alone, the block left standing
   and nothing said: what a lagging server does with an early FINISH queued behind another one."
  [sim {:keys [pos sequence]}]
  (let [{:keys [at] dpos :pos} (:sim/dig sim)
        id (block-at sim pos)
        held (held-item sim)
        slot (held-slot sim)
        n (:sim/breaks sim)
        sim (update sim :sim/breaks inc)
        ack {:packet/name :block-changed-ack :sequence sequence}]
    (cond (contains? (:sim/drop-breaks sim) n)
          (-> sim
              (dissoc :sim/dig)
              (send ack))
          (and (= dpos pos) id (pos? id) (>= (- (:sim/now sim) at) (break-ms sim id held)))
          (-> sim
              (dissoc :sim/dig)
              (break-block pos id held slot)
              (send ack))
          (and (= dpos pos) id (pos? id))
          (-> sim
              (dissoc :sim/dig)
              (assoc :sim/delayed {:pos pos :at at :held held :slot slot})
              (send ack))
          :else
          (-> sim
              (dissoc :sim/dig)
              (send ack)))))

(defn tick-delayed
  "Finish a delayed destroy (finish-dig) once the block's whole break time has passed by the
   server's clock: the block update, seconds after the ack on a slow server (:slow-breaks)."
  [sim]
  (let [{:keys [pos at held slot]} (:sim/delayed sim)
        id (when pos (block-at sim pos))]
    (cond (nil? pos) sim
          (or (nil? id) (zero? id)) (dissoc sim :sim/delayed)
          (>= (- (:sim/now sim) at) (full-ms sim id held))
          (-> sim
              (dissoc :sim/delayed)
              (break-block pos id held slot))
          :else sim)))

(defmethod on-packet [:play :player-action]
  [sim {:keys [status pos] :as p}]
  (cond (= status (packet/value :dig :start-destroy))
        (assoc sim :sim/dig {:pos pos :at (:sim/now sim)})
        (= status (packet/value :dig :stop-destroy)) (finish-dig sim p)
        :else sim))

;; ---------------------------------------------------------------- windows

(defn result-of
  "What the grid of view crafts, {:item id :count n}, or nil when it matches no recipe."
  [view layout]
  (let [grid (into {}
                   (for [s (inventory/grid-slots layout)
                         :let [it (get view s)]
                         :when it]
                     [s (recipe/item-name (:item it))]))]
    (when-let [r (recipe/match grid (:size layout))]
      {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)})))

(defn own-path
  "Where the sim keeps the own slots of the open container of kind: the table's grid on its
   window (it empties when the window closes), a furnace's slots at its position (they stay)."
  [sim kind]
  (case kind
    :table [:sim/window :grid]
    :furnace [:sim/furnaces (get-in sim [:sim/window :pos]) :slots]))

(defn view-of
  "The slots of window kind as the click rules see them, {slot item}, never a crafting result:
   :inventory is window 0; a container is its own slots plus the player's where it shows them
   (inventory/layouts)."
  [sim kind]
  (if (= kind :inventory)
    (:sim/inv sim)
    (let [layout (inventory/layouts kind)]
      (merge (get-in sim (own-path sim kind))
             (into {}
                   (for [[s it] (:sim/inv sim)
                         :let [t (inventory/translate (inventory/layouts :inventory) layout s)]
                         :when t]
                     [t it]))))))

(defn with-view
  "Write a clicked view of window kind back into the sim; the inverse of view-of. A crafting
   result slot is dropped, since it is always derived."
  [sim kind view]
  (let [layout (inventory/layouts kind)
        view (cond-> view (inventory/crafting? layout) (dissoc inventory/result-slot))]
    (if (= kind :inventory)
      (assoc sim :sim/inv view)
      (let [inv (inventory/layouts :inventory)]
        (->
          sim
          (assoc-in (own-path sim kind) (select-keys view (inventory/own-slots layout)))
          (assoc
           :sim/inv
           (merge
            (into {} (remove (fn [[s _]] (inventory/translate inv layout s)) (:sim/inv sim)))
            (into
             {}
             (for [[s it] view :let [w (inventory/translate layout inv s)] :when w] [w it])))))))))

(defn full
  "view with its result slot set to what its grid crafts, when the window crafts."
  [view layout]
  (cond-> view (inventory/crafting? layout) (assoc inventory/result-slot (result-of view layout))))

(defn insert
  "Put a stack into the given slots, stacking first. Returns [view leftover]."
  [view slots {:keys [item count]}]
  (let [order (concat (filter #(= item (:item (get view %))) slots)
                      (filter #(nil? (get view %)) slots))
        [view n] (reduce (fn [[view n] s]
                           (if (zero? n)
                             (reduced [view n])
                             (let [have (:count (get view s) 0)
                                   k (min n (- (stack-size item) have))]
                               [(assoc view s {:item item :count (+ have k)}) (- n k)])))
                         [view count]
                         order)]
    [view (when (pos? n) {:item item :count n})]))

(defn take-one
  "view with one item removed from slot s; the slot empties at zero. s must hold an item."
  [view s]
  (let [{:keys [count] :as it} (get view s)]
    (if (> count 1) (assoc view s (assoc it :count (dec count))) (dissoc view s))))

(defn craft-once
  "view after one craft: one item taken from every occupied grid slot."
  [view layout]
  (reduce (fn [view s] (if (get view s) (take-one view s) view))
          view
          (inventory/grid-slots layout)))

(defn craft-all
  "Shift-click on the result: craft and move results into the store until the grid matches
   nothing or the store is full."
  [view layout]
  (loop [view view]
    (let [out (result-of view layout)
          [view' left] (when out (insert view (inventory/store-slots layout) out))]
      (cond (or (nil? out) left) view
            :else (recur (craft-once view' layout))))))

(defn burns?
  "Does a furnace burn this item name (the game's fuel table)?"
  [n]
  (contains? blocks/fuel-by-name n))

(defn accepts?
  "May a click put item into slot of a window with layout? A furnace's output takes nothing and
   its fuel slot only what burns or an empty bucket (vanilla's FurnaceResultSlot and
   FurnaceFuelSlot); every other slot takes anything."
  [layout slot {:keys [item]}]
  (let [n (recipe/item-name item)]
    (cond (= slot (:output layout)) false
          (= slot (:fuel layout)) (or (burns? n) (= n :bucket))
          :else true)))

(defn take-onto
  "A loaded cursor clicks a slot that refuses it: vanilla moves what fits of the same item from
   the slot onto the cursor (taking from a furnace's output), and does nothing otherwise."
  [view cursor slot]
  (let [at (get view slot)]
    (if (and at (= (:item at) (:item cursor)))
      (let [k (min (:count at) (- (stack-size (:item cursor)) (:count cursor)))
            left (- (:count at) k)]
        {:view (if (pos? left) (assoc view slot (assoc at :count left)) (dissoc view slot))
         :cursor (update cursor :count + k)})
      {:view view :cursor cursor})))

(defn click-kind
  "Which vanilla rule a click in a window with layout falls under: :result (the result slot of a
   window that crafts), :swap (with a hotbar slot), :shift (a quick move), :left (the left
   button) or :right."
  [layout {:keys [slot button mode]}]
  (cond (and (inventory/crafting? layout) (= slot inventory/result-slot)) :result
        (= mode (inventory/click-modes :swap)) :swap
        (= mode (inventory/click-modes :quick-move)) :shift
        (= button inventory/left-button) :left
        :else :right))

(defmulti click-view
  "Vanilla click rules over one window's slots, one method per kind of click (click-kind).
   Returns {:view :cursor :violation}."
  {:arglists '([layout view cursor click])}
  (fn [layout _view _cursor click] (click-kind layout click)))

(defmethod click-view :result
  ;; vanilla crafts onto the cursor, or with a shift-click into the store; a click when the grid
  ;; matches nothing is recorded as a violation
  [layout view cursor {:keys [mode]}]
  (let [out (result-of view layout)]
    (cond (nil? out) {:view view :cursor cursor :violation [:click-on-empty-result mode]}
          (= mode (inventory/click-modes :quick-move))
          {:view (craft-all view layout) :cursor cursor}
          (or (nil? cursor)
              (and (= (:item cursor) (:item out))
                   (<= (+ (:count cursor) (:count out)) (stack-size (:item out)))))
          {:view (craft-once view layout) :cursor (update out :count + (:count cursor 0))}
          :else {:view view :cursor cursor})))

(defmethod click-view :swap
  ;; the slot and hotbar slot (hotbar + button) trade places, unless the slot refuses the
  ;; hotbar's item
  [layout view cursor {:keys [slot button]}]
  (let [at (get view slot)
        h (+ (inventory/hotbar-start layout) button)
        other (get view h)]
    (if (and other (not (accepts? layout slot other)))
      {:view view :cursor cursor}
      {:view (-> view
                 (dissoc slot h)
                 (cond-> other (assoc slot other)
                         at (assoc h at)))
       :cursor cursor})))

(defmethod click-view :shift
  ;; a container's own stack (a grid cell, a furnace slot) moves into the store, what does not
  ;; fit stays; anywhere else nothing
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (if (and at (some #{slot} (inventory/own-slots layout)))
      (let [[view' left] (insert (dissoc view slot) (inventory/store-slots layout) at)]
        {:view (cond-> view' left (assoc slot left)) :cursor cursor})
      {:view view :cursor cursor})))

(defmethod click-view :left
  ;; pick up, put down, merge up to a full stack (the rest stays on the cursor), or swap; a slot
  ;; that refuses the cursor's item gives what fits of the same item instead
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (nil? cursor) {:view (dissoc view slot) :cursor at}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (nil? at) {:view (assoc view slot cursor) :cursor nil}
          (= (:item at) (:item cursor))
          (let [k (min (:count cursor) (- (stack-size (:item at)) (:count at)))
                left (- (:count cursor) k)]
            {:view (assoc view slot (update at :count + k))
             :cursor (when (pos? left) (assoc cursor :count left))})
          :else {:view (assoc view slot cursor) :cursor at})))

(defmethod click-view :right
  ;; an empty cursor picks up half (rounded up); a loaded one places one into an empty or
  ;; same-item slot that is not full, or takes from a slot that refuses it as a left click does
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (and (nil? cursor) at)
          (let [half (long (math/ceil (/ (:count at) 2)))
                rest (- (:count at) half)]
            {:cursor (assoc at :count half)
             :view (if (pos? rest) (assoc view slot (assoc at :count rest)) (dissoc view slot))})
          (nil? cursor) {:view view :cursor nil}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (or (nil? at) (and (= (:item at) (:item cursor)) (< (:count at) (stack-size (:item at)))))
          {:view (assoc view slot {:item (:item cursor) :count (inc (:count at 0))})
           :cursor (when (> (:count cursor) 1) (update cursor :count dec))}
          :else {:view view :cursor cursor})))

(defn state-id-path
  "Where the sim keeps the state id of window kind: window 0's, or the open container's."
  [kind]
  (if (= kind :inventory) [:sim/state-id] [:sim/window :state-id]))

(defn window-id
  "The protocol window id of window kind: 0, or the open container's."
  [sim kind]
  (if (= kind :inventory) 0 (get-in sim [:sim/window :id])))

(defn sync-window
  "Answer a click the way vanilla does. The click's own changed-slots and cursor fields are the
   client's prediction: the server records them as what the client believes, then sends only
   what differs from that belief. We always predict no changed slots (so every changed slot is
   sent) and an empty cursor (so the cursor is sent only when it is not empty). A stale state
   id gets the full window instead. predicted-cursor is the cursor the client last claimed."
  [sim kind before predicted-cursor stale?]
  (let [layout (inventory/layouts kind)
        slots (range (inventory/slot-count layout))
        after (full (view-of sim kind) layout)
        id (window-id sim kind)
        path (state-id-path kind)]
    (if stale?
      (let [sim (update-in sim path inc)]
        (send sim
              {:packet/name :container-set-content
               :window-id id
               :state-id (get-in sim path)
               :items (mapv #(get after %) slots)
               :carried (:sim/cursor sim)}))
      (as-> sim sim
        (reduce (fn [sim s]
                  (if (= (get before s) (get after s))
                    sim
                    (let [sim (update-in sim path inc)]
                      (send sim
                            {:packet/name :container-set-slot
                             :window-id id
                             :state-id (get-in sim path)
                             :slot s
                             :item (get after s)}))))
                sim
                slots)
        (if (= predicted-cursor (:sim/cursor sim))
          sim
          (send sim {:packet/name :set-cursor-item :item (:sim/cursor sim)}))))))

(defn window-kind
  "Which window a protocol window id names: :inventory, the open container's kind (:table,
   :furnace) when it is that window's id, else nil."
  [sim window-id]
  (cond (zero? window-id) :inventory
        (= window-id (get-in sim [:sim/window :id])) (get-in sim [:sim/window :kind])))

(defmethod on-packet [:play :container-click]
  [sim {:keys [window-id state-id] :as pkt}]
  (let [n (:sim/clicks sim)
        sim (update sim :sim/clicks inc)
        kind (window-kind sim window-id)
        ;; with a container open, the player's own window is not the open menu: vanilla
        ;; ignores the click, and a careful client never sends it
        sim (cond-> sim
              (and (= kind :inventory) (:sim/window sim))
              (update :sim/violations conj [:click-inventory-while-open window-id]))
        kind (when-not (and (= kind :inventory) (:sim/window sim)) kind)]
    (if (or (nil? kind) (contains? (:sim/drop-clicks sim) n))
      sim
      (let [layout (inventory/layouts kind)
            before (full (view-of sim kind) layout)
            {:keys [view cursor violation]}
            (click-view layout (view-of sim kind) (:sim/cursor sim) pkt)
            sim (cond-> (-> sim
                            (with-view kind view)
                            (assoc :sim/cursor cursor))
                  violation (update :sim/violations conj violation))]
        (sync-window sim
                     kind
                     before
                     (:cursor pkt)
                     (not= state-id (get-in sim (state-id-path kind))))))))

(defn close-window
  "The open container closes: a table's grid goes back into the inventory (vanilla), a
   furnace's slots stay in the furnace; window 0 is synced with what came back."
  [sim]
  (let [store (inventory/store-slots (inventory/layouts :inventory))
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        inv (reduce (fn [inv it] (first (insert inv store it)))
                    (:sim/inv sim)
                    (vals (get-in sim [:sim/window :grid])))]
    (-> sim
        (assoc :sim/inv inv)
        (dissoc :sim/window)
        (sync-window :inventory before (:sim/cursor sim) false))))

(defmethod on-packet [:play :container-close]
  [sim {:keys [window-id]}]
  (let [sim (cond-> sim
              (:sim/cursor sim)
              (update :sim/violations conj [:close-with-cursor (:sim/cursor sim)]))]
    (if (= window-id (get-in sim [:sim/window :id])) (close-window sim) sim)))

(defn give
  "An item entity reaches the inventory: stack it into window 0 and tell the client."
  [sim item]
  (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
        [inv _] (insert (:sim/inv sim) (inventory/store-slots (inventory/layouts :inventory)) item)]
    (sync-window (assoc sim :sim/inv inv) :inventory before (:sim/cursor sim) false)))

(defn open-window
  "Open a container window of kind for the client, closing any open one first as vanilla does:
   a fresh window id with state id 1 (extra carries the kind's own keys: a table's empty grid,
   a furnace's position), then open-screen and the whole window with the real cursor."
  [sim kind extra]
  (let [sim (if-let [old (get-in sim [:sim/window :id])]
              (-> sim
                  close-window
                  (send {:packet/name :container-close :window-id old}))
              sim)
        id (:sim/next-window sim)
        layout (inventory/layouts kind)
        sim (-> sim
                (assoc :sim/window (merge {:id id :kind kind :state-id 1} extra))
                (update :sim/next-window inc))
        view (full (view-of sim kind) layout)]
    (-> sim
        (send {:packet/name :open-screen
               :window-id id
               :menu-type (:menu-type layout)
               :title (byte-array 0)})
        (send {:packet/name :container-set-content
               :window-id id
               :state-id 1
               :items (mapv #(get view %) (range (inventory/slot-count layout)))
               :carried (:sim/cursor sim)}))))

;; ---------------------------------------------------------------- the furnace

(def fresh-furnace
  "A furnace nothing has been put in: no slots, unlit, no progress (vanilla's data 0-3)."
  {:slots {} :lit 0 :lit-total 0 :cook 0 :cook-total 0})

(def default-cook-ticks 200)          ; vanilla's cook total when no recipe takes the input

(def smelt-by-input
  "item name → the furnace recipe that takes it (smelting.edn)."
  (into {} (for [r recipe/smelting n (:smelt/input r)] [n r])))

(defn smelt-of
  "The furnace recipe that takes a slot's item, or nil."
  [it]
  (some-> it
          :item
          recipe/item-name
          smelt-by-input))

(def furnace-input (inventory/slot-in :furnace [:input])) ; a furnace's own slots, as its window numbers them
(def furnace-fuel (inventory/slot-in :furnace [:fuel]))
(def furnace-output (inventory/slot-in :furnace [:output]))

(defn furnace-data
  "A furnace's properties as container-set-data sends them, {property-id value}: the fire left
   and lit (:lit, :lit-total), the cooking done and to do (:cook, :cook-total), numbered by the
   furnace layout's :properties."
  [f]
  (into {} (for [[k id] (:properties (inventory/layouts :furnace))] [id (get f k)])))

(defn can-burn?
  "Can the furnace's input cook now: a recipe takes it, and the output is empty or holds the
   result with room for one more batch."
  [{:keys [slots]}]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (boolean (and r
                  (or (nil? out)
                      (and (= (:item out) (recipe/item-id (:smelt/result r)))
                           (<= (+ (:count out) (:smelt/count r)) max-stack)))))))

(defn smelt-once
  "A furnace's slots after one batch: one input taken, its result added to the output."
  [slots]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (-> slots
        (take-one furnace-input)
        (assoc furnace-output
               {:item (recipe/item-id (:smelt/result r))
                :count (+ (:count out 0) (:smelt/count r))}))))

(defn reset-on-new-input
  "Vanilla resets the progress, and sets the cook total from the recipe, when the input slot is
   set to a different item; :cooking remembers which item the progress is for."
  [{:keys [slots cooking] :as f}]
  (let [item (:item (get slots furnace-input))]
    (if (= item cooking)
      f
      (cond-> (assoc f
                     :cook 0
                     :cook-total
                     (:smelt/ticks (smelt-of (get slots furnace-input)) default-cook-ticks))
        item (assoc :cooking item)
        (nil? item) (dissoc :cooking)))))

(defn cook
  "One game tick of a furnace, as vanilla's AbstractFurnaceBlockEntity.serverTick: a lit furnace
   burns down one tick; one that is out lights when its input can cook, taking one fuel for
   the fuel's burn ticks (fuel.edn); while lit and able to cook, progress grows, and at the
   cook total one input becomes the result; lit but unable, progress resets; out with no fuel
   or input, progress unwinds two a tick. (A lava bucket's empty bucket is not modelled.)"
  [f]
  (let [f (cond-> (reset-on-new-input f) (pos? (:lit f)) (update :lit dec))
        fuel (get-in f [:slots furnace-fuel])]
    (cond (or (pos? (:lit f)) (and fuel (get-in f [:slots furnace-input])))
          (let [f (if (and (zero? (:lit f)) (can-burn? f))
                    (let [t (get blocks/fuel-by-name (recipe/item-name (:item fuel)) 0)]
                      (-> f
                          (assoc :lit t :lit-total t)
                          (update :slots take-one furnace-fuel)))
                    f)]
            (cond (not (and (pos? (:lit f)) (can-burn? f))) (assoc f :cook 0)
                  (= (inc (:cook f)) (:cook-total f))
                  (-> f
                      (assoc :cook 0)
                      (update :slots smelt-once))
                  :else (update f :cook inc)))
          (pos? (:cook f)) (update f :cook #(max 0 (- % 2)))
          :else f)))

(defn send-data
  "container-set-data for every property of the open window that changed between before and
   after (vanilla sends a property only when it differs from what the client was last told)."
  [sim before after]
  (reduce (fn [sim p]
            (if (= (get before p) (get after p))
              sim
              (send sim
                    {:packet/name :container-set-data
                     :window-id (get-in sim [:sim/window :id])
                     :property p
                     :value (get after p)})))
          sim
          (sort (keys after))))

(defn open-furnace
  "Open the furnace at pos (one is made there if the model has none yet): its window, then
   every property, as vanilla sends a menu's whole state on opening."
  [sim pos]
  (let [sim (update-in sim [:sim/furnaces pos] #(or % fresh-furnace))]
    (-> sim
        (open-window :furnace {:pos pos})
        (send-data {} (furnace-data (get-in sim [:sim/furnaces pos]))))))

(defn tick-furnaces
  "One game tick of every furnace, open or not (a furnace cooks with nobody watching); the one
   whose window is open tells the client what changed: slots with a state id each, then the
   properties."
  [sim]
  (reduce (fn [sim [pos f]]
            (let [open? (= pos (get-in sim [:sim/window :pos]))
                  before (when open? (view-of sim :furnace))
                  f' (cook f)
                  sim (assoc-in sim [:sim/furnaces pos] f')]
              (cond-> sim
                open?
                (-> (sync-window :furnace before (:sim/cursor sim) false)
                    (send-data (furnace-data f) (furnace-data f'))))))
          sim
          (:sim/furnaces sim)))

;; ---------------------------------------------------------------- using items on blocks

(def faces
  "A face as the packets number it → the step to the cell beyond it (face-offsets)."
  (into {} (for [[n step] physics/face-offsets] [(packet/value :face n) step])))

(defn overlaps-player?
  "Would a block at [x y z] intersect the player's 0.6×1.8 box at its last reported position?
   nil before the player has moved."
  [sim [x y z]]
  (when-let [[px py pz] (:sim/player-pos sim)]
    (and (< (- px 0.3) (inc x))
         (> (+ px 0.3) x)
         (< py (inc y))
         (> (+ py 1.8) y)
         (< (- pz 0.3) (inc z))
         (> (+ pz 0.3) z))))

(defmethod on-packet [:play :set-carried-item] [sim {:keys [slot]}] (assoc sim :sim/held slot))

(def respawn
  "The respawn the model sends for a new life: the overworld, survival, nothing before it."
  {:packet/name :respawn
   :dimension-type 0
   :dimension "minecraft:overworld"
   :seed 0
   :game-type (packet/value :game-type :survival)
   :previous-game-type (packet/value :game-type :none)
   :debug? false
   :flat? false})

(defmethod on-packet [:play :client-command]
  [sim {:keys [action]}]
  ;; a dead player asking to respawn gets a new life at spawn, full health and breath, then the
  ;; teleport that places it
  (if (and (= action (packet/value :client-command :perform-respawn)) (<= (:sim/health sim) 0.0))
    (let [[x y z] (:sim/respawn sim)]
      (-> sim
          (assoc :sim/health max-health :sim/air max-air :sim/player-pos [x y z])
          (update :sim/respawns inc)
          (send respawn)
          (send {:packet/name :player-position
                 :teleport-id 2
                 :x x
                 :y y
                 :z z
                 :dx 0.0
                 :dy 0.0
                 :dz 0.0
                 :yaw 0.0
                 :pitch 0.0
                 :flags 0})
          stream))
    sim))

;; ---------------------------------------------------------------- fire and portals (issue #8)

(def portal-ms 4000)                  ; a survival player in a portal this long changes dimension (80 game ticks)
(def nether
  "The respawn the model sends for the Nether: the dimension change a portal makes."
  (assoc respawn
         :dimension-type (:dimension/id (dimension/by-name dimension/nether))
         :dimension dimension/nether))

(defn frame-around
  "The six interior cells of a complete portal frame round cell along axis (:x or :z), or nil: an
   interior two wide and three high, air (or fire) inside, obsidian on its four sides, the
   corners not needed (PortalShape)."
  [sim [x y z] axis]
  (let [along (fn [[a b c] d] (if (= axis :x) [(+ a d) b c] [a b (+ c d)]))
        obsidian? #(= :obsidian (blocks/name-of (block-at sim %)))
        open? #(contains? #{:air :fire} (blocks/name-of (block-at sim %)))]
    (first (for [da [0 -1]
                 dy [0 -1 -2]
                 :let [base (along [x (+ y dy) z] da)
                       inside (for [a [0 1] b [0 1 2]] (update (along base a) 1 + b))
                       frame (concat (for [a [0 1]] (update (along base a) 1 dec))
                                     (for [a [0 1]] (update (along base a) 1 + 3))
                                     (for [b [0 1 2]] (update (along base -1) 1 + b))
                                     (for [b [0 1 2]] (update (along base 2) 1 + b)))]
                 :when (and (every? open? inside) (every? obsidian? frame))]
             inside))))

(defn light
  "use-item-on with flint and steel: fire in the cell the clicked face looks into when it is air;
   fire inside a complete frame becomes the portal instead (BaseFireBlock.onPlace finds the
   PortalShape round it). Then the ack."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        inside (when (= :air (blocks/name-of (block-at sim dest)))
                 (or (some-> (frame-around sim dest :x)
                             (vector (blocks/default-state :nether_portal)))
                     (some-> (frame-around sim dest :z)
                             (vector (inc (blocks/default-state :nether_portal))))))
        fire (blocks/default-state :fire)]
    (cond (nil? (block-at sim dest)) (send sim ack)
          inside
          (let [[cells state] inside]
            (-> (reduce (fn [sim c]
                          (-> sim
                              (assoc-in [:sim/placed c] state)
                              (send {:packet/name :block-update :pos c :state state})))
                        sim
                        cells)
                (send ack)))
          (= :air (blocks/name-of (block-at sim dest)))
          (-> sim
              (assoc-in [:sim/placed dest] fire)
              (send {:packet/name :block-update :pos dest :state fire})
              (send ack))
          :else (send sim ack))))

(defn arrive
  "The model in the Nether: with a :sim/nether, its column becomes the world (the overworld's
   placed and broken blocks were the other dimension's) and the player stands at its :at; without
   one, the same column where the player stood (a one-column model of the portal alone)."
  [sim]
  (if-let [{:keys [column at]} (:sim/nether sim)]
    (-> sim
        (assoc :sim/columns {[0 0] column}
               :sim/placed {}
               :sim/broken #{}
               :sim/player-pos at
               :sim/view #{}) ; a dimension change drops every chunk the client held
        (dissoc :sim/view-at))
    (dissoc sim :sim/view-at)))

(defn through-portal
  "The model a tick on: a player whose feet stand in a nether portal portal-ms on end changes
   dimension (arrive, then the Nether's respawn, a teleport where it now stands and its column);
   out of a portal, the count starts over."
  [sim now]
  (let [pos (:sim/player-pos sim)
        in? (and pos
                 (= :nether_portal
                    (blocks/name-of (block-at sim
                                              (mapv #(long (math/floor %)) (update pos 1 + 0.1))))))
        since (:sim/portal-since sim)]
    (cond (not in?) (dissoc sim :sim/portal-since)
          (= dimension/nether (:sim/dimension sim)) sim
          (nil? since) (assoc sim :sim/portal-since now)
          (< (- now since) portal-ms) sim
          :else
          (let [sim (arrive sim)
                [x y z] (:sim/player-pos sim)]
            (-> sim
                (assoc :sim/dimension dimension/nether)
                (dissoc :sim/portal-since)
                (send nether)
                (send {:packet/name :player-position
                       :teleport-id 3
                       :x x
                       :y y
                       :z z
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 0.0
                       :pitch 0.0
                       :flags 0})
                (assoc :sim/view #{}) ; the client dropped the old dimension's chunks
                stream)))))

(defn place
  "use-item-on that is not on a container: place the held block item at pos + face when the
   destination is one a block may take the place of (blocks/placeable-into?: air, a grass or a
   fluid, as the server's replaceable tag says; a flower refuses it), the clicked block is
   solid and the block would not overlap the player; else tell the client the destination's
   real state. Either way, then the ack. Sim race 38515210080 (issue #44, 198 runs): the first
   op of the portal's pad placed cobblestone into a water cell at the pond's edge, which a real
   server takes and the model refused, three times, :rejected."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        slot (held-slot sim)
        held (get-in sim [:sim/inv slot])
        d (block-at sim dest)
        overlap? (overlaps-player? sim dest)
        placing (some-> held
                        :item
                        recipe/item-name
                        blocks/default-state) ; the held item is a block: it is placed
        ok? (and placing
                 d
                 (blocks/placeable-into? d)
                 (blocks/solid? (or (block-at sim pos) blocks/air))
                 (not overlap?))
        sim (cond-> sim overlap? (update :sim/violations conj [:place-into-player dest]))]
    (if ok?
      (let [before (full (:sim/inv sim) (inventory/layouts :inventory))]
        (-> sim
            (assoc-in [:sim/placed dest] placing)
            (disturb dest)
            (update :sim/inv take-one slot)
            (send {:packet/name :block-update :pos dest :state placing})
            (sync-window :inventory before (:sim/cursor sim) false)
            (send ack)))
      (-> sim
          (send {:packet/name :block-update :pos dest :state (or d blocks/air)})
          (send ack)))))

(defmethod on-packet [:play :use-item-on]
  ;; a right-click on a container opens it (ack first, as with a placement); anything else is
  ;; a placement against the clicked face
  [sim {:keys [pos face sequence]}]
  (let [ack {:packet/name :block-changed-ack :sequence sequence}]
    (case (blocks/name-of (block-at sim pos))
      :crafting_table
      (-> sim
          (send ack)
          (open-window :table {:grid {}}))
      :furnace
      (-> sim
          (send ack)
          (open-furnace pos))
      (if (= (:item (get-in sim [:sim/inv (held-slot sim)])) (get blocks/items :flint_and_steel))
        (light sim pos face ack)
        (place sim pos face ack)))))

(def use-range 4.5)                   ; how far the eyes reach for a block, as the server's interaction range
(def ray-step 0.05)                   ; the length of one step along a ray

(defn ray-cells
  "The cells a ray from the player's eye (its last reported position) along look [yaw pitch]
   passes through within use-range, in order, without repeats."
  [sim look]
  (let [[px py pz] (:sim/player-pos sim)]
    (physics/cells-along [px (+ py 1.62) pz] (physics/direction look) use-range ray-step)))

(def bucket-fluids
  "What each bucket holds, by item id: the empty one nothing, the filled ones the source state they
   pour."
  {(get blocks/items :bucket) nil
   (get blocks/items :water_bucket) blocks/water-base
   (get blocks/items :lava_bucket) blocks/lava-base})

(def filled-bucket
  "The bucket a scooped source state fills an empty one into."
  {blocks/water-base (get blocks/items :water_bucket)
   blocks/lava-base (get blocks/items :lava_bucket)})

(defn scooped
  "The fluid source an empty bucket used along look takes: the first water or lava source on the
   ray (BucketItem with ClipContext.Fluid.SOURCE_ONLY), unless a block with an outline comes
   first (ClipContext.Block.OUTLINE: anything but air and water or lava, a plant as much as
   stone); nil when none."
  [sim look]
  (reduce (fn [_ cell]
            (let [id (block-at sim cell)]
              (cond (contains? filled-bucket id) (reduced cell)
                    (or (nil? id)
                        (blocks/air? id)
                        (contains? blocks/water-states id)
                        (contains? blocks/lava-states id))
                    nil
                    :else (reduced nil))))
          nil
          (ray-cells sim look)))

(defn poured
  "Where a filled bucket used along look puts its fluid: the cell before the first solid block on
   the ray, the ray passing through every fluid (ClipContext.Fluid.NONE: a water pour aimed at the
   floor under a lava source goes through the lava and replaces it), when that cell can take a
   fluid (air, a plant, a fluid); nil when no solid block is in reach."
  [sim look]
  (reduce (fn [before cell]
            (let [id (block-at sim cell)]
              (cond (nil? id) (reduced nil)
                    (blocks/solid? id) (reduced before)
                    :else cell)))
          nil
          (ray-cells sim look)))

(def quench-sides
  "The cells round a lava block water turns it to stone from: above and the four sides, never
   below (LiquidBlock.shouldSpreadLiquid over POSSIBLE_FLOW_DIRECTIONS, each one's opposite)."
  [[0 1 0] [0 0 -1] [0 0 1] [1 0 0] [-1 0 0]])

(defn quench
  "The model after water meets lava at or round pos: each lava block there with water above it or
   beside it becomes obsidian when a source, cobblestone when flowing, and the client is told."
  [sim pos]
  (reduce (fn [sim cell]
            (let [id (block-at sim cell)
                  water? (some #(contains? blocks/water-states (block-at sim (mapv + cell %)))
                               quench-sides)]
              (cond (not (and (contains? blocks/lava-states id) water?)) sim
                    :else
                    (let [stone (blocks/default-state
                                 (if (= id blocks/lava-base) :obsidian :cobblestone))]
                      (-> sim
                          (assoc-in [:sim/placed cell] stone)
                          (send {:packet/name :block-update :pos cell :state stone}))))))
          sim
          (cons pos (map #(mapv + pos %) [[0 1 0] [0 -1 0] [1 0 0] [-1 0 0] [0 0 1] [0 0 -1]]))))

(defn swap-held
  "The model with the one held item in the held slot exchanged for item (a bucket filled or
   emptied), the client told what window 0 now holds."
  [sim item]
  (let [slot (held-slot sim)
        held (get-in sim [:sim/inv slot])
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        stack {:item item :count 1}
        inv (if (= 1 (:count held))
              (assoc (:sim/inv sim) slot stack)
              (first (insert (take-one (:sim/inv sim) slot)
                             (inventory/store-slots (inventory/layouts :inventory))
                             stack)))]
    (-> sim
        (assoc :sim/inv inv)
        (sync-window :inventory before (:sim/cursor sim) false))))

(defmethod on-packet [:play :use-item]
  ;; a held bucket used along the look: an empty one aimed at a water or lava source fills and the
  ;; source becomes air; a filled one puts its fluid in the cell before the first solid block,
  ;; empties, and water meeting lava turns it to stone. Anything else does nothing here. Either
  ;; way, the ack.
  [sim {:keys [sequence y-rot x-rot]}]
  (let [ack {:packet/name :block-changed-ack :sequence sequence}
        item (:item (get-in sim [:sim/inv (held-slot sim)]))
        look [y-rot x-rot]
        bucket? (and (:sim/player-pos sim) (contains? bucket-fluids item))
        fluid (get bucket-fluids item)
        src (when (and bucket? (nil? fluid)) (scooped sim look))
        into (when (and bucket? fluid) (poured sim look))]
    (cond src
          (let [state (block-at sim src)]
            (-> sim
                (assoc-in [:sim/placed src] blocks/air)
                (disturb src)
                (send {:packet/name :block-update :pos src :state blocks/air})
                (swap-held (filled-bucket state))
                (send ack)))
          into
          (-> sim
              (assoc-in [:sim/placed into] fluid)
              (send {:packet/name :block-update :pos into :state fluid})
              (quench into)
              (swap-held (get blocks/items :bucket))
              (send ack))
          :else (send sim ack))))

;; ---------------------------------------------------------------- falling blocks

(def fall-delay-ms 100)               ; a falling block looks under itself 2 game ticks after a neighbour changed (FallingBlock.getDelayAfterPlace)
(def fall-gravity 0.04)               ; blocks per tick a falling block gains each tick (FallingBlockEntity.getDefaultGravity)
(def fall-drag 0.98)                  ; the share of its speed it keeps each tick

(defn free?
  "Is the cell holding state id nothing a falling block rests on (FallingBlock.isFree: air, a
   liquid, fire, a plant)? An unloaded cell is not."
  [id]
  (boolean (and id (not (blocks/solid? id)))))

(defn disturb
  "The block at pos changed: a falling block over it looks under itself fall-delay-ms from now,
   as vanilla schedules its tick on the neighbour update."
  [sim [x y z]]
  (let [above [x (inc y) z]]
    (if (blocks/falls? (block-at sim above))
      (assoc-in sim [:sim/falls above] (+ (:sim/now sim) fall-delay-ms))
      sim)))

(defn rest-y
  "The lowest height a block falling in column x z, its bottom in cell y, can come down to: the
   bottom of the free cells straight under it."
  [sim x y z]
  (last (cons y (take-while #(free? (block-at sim [x % z])) (iterate dec (dec y))))))

(defn start-fall
  "The block at pos falls when it still falls and the cell under it is free: its cell becomes air
   for the client, the block above it is disturbed in turn, and it is in the air from its own
   cell (FallingBlockEntity.fall), keyed by where it fell from."
  [sim [x y z :as pos]]
  (let [state (block-at sim pos)]
    (if (and (blocks/falls? state) (free? (block-at sim [x (dec y) z])))
      (-> sim
          (assoc-in [:sim/placed pos] blocks/air)
          (send {:packet/name :block-update :pos pos :state blocks/air})
          (assoc-in [:sim/falling pos] {:state state :y (double y) :v 0.0})
          (disturb pos))
      sim)))

(defn land
  "A falling block comes to rest in cell to: placed there when the cell is free (vanilla drops it
   as an item otherwise, which the model does not), and it looks under itself again."
  [sim [x y z :as to] state]
  (if (free? (block-at sim to))
    (-> sim
        (assoc-in [:sim/placed to] state)
        (send {:packet/name :block-update :pos to :state state})
        (disturb [x (dec y) z]))
    sim))

(defn fall-tick
  "One game tick of the block in the air that fell from origin (FallingBlockEntity.tick): it gains
   fall-gravity of speed and moves down, landing on whatever is under it now (a block that
   landed before it included), else keeps fall-drag of its speed."
  [sim [x _ z :as origin] {:keys [state y v] :as f}]
  (let [v (+ v fall-gravity)
        y' (- y v)
        bottom (rest-y sim x (long (math/floor y)) z)]
    (if (<= y' bottom)
      (land (update sim :sim/falling dissoc origin) [x bottom z] state)
      (assoc-in sim [:sim/falling origin] (assoc f :y y' :v (* v fall-drag))))))

(defn tick-falls
  "One game tick of falling blocks: every block whose look is due falls or stays, then every one in
   the air moves, the lowest first; in position order, so a run replays."
  [sim]
  (let [now (:sim/now sim)
        due (sort (keep (fn [[p at]] (when (<= at now) p)) (:sim/falls sim)))
        sim (reduce (fn [sim p] (start-fall (update sim :sim/falls dissoc p) p)) sim due)]
    (reduce (fn [sim [origin f]] (fall-tick sim origin f))
            sim
            (sort-by (comp second key) (:sim/falling sim)))))

(defn within-pickup?
  "Is an item at [ix iy iz] inside the pickup box of a player at [px py pz] (its box inflated
   by 1 horizontally and 0.5 vertically)?"
  [[px py pz] [ix iy iz]]
  (and (< (abs (- px ix)) 1.3) (< (abs (- pz iz)) 1.3) (< -0.5 (- iy py) 2.3)))

(defn eye-under?
  "Is the player's eye (y + 1.62, from the position the client last reported) in a water block
   of the model's column?"
  [sim]
  (when-let [[x y z] (:sim/player-pos sim)]
    (= :water
       (:fluid/kind (blocks/fluid (block-at sim
                                            [(long (math/floor x)) (long (math/floor (+ y 1.62)))
                                             (long (math/floor z))]))))))

(defn kill
  "At :sim/kill-at, once: the player dies, told to the client as set-health; the respawn then is
   client-command's, as after any death."
  [sim]
  (let [at (:sim/kill-at sim)]
    (cond (or (nil? at) (< (:sim/now sim) at)) sim
          :else
          (-> sim
              (assoc :sim/health 0.0)
              (dissoc :sim/kill-at)
              (send {:packet/name :set-health :health 0.0 :food 20 :saturation 5.0})))))

(defn drown
  "One tick of breath: air falls while the eye is under and refills otherwise; at zero, a hurt
   of drown-damage every drown-every ticks, each told to the client as set-health. A dead
   player (health at zero) is left for client-command to respawn."
  [sim]
  (let [under? (eye-under? sim)
        air (if under? (max 0 (dec (:sim/air sim))) (min max-air (+ (:sim/air sim) air-regained)))
        hurt? (and under?
                   (zero? air)
                   (zero? (mod (quot (:sim/now sim) physics/tick-ms) drown-every))
                   (pos? (:sim/health sim)))
        health (if hurt? (max 0.0 (- (:sim/health sim) drown-damage)) (:sim/health sim))]
    (cond-> (assoc sim :sim/air air :sim/health health)
      hurt? (send {:packet/name :set-health :health health :food 20 :saturation 5.0}))))

(defn tick
  "Advance the server clock to now, one game tick: send a keep-alive when one is due, breathe
   or drown, cook in every furnace, let gravel and sand fall, carry a player through a portal,
   and hand over every item whose pickup delay has passed and
   that the player stands within reach of."
  [sim now]
  (let [sim (through-portal (tick-falls (tick-furnaces (tick-delayed
                                                        (drown (kill (assoc sim :sim/now now))))))
                            now)
        sim (if (>= now (:sim/next-keep-alive sim))
              (-> sim
                  (send {:packet/name :keep-alive :id now})
                  (update :sim/keep-alives-pending conj now)
                  (assoc :sim/next-keep-alive (+ now (:sim/keep-alive-every sim))))
              sim)]
    (reduce
     (fn [sim [eid {:keys [pos spawned]}]]
       (if (and (:sim/player-pos sim)
                (>= (- now spawned) pickup-delay)
                (within-pickup? (:sim/player-pos sim) pos))
         (-> sim
             (update :sim/items dissoc eid)
             (send {:packet/name :take-item-entity :collected eid :collector 1 :count 1})
             (send {:packet/name :remove-entities :ids [eid]})
             (give (get (get (:sim/items sim) eid) :item {:item (:oak_log blocks/items) :count 1})))
         sim))
     sim
     (:sim/items sim))))

(defn step
  "The model's reducer: a client packet or a tick in, the next sim out."
  [sim {:sim/keys [kind packet now]}]
  (case kind
    :packet (on-packet sim packet)
    :tick (tick sim now)
    sim))

(defn drain "[sim' packets-for-the-client]" [sim] [(assoc sim :sim/out []) (:sim/out sim)])

(defn sends
  "The packets a bot world asked to send, from its :bot/effects."
  [world]
  (mapv :effect/packet (filter #(= :send (:effect/kind %)) (:bot/effects world))))

(defn tick-rand
  "[r' x]: the next state of a tick seed r and its roll x in [0, 1), by the same pure step as
   the loot's rolls (roll)."
  [r]
  (let [[{r' :sim/rng} x] (roll {:sim/rng r})] [r' x]))

(defn play-on
  "Play a bot reducer against the model on from a moment: world and sim as they are, a tick
   (physics/tick-ms) at a time from t0, until (stop? world) or max-ms of play. Returns
   {:world :sim :before [world sim]}, :before the pair as it was one tick before the stop: the
   moment a run that then failed can be played on from (a snapshot, simrace). pending is what the
   bot has sent and the model not yet taken (the start packets, for a run from the handshake;
   nothing, on from a snapshot). go is sent once the bot is loaded, or never when nil.
   :sim/lag-ticks in sim delays every server->client packet by that many ticks, in order: a slow
   network as an input. Each tick's :event/rand is 0.5, or, with :sim/tick-seed in sim, the
   next of a pure generator started there (tick-rand), so runs of one world with different seeds
   are different runs, each the same every time."
  [bot-step world sim stop? max-ms t0 pending go]
  (let [lag (* physics/tick-ms (:sim/lag-ticks sim 0))]
    (loop [world (assoc world :bot/effects [])
           sim sim
           pending pending
           t t0
           went? (boolean (nil? go))
           inflight []
           r (:sim/tick-seed sim)
           before [world sim]]
      (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
            sim (step sim {:sim/kind :tick :sim/now t})
            [sim fresh] (drain sim)
            inflight (into inflight (map (fn [p] [(+ t lag) p]) fresh))
            inbound (map second (take-while #(<= (first %) t) inflight))
            inflight (vec (drop-while #(<= (first %) t) inflight))
            world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
            [r rand] (if r (tick-rand r) [nil 0.5])
            world (bot-step world {:event/kind :tick :event/now t :event/rand rand})
            go? (and (not went?) (:player/loaded? world))
            world (if go? (bot-step world go) world)
            out (sends world)
            world (assoc world :bot/effects [])]
        (if (or (stop? world) (> t (+ t0 max-ms)))
          {:world world
           :sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim out) ; deliver the last tick's packets
           :before before}
          (recur world
                 sim
                 out
                 (+ t physics/tick-ms)
                 (boolean (or went? go?))
                 inflight
                 r
                 [world sim]))))))

(defn run+
  "Run a bot reducer against the model from a fresh handshake until (stop? world) or max-ms,
   sending go (default {:event/kind :go}) once the bot is loaded: play-on's answer,
   {:world :sim :before}."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [w (bot-step world0 {:event/kind :start})]
    (play-on bot-step w sim0 stop? max-ms 0 (sends w) (or go {:event/kind :go}))))

(defn run
  "Run a bot reducer against the model from a fresh handshake, a tick (physics/tick-ms) at a
   time, until (stop? world) or max-ms. Sends go (default {:event/kind :go}) once the bot is
   loaded. Returns [world sim] (run+ for the moment before the stop too)."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [{:keys [world sim]} (run+ bot-step world0 sim0 stop? max-ms go)] [world sim]))

;;;; I/O: the model behind a channel pair, on a thread of its own ;;;;


(defn connect!
  "The model behind a channel pair shaped like conn/open!'s: {:in chan :out chan :close! fn}.
   Packet maps the client puts on :out are stepped into the model; every physics/tick-ms of wall time
   the model ticks; whatever it says for the client goes onto :in, in order. Both channels are
   bounded, as conn's are. :in closes when the client closes :out or calls :close!, which the
   loop reads as the socket closing."
  [sim0]
  (let [in (async/chan 1024)
        out (async/chan 256)
        stop (async/chan)
        t0 (System/currentTimeMillis)]
    (async/thread (loop [sim sim0
                         next-tick physics/tick-ms]
                    (let [wait (max 0 (- next-tick (- (System/currentTimeMillis) t0)))
                          [v ch] (async/alts!! [stop out (async/timeout wait)] :priority true)
                          closed? (or (= ch stop) (and (= ch out) (nil? v)))
                          sim (cond closed? sim
                                    (= ch out) (step sim {:sim/kind :packet :sim/packet v})
                                    :else (step sim {:sim/kind :tick :sim/now next-tick}))
                          [sim said] (drain sim)]
                      (doseq [p said] (async/>!! in p))
                      (cond closed? (async/close! in)
                            (= ch out) (recur sim next-tick)
                            :else (recur sim (+ next-tick physics/tick-ms))))))
    {:in in :out out :close! (fn [] (async/close! stop) (async/close! out))}))
