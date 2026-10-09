(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, pickup when the player comes
   within the inflated pickup box after the 10-tick delay, and window 0 (the player's
   inventory with its 2x2 crafting grid) with vanilla click rules and state ids.

   Fault knobs are inputs, not hidden state: :sim/drop-clicks is a set of click ordinals
   (0-based) the server silently loses. :sim/violations records anything a real server would
   punish or a careful client must never do: a click on an empty result slot, a close with a
   loaded cursor."
  (:refer-clojure :exclude [send])
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.recipe :as recipe]))

(def dig-ms 3000)                       ; a log by hand (hardness 2)
(def leaf-dig-ms 300)                   ; leaves by hand (hardness 0.2)

(defn block-at
  "The server's view of a block: placed blocks, broken positions (air), else the fixture column."
  [sim pos]
  (cond (contains? (:sim/placed sim) pos) (get-in sim [:sim/placed pos])
        (contains? (:sim/broken sim) pos) 0
        :else (chunk/block-at {[0 0] (:sim/chunk sim)} pos)))

(defn- break-ms [id] (if (blocks/leaves? id) leaf-dig-ms dig-ms))
(def pickup-delay 500)
(def keep-alive-every 15000)

(defn init [{:keys [column spawn keep-alive-every drop-clicks inventory] :or {keep-alive-every keep-alive-every}}]
  {:sim/phase :handshake
   :sim/inv (or inventory {})
   :sim/state-id 1
   :sim/held 0
   :sim/placed {}
   :sim/next-window 1
   :sim/clicks 0
   :sim/drop-clicks (or drop-clicks #{})
   :sim/violations []
   :sim/keep-alive-every keep-alive-every
   :sim/now 0
   :sim/out []
   :sim/column column
   :sim/chunk (chunk/decode column)
   :sim/spawn spawn
   :sim/broken #{}
   :sim/items {}
   :sim/next-eid 100
   :sim/next-keep-alive keep-alive-every
   :sim/keep-alives-pending #{}})

(defn- send [sim pkt] (update sim :sim/out conj pkt))

(defmulti on-packet (fn [sim pkt] [(:sim/phase sim) (:packet/name pkt)]))
(defmethod on-packet :default [sim _] sim)

(defmethod on-packet [:handshake :intention] [sim _] (assoc sim :sim/phase :login))
(defmethod on-packet [:login :hello] [sim {:keys [username uuid]}]
  (send sim {:packet/name :login-finished :uuid uuid :username username}))
(defmethod on-packet [:login :login-acknowledged] [sim _]
  (-> sim (assoc :sim/phase :configuration) (send {:packet/name :select-known-packs})))
(defmethod on-packet [:configuration :select-known-packs] [sim _]
  (send sim {:packet/name :finish-configuration}))
(defmethod on-packet [:configuration :finish-configuration] [sim _]
  (let [[x y z] (:sim/spawn sim)]
    (-> sim
        (assoc :sim/phase :play)
        (send {:packet/name :login :entity-id 1})
        (send {:packet/name :player-position :teleport-id 1 :x x :y y :z z :dx 0.0 :dy 0.0 :dz 0.0
               :yaw 0.0 :pitch 0.0 :flags 0})
        (send {:packet/name :container-set-content :window-id 0 :state-id (:sim/state-id sim)
               :items (mapv #(get-in sim [:sim/inv %]) (range 46)) :carried nil})
        (send {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data (:sim/column sim)})
        (send {:packet/name :chunk-batch-finished :batch-size 1}))))

(defmethod on-packet [:play :move-player-pos-rot] [sim {:keys [x y z]}] (assoc sim :sim/player-pos [x y z]))
(defmethod on-packet [:play :move-player-pos] [sim {:keys [x y z]}] (assoc sim :sim/player-pos [x y z]))
(defmethod on-packet [:play :keep-alive] [sim {:keys [id]}] (update sim :sim/keep-alives-pending disj id))

(defmethod on-packet [:play :player-action] [sim {:keys [status pos sequence]}]
  (case (long status)
    0 (assoc sim :sim/dig {:pos pos :at (:sim/now sim)})
    2 (let [{:keys [at] dpos :pos} (:sim/dig sim)
            id (block-at sim pos)]
        (if (and (= dpos pos) id (pos? id) (>= (- (:sim/now sim) at) (break-ms id)))
          (let [eid (:sim/next-eid sim)
                [x y z] pos
                item-pos [(+ x 0.5) (+ y 0.25) (+ z 0.5)]
                sim (-> sim
                        (update :sim/broken conj pos)
                        (dissoc :sim/dig)
                        ;; observed live on 26.1.2: the breaker gets the block update, then the ack
                        (send {:packet/name :block-update :pos pos :state 0})
                        (send {:packet/name :block-changed-ack :sequence sequence}))]
            (if (blocks/log? id)
              (-> sim
                  (assoc :sim/next-eid (inc eid))
                  (assoc-in [:sim/items eid] {:pos item-pos :spawned (:sim/now sim)})
                  (send {:packet/name :add-entity :entity-id eid :uuid nil :type blocks/item-entity-type
                         :x (first item-pos) :y (second item-pos) :z (nth item-pos 2)}))
              sim))
          (-> sim (dissoc :sim/dig) (send {:packet/name :block-changed-ack :sequence sequence}))))
    sim))

;; ---------------------------------------------------------------- windows

(def max-stack 64)

(def table-state (first (keep (fn [[n _ lo]] (when (= n :crafting_table) lo)) blocks/table)))

(def layouts
  "Window 0 has a 2x2 grid and the inventory at 9-44; a crafting table has a 3x3 grid and the
   same inventory shifted to 10-45. Slot 0 is always the result."
  {:inventory {:size 2 :store (range 9 45) :hotbar 36}
   :table {:size 3 :store (range 10 46) :hotbar 37}})

(defn- grid-slots [{:keys [size]}] (range 1 (inc (* size size))))

(defn- result-of [view layout]
  (let [grid (into {} (for [s (grid-slots layout) :let [it (get view s)] :when it] [s (recipe/item-name (:item it))]))]
    (when-let [r (recipe/match grid (:size layout))]
      {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)})))

(defn- view-of [sim kind]
  (case kind
    :inventory (:sim/inv sim)
    :table (merge (get-in sim [:sim/window :grid])
                  (into {} (for [[s it] (:sim/inv sim) :when (<= 9 s 44)] [(inc s) it])))))

(defn- with-view [sim kind view]
  (let [view (dissoc view 0)]
    (case kind
      :inventory (assoc sim :sim/inv view)
      :table (-> sim
                 (assoc-in [:sim/window :grid] (into {} (for [s (range 1 10) :let [it (get view s)] :when it] [s it])))
                 (assoc :sim/inv (merge (into {} (remove (fn [[s _]] (<= 9 s 44)) (:sim/inv sim)))
                                        (into {} (for [[s it] view :when (<= 10 s 45)] [(dec s) it]))))))))

(defn- full [view layout] (assoc view 0 (result-of view layout)))

(defn- insert
  "Put a stack into the given slots, stacking first. Returns [view leftover]."
  [view slots {:keys [item count]}]
  (let [order (concat (filter #(= item (:item (get view %))) slots) (filter #(nil? (get view %)) slots))]
    (loop [view view [s & more] order n count]
      (if (or (zero? n) (nil? s))
        [view (when (pos? n) {:item item :count n})]
        (let [have (:count (get view s) 0) k (min n (- max-stack have))]
          (recur (assoc view s {:item item :count (+ have k)}) more (- n k)))))))

(defn- take-one [view s]
  (let [{:keys [count] :as it} (get view s)]
    (if (> count 1) (assoc view s (assoc it :count (dec count))) (dissoc view s))))

(defn- craft-once [view layout]
  (reduce (fn [view s] (if (get view s) (take-one view s) view)) view (grid-slots layout)))

(defn- click-view
  "Vanilla click rules over one window's slots. Returns {:view :cursor :violation}."
  [layout view cursor {:keys [slot button mode]}]
  (let [at (get view slot)
        store (:store layout)]
    (cond
      (= slot 0)
      (if-let [out (result-of view layout)]
        (if (= mode 1)
          {:view (loop [view view]
                   (if-let [out (result-of view layout)]
                     (let [[view' left] (insert view store out)]
                       (if left view (recur (craft-once view' layout))))
                     view))
           :cursor cursor}
          (if (or (nil? cursor) (and (= (:item cursor) (:item out)) (<= (+ (:count cursor) (:count out)) max-stack)))
            {:view (craft-once view layout) :cursor (update out :count + (:count cursor 0))}
            {:view view :cursor cursor}))
        {:view view :cursor cursor :violation [:click-on-empty-result mode]})

      (= mode 2)
      (let [h (+ (:hotbar layout) button) other (get view h)]
        {:view (-> view (dissoc slot h) (cond-> other (assoc slot other) at (assoc h at))) :cursor cursor})

      (= mode 1)
      (if (and at (some #{slot} (grid-slots layout)))
        (let [[view' left] (insert (dissoc view slot) store at)] {:view (cond-> view' left (assoc slot left)) :cursor cursor})
        {:view view :cursor cursor})

      (= button 0)
      (cond (nil? cursor) {:view (dissoc view slot) :cursor at}
            (nil? at) {:view (assoc view slot cursor) :cursor nil}
            (= (:item at) (:item cursor))
            (let [k (min (:count cursor) (- max-stack (:count at)))
                  left (- (:count cursor) k)]
              {:view (assoc view slot (update at :count + k)) :cursor (when (pos? left) (assoc cursor :count left))})
            :else {:view (assoc view slot cursor) :cursor at})

      :else
      (cond (nil? cursor) (if at
                            (let [half (long (Math/ceil (/ (:count at) 2)))
                                  rest (- (:count at) half)]
                              {:cursor (assoc at :count half)
                               :view (if (pos? rest) (assoc view slot (assoc at :count rest)) (dissoc view slot))})
                            {:view view :cursor nil})
            (or (nil? at) (and (= (:item at) (:item cursor)) (< (:count at) max-stack)))
            {:view (assoc view slot {:item (:item cursor) :count (inc (:count at 0))})
             :cursor (when (> (:count cursor) 1) (update cursor :count dec))}
            :else {:view view :cursor cursor}))))

(defn- state-id-path [kind] (case kind :inventory [:sim/state-id] :table [:sim/window :state-id]))
(defn- window-id [sim kind] (case kind :inventory 0 :table (get-in sim [:sim/window :id])))

(defn- sync-window
  "Answer a click the way vanilla does. The click's own changed-slots and cursor fields are the
   client's prediction: the server records them as what the client believes, then sends only
   what differs from that belief. We always predict no changed slots (so every changed slot is
   sent) and an empty cursor (so the cursor is sent only when it is not empty). A stale state
   id gets the full window instead. predicted-cursor is the cursor the client last claimed."
  [sim kind before predicted-cursor stale?]
  (let [layout (layouts kind)
        after (full (view-of sim kind) layout)
        id (window-id sim kind)
        path (state-id-path kind)]
    (if stale?
      (let [sim (update-in sim path inc)]
        (send sim {:packet/name :container-set-content :window-id id :state-id (get-in sim path)
                   :items (mapv #(get after %) (range 46)) :carried (:sim/cursor sim)}))
      (as-> sim sim
        (reduce (fn [sim s]
                  (if (= (get before s) (get after s))
                    sim
                    (let [sim (update-in sim path inc)]
                      (send sim {:packet/name :container-set-slot :window-id id :state-id (get-in sim path)
                                 :slot s :item (get after s)}))))
                sim (range 46))
        (if (= predicted-cursor (:sim/cursor sim)) sim (send sim {:packet/name :set-cursor-item :item (:sim/cursor sim)}))))))

(defn- window-kind [sim window-id]
  (cond (zero? window-id) :inventory
        (= window-id (get-in sim [:sim/window :id])) :table))

(defmethod on-packet [:play :container-click] [sim {:keys [window-id state-id] :as pkt}]
  (let [n (:sim/clicks sim)
        sim (update sim :sim/clicks inc)
        kind (window-kind sim window-id)]
    (if (or (nil? kind) (contains? (:sim/drop-clicks sim) n))
      sim
      (let [layout (layouts kind)
            before (full (view-of sim kind) layout)
            {:keys [view cursor violation]} (click-view layout (view-of sim kind) (:sim/cursor sim) pkt)
            sim (cond-> (-> sim (with-view kind view) (assoc :sim/cursor cursor))
                  violation (update :sim/violations conj violation))]
        (sync-window sim kind before (:cursor pkt) (not= state-id (get-in sim (state-id-path kind))))))))

(defmethod on-packet [:play :container-close] [sim {:keys [window-id]}]
  (let [sim (cond-> sim (:sim/cursor sim) (update :sim/violations conj [:close-with-cursor (:sim/cursor sim)]))]
    (if (= :table (window-kind sim window-id))
      (let [before (full (:sim/inv sim) (layouts :inventory))
            inv (reduce (fn [inv it] (first (insert inv (range 9 45) it))) (:sim/inv sim) (vals (get-in sim [:sim/window :grid])))]
        (-> sim (assoc :sim/inv inv) (dissoc :sim/window) (sync-window :inventory before (:sim/cursor sim) false)))
      sim)))

(defn- give
  "An item entity reaches the inventory: stack it into window 0 and tell the client."
  [sim item]
  (let [before (full (:sim/inv sim) (layouts :inventory))
        [inv _] (insert (:sim/inv sim) (range 9 45) item)]
    (sync-window (assoc sim :sim/inv inv) :inventory before (:sim/cursor sim) false)))

;; ---------------------------------------------------------------- using items on blocks

(def faces {0 [0 -1 0] 1 [0 1 0] 2 [0 0 -1] 3 [0 0 1] 4 [-1 0 0] 5 [1 0 0]})

(defn- overlaps-player? [sim [x y z]]
  (when-let [[px py pz] (:sim/player-pos sim)]
    (and (< (- px 0.3) (inc x)) (> (+ px 0.3) x)
         (< py (inc y)) (> (+ py 1.8) y)
         (< (- pz 0.3) (inc z)) (> (+ pz 0.3) z))))

(defmethod on-packet [:play :set-carried-item] [sim {:keys [slot]}] (assoc sim :sim/held slot))

(defmethod on-packet [:play :use-item-on] [sim {:keys [pos face sequence]}]
  (let [ack {:packet/name :block-changed-ack :sequence sequence}]
    (if (= table-state (block-at sim pos))
      (let [id (:sim/next-window sim)
            sim (-> sim (assoc :sim/window {:id id :grid {} :state-id 1}) (update :sim/next-window inc))]
        (-> sim
            (send ack)
            (send {:packet/name :open-screen :window-id id :menu-type 12 :title (byte-array 0)})
            (send {:packet/name :container-set-content :window-id id :state-id 1
                   :items (mapv #(get (full (view-of sim :table) (layouts :table)) %) (range 46))
                   :carried (:sim/cursor sim)})))
      (let [dest (mapv + pos (faces face))
            slot (+ 36 (:sim/held sim 0))
            held (get-in sim [:sim/inv slot])
            d (block-at sim dest)
            overlap? (overlaps-player? sim dest)
            ok? (and held (= (:item held) (recipe/item-id :crafting_table))
                     d (not (blocks/solid? d)) (not= :liquid (blocks/type-of d))
                     (blocks/solid? (or (block-at sim pos) 0))
                     (not overlap?))
            sim (cond-> sim overlap? (update :sim/violations conj [:place-into-player dest]))]
        (if ok?
          (let [before (full (:sim/inv sim) (layouts :inventory))]
            (-> sim
                (assoc-in [:sim/placed dest] table-state)
                (update :sim/inv take-one slot)
                (send {:packet/name :block-update :pos dest :state table-state})
                (sync-window :inventory before (:sim/cursor sim) false)
                (send ack)))
          (-> sim (send {:packet/name :block-update :pos dest :state (or d 0)}) (send ack)))))))

(defn- within-pickup? [[px py pz] [ix iy iz]]
  (and (< (abs (- px ix)) 1.3) (< (abs (- pz iz)) 1.3) (< -0.5 (- iy py) 2.3)))

(defn- tick [sim now]
  (let [sim (assoc sim :sim/now now)
        sim (if (>= now (:sim/next-keep-alive sim))
              (-> sim
                  (send {:packet/name :keep-alive :id now})
                  (update :sim/keep-alives-pending conj now)
                  (assoc :sim/next-keep-alive (+ now (:sim/keep-alive-every sim))))
              sim)]
    (reduce (fn [sim [eid {:keys [pos spawned]}]]
              (if (and (:sim/player-pos sim)
                       (>= (- now spawned) pickup-delay)
                       (within-pickup? (:sim/player-pos sim) pos))
                (-> sim
                    (update :sim/items dissoc eid)
                    (send {:packet/name :take-item-entity :collected eid :collector 1 :count 1})
                    (send {:packet/name :remove-entities :ids [eid]})
                    (give {:item (:oak_log blocks/items) :count 1}))
                sim))
            sim (:sim/items sim))))

(defn step [sim {:sim/keys [kind packet now]}]
  (case kind
    :packet (on-packet sim packet)
    :tick (tick sim now)
    sim))

(defn drain
  "[sim' packets-for-the-client]"
  [sim]
  [(assoc sim :sim/out []) (:sim/out sim)])

(defn- sends [world]
  (mapv :effect/packet (filter #(= :send (:effect/kind %)) (:bot/effects world))))

(defn run
  "Run a bot reducer against the model from a fresh handshake, 50 ms per tick, until (stop?
   world) or max-ms. Sends go (default {:event/kind :go}) once the bot is loaded. Returns
   [world sim]."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [w (bot-step world0 {:event/kind :start})]
    (loop [world (assoc w :bot/effects []) sim sim0 pending (sends w) t 0 went? false]
      (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
            sim (step sim {:sim/kind :tick :sim/now t})
            [sim inbound] (drain sim)
            world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
            world (bot-step world {:event/kind :tick :event/now t :event/rand 0.5})
            go? (and (not went?) (:player/loaded? world))
            world (if go? (bot-step world (or go {:event/kind :go})) world)
            out (sends world)
            world (assoc world :bot/effects [])]
        (if (or (stop? world) (> t max-ms))
          [world (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim out)]   ; deliver the last tick's packets
          (recur world sim out (+ t 50) (or went? go?)))))))
