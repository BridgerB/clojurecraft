(ns clojurecraft.window
  "Clicking in a window, as values. A view describes one window the way the click rules need
   it: which window id, its latest state id, its own slots (a crafting grid, a furnace's input,
   fuel and output), and where each player-inventory slot appears in it. Window 0 (the
   player's own screen), an open crafting table and an open furnace share every rule here.

   775 click semantics, all verified live: a click carries the client's prediction (changed
   slots and cursor); the server adopts it and then sends only what differs. We predict no
   changed slots, so every changed slot comes back; and an empty cursor, so the world drops
   :window/cursor on every click and the server corrects it only when it is not empty."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def answer-timeout 3000)             ; a click the server never answers fails the intent

(defn view
  "The window an intent clicks in: :inventory (window 0, 2x2 grid), or a container kind of
   inventory/layouts when that container is the open window (:table, the crafting table's 3x3
   grid; :furnace, size 0, its own slots input, fuel and output). nil when it is not open."
  [world which]
  (let [layout (inventory/layouts which)]
    (if (= which :inventory)
      {:view/id 0
       :view/state-id (:window/state-id world)
       :view/size (:size layout)
       :view/grid (:window/grid world)
       :view/slot-of #(inventory/player->window-slot layout %)}
      (let [{:window/keys [id menu-type state-id slots]} (:window/open world)]
        (when (= (:menu-type layout) menu-type)
          {:view/id id
           :view/state-id state-id
           :view/size (:size layout)
           :view/grid slots
           :view/slot-of #(inventory/player->window-slot layout %)})))))

(def max-lost 3)                      ; clicks the server may leave unanswered in one intent before it fails :stale-window
(def stale-state-id -1)               ; a state id no window ever has

(defn send-click
  "Send one click into a view carrying sent-id as its state id, and remember which state id the
   answer must move (the view's)."
  [world {:view/keys [id state-id]} {:click/keys [slot button mode]} sent-id]
  (-> world
      (game/emit {:packet/name :container-click
                  :window-id id
                  :state-id sent-id
                  :slot slot
                  :button button
                  :mode mode
                  :changed []
                  :cursor nil})
      (dissoc :window/cursor)
      (intent/assoc-intent :intent/awaiting state-id
                           :intent/awaiting-window id
                           :intent/sent-at (:time/now world))))

(defn click
  "Send one click into a view and remember which state id the answer must move."
  [world v c]
  (send-click world v c (:view/state-id v)))

(defn resync-click
  "Send one click with a state id the server's window never has, so that, whatever the click
   changes, the server answers with the whole window and the real cursor (26.1.2's
   handleContainerClick: a state id that differs from the menu's gets broadcastFullState). The
   way to learn the cursor after a lost click, since click forgets it as it sends."
  [world v c]
  (send-click world v c stale-state-id))

(defn state-id-of
  "The latest state id the server sent for window-id: window 0's, or the open container's
   when it is that window. nil for a window that is not open."
  [world window-id]
  (if (zero? window-id)
    (:window/state-id world)
    (when (= window-id (get-in world [:window/open :window/id]))
      (get-in world [:window/open :window/state-id]))))

(defn waiting
  "nil when no click is unanswered; :waiting while the window's state id has not moved;
   :overdue once the answer is later than answer-timeout."
  [world {:intent/keys [awaiting awaiting-window sent-at]}]
  (when (and awaiting (= awaiting (state-id-of world awaiting-window)))
    (if (> (- (:time/now world) sent-at) answer-timeout) :overdue :waiting)))

(defn lost
  "A click went unanswered past answer-timeout: the intent picks up from the server's slots,
   which are the truth. First a resync (resync, the cursor the world shows may not be the
   server's), then the intent goes on at stage restart, a stage that sends no blind next click
   (a craft or a smelt settles again: cursor down, grid or furnace emptied, then laid again; a
   swap into the hand is asked again). The max-lost-th loss fails :stale-window. A loaded server
   answers late rather than never: the race that refilled each server's 25 slots the moment a
   bot ended (issues #131, #132, 34 bots in one round) failed crafts and equips :stale-window on
   the first click left over three seconds, while the smelt, which took this way, went on."
  [world {:intent/keys [lost] :or {lost 0}} restart]
  (if (>= (inc lost) max-lost)
    (intent/fail world :stale-window)
    (-> world
        (intent/dissoc-intent :intent/awaiting :intent/awaiting-window
                              :intent/sent-at :intent/clicks)
        (intent/assoc-intent :intent/lost (inc lost)
                             :intent/resync true
                             :intent/stage restart
                             :intent/since (:time/now world)))))

(defn free-slot
  "A player-inventory slot with nothing in it, as a slot of this view."
  [world {:view/keys [slot-of]}]
  (some (fn [p] (when-not (get-in world [:player/inventory p]) (slot-of p)))
        inventory/main-then-hotbar))

(def junk
  "What a full inventory throws away to make room: what mining and walking pick up that no stage
   asks for, no recipe the goals reach takes and no floor is laid from (stairs/fillers). Race
   38805920624 runner 5 (issues #172, #192): eight bots stopped at 36 full slots, 450 to 830
   cobblestone, the stones that drop themselves, raw copper, lapis, sand and clay among them;
   a craft's result or a furnace's output shift-clicked into the inventory had nowhere to go, the
   server answered nothing, and the resync after it found no free slot: :inventory-full. The
   Nether adds what a tunnel to the fortress picks up (race 39337645053, issue #446: soul sand
   and soil, wart blocks, shroomlights, nuggets, quartz; magma blocks, which a tunnel through a
   basalt delta digs by the stack, probe 39855333521, issue #338, R3-28-1). The fortress's
   own bricks and fences are junk too: no floor is laid from them (stairs/fillers) and no
   recipe the goals reach takes them, and a fortress dug through fills slots with them by
   the hundred (issue #580: 705 nether bricks held, no slot for a craft's result)."
  #{:sand :red_sand :sandstone :red_sandstone :clay_ball :raw_copper :lapis_lazuli :redstone
    :calcite :tuff_bricks :dripstone_block :pointed_dripstone :amethyst_shard :leaf_litter
    :moss_block :moss_carpet :big_dripleaf :small_dripleaf :wildflowers :wheat_seeds :oak_sapling
    :birch_sapling :spruce_sapling :jungle_sapling :acacia_sapling :dark_oak_sapling :cherry_sapling
    :mangrove_propagule :soul_sand :soul_soil :nether_wart_block :warped_wart_block :shroomlight
    :lantern :gold_nugget :quartz :magma_block :nether_bricks :nether_brick_fence})

(def cobblestone-keep 128)            ; cobblestone a full inventory never throws below: a cast's walls, floors and seals

(def filler-keep
  "How many of each building block a full inventory keeps when throwing surplus stacks."
  (zipmap [:cobblestone :cobbled_deepslate :andesite :diorite :granite :tuff :dirt :netherrack
           :blackstone :basalt]
          (repeat cobblestone-keep)))

(def spare-uses 40)                   ; digs a tool must have left to count as a spare: a tunnel's twelve level steps dig about thirty

(defn uses-left
  "How many digs a slot's tool has left: its tier's durability (blocks/materials) less the
   :damage component the server sends on a worn tool (packet/read-slot); nil for an item that
   is no tool."
  [{:keys [item components]}]
  (when-let [{:keys [tier]} (blocks/tool item)]
    (- (:durability (blocks/materials tier)) (get components :damage 0))))

(defn swings-left
  "How many swings or digs a stack has left: its :durability when the model has counted one
   down (sim/wear), else uses-left. Nil when neither is known."
  [item]
  (if (contains? item :durability) (:durability item) (uses-left item)))

(defn worn?
  "Is a slot's tool too worn to be a spare: fewer than spare-uses digs left?"
  [slot]
  (boolean (some-> (uses-left slot)
                   (< spare-uses))))

(defn worn-spare-slot
  "The player slot (main inventory first) of a worn tool (worn?) that the inventory holds a
   better one of the same item beside: the stack a full inventory throws first of its tools. The
   last stack of a tool is never one, however worn. Race 39337645053 (issues #463, #457): bots at
   the flint and steel and the cast held sixteen and eighteen stone pickaxes, each worn out by
   the stairs and tunnels and each made again as a spare (make/resolve-spares), one slot apiece
   because tools do not stack, and the craft that needed a slot failed :inventory-full."
  [world]
  (let [inv (:player/inventory world)
        held (for [p inventory/main-then-hotbar
                   :let [s (get inv p)]
                   :when (and s (worn? s))]
               [p (:item s) (uses-left s)])]
    (some (fn [[p item n]]
            (when (some (fn [q]
                          (when-let [o (get inv q)]
                            (and (not= q p) (= item (:item o)) (> (uses-left o) n))))
                        inventory/main-then-hotbar)
              p))
          held)))

(defn junk-slot
  "The player slot (main inventory first) of the stack a full inventory throws away: the first
   holding junk, else a worn tool with a better one beside it (worn-spare-slot), else a building-block stack whose loss still leaves filler-keep of its kind; nil when
   there is neither."
  [world]
  (let [inv (:player/inventory world)
        counts (recipe/counts inv)
        held (for [p inventory/main-then-hotbar
                   :let [s (get inv p)]
                   :when s]
               [p (recipe/item-name (:item s)) (:count s)])]
    (or (some (fn [[p n]] (when (junk n) p)) held)
        (worn-spare-slot world)
        (some (fn [[p n c]]
                (when-let [keep (filler-keep n)] (when (>= (- (get counts n 0) c) keep) p)))
              held))))

(defn tossable
  "The player slot to throw a stack from so that view v has a free slot: junk-slot when no slot
   is free, else nil. A window intent asks it before it moves anything into the inventory."
  [world v]
  (when-not (free-slot world v) (junk-slot world)))

(def toss-pitch -30.0)                 ; degrees up the eyes look before a throw (negative is up)

(defn toss
  "Throw the whole stack at player slot p out of view v, to make room (tossable). First the eyes
   look up to toss-pitch (a tick, so the server has the look before the click): a stack thrown
   along the look flies about 0.3 blocks a tick from the eyes, so looking down at a table it lands
   a block off, inside the pickup box, and comes back once its two seconds are up; thrown upward
   it lands about three blocks off, out of it."
  [world {:view/keys [slot-of] :as v} p]
  (let [[yaw pitch] (:player/look world)]
    (if (> pitch toss-pitch)
      (assoc world :player/controls {:control/look [yaw toss-pitch]})
      (click world v (inventory/toss-click (slot-of p))))))

(defn resync
  "Learn the server's cursor after a lost click: put it down into a free slot of view v with a
   click the server answers in full (resync-click). A loaded cursor is put down, an empty one
   leaves everything as it was, and either way the whole window and the real cursor come back.
   With no slot free, the resync click throws a junk stack out instead (junk-slot; vanilla throws
   only with nothing on the cursor, and answers in full either way). Fails :inventory-full when
   no slot is free and nothing is junk."
  [world {:view/keys [slot-of] :as v}]
  (let [s (free-slot world v)
        p (junk-slot world)]
    (cond s
          (-> world
              (resync-click v (inventory/left-click s))
              (intent/dissoc-intent :intent/resync))
          p
          (-> world
              (resync-click v (inventory/toss-click (slot-of p)))
              (intent/dissoc-intent :intent/resync))
          :else (intent/fail world :inventory-full))))

(defmethod intent/run :toss
  ;; {:intent/kind :toss}: throw junk out of window 0 (tossable, toss) until a slot is free, then
  ;; done; with nothing to throw it is done too
  [world i _]
  (let [world (assoc world :plan/intent i)
        v (view world :inventory)]
    (case (waiting world i)
      :overdue (lost world i :toss)
      :waiting world
      (cond (:intent/resync i) (resync world v)
            (:window/open world)
            (-> world
                (game/emit {:packet/name :container-close
                            :window-id (get-in world [:window/open :window/id])})
                (dissoc :window/open))
            (tossable world v) (toss world v (tossable world v))
            :else
            (-> world
                (assoc :player/controls {})
                intent/done)))))
