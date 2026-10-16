(ns clojurecraft.inventory
  "The player's items and screen as values: which slot of which window is which inventory slot,
   how a slot update changes :player/inventory, :window/grid and :window/open, and what the bot
   holds. Pure functions of the world; the packet handlers in game call them."
  (:require [clojurecraft.blocks :as blocks]))

(set! *warn-on-reflection* true)

(defn item-count
  "How many of an item (by id) the player holds; the crafting grid is not the inventory."
  [world id]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (= item id)] count)))

(defn held
  "How many items whose ids are in the set ids the player holds (inventory only, never the grid)."
  [world ids]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (ids item)] count)))

(defn logs-held
  "How many logs of any species the player holds (inventory only, never the grid)."
  [world]
  (held world blocks/log-items))

(defn set-slot
  "Put item in a player-inventory slot; a nil item empties it (the key is removed, never nil)."
  [world slot item]
  (if item
    (assoc-in world [:player/inventory slot] item)
    (update world :player/inventory dissoc slot)))

(defn fresh-menus
  "A login or respawn gives the player a fresh menu: nothing open, an empty cursor, and a grid
   the server empties back into the inventory (it then sends the window's contents)."
  [w]
  (-> w
      (dissoc :window/open :window/cursor)
      (assoc :window/grid {})))

(def layouts
  "The windows the bot clicks in, as data. Every vanilla window shows the player's 36 slots after
   its own, 27 main then 9 hotbar, from window slot :inventory on; slot 0 is a crafting result
   and 1..size² the grid. Window 0 (the player's own screen, :inventory) also shows the armour
   at :armour..:armour+3 (head to feet) and the offhand at :offhand; a container is known by
   its :menu-type (registry minecraft:menu: 12 the crafting table, 14 the furnace, whose own
   slots are :input, :fuel and :output, and whose :properties are the container-set-data ids of
   its fire and its cooking). The bot's views and the server model's windows are both read from
   here."
  {:inventory {:size 2 :inventory 9 :armour 5 :offhand 45}
   :table {:size 3 :inventory 10 :menu-type (blocks/menus :crafting)}
   ;; a furnace has no grid: its own slots are the input, the fuel and the output
   :furnace {:size 0
             :inventory 3
             :menu-type (blocks/menus :furnace)
             :input 0
             :fuel 1
             :output 2
             ;; AbstractFurnaceBlockEntity's data: ticks of fire left and of the fuel lit, ticks
             ;; cooked and to cook
             :properties {:lit 0 :lit-total 1 :cook 2 :cook-total 3}}})

(def result-slot 0)                     ; a crafting window's slot 0 shows what its grid makes

(def click-modes
  "The container-click packet's mode, by name (26.1.2 ContainerInput): :pickup picks a stack up
   or puts it down, :quick-move moves it to the other part of the window (a shift-click), :swap
   trades it with the hotbar slot the button names, :throw drops it out of the window (only with
   nothing on the cursor)."
  {:pickup 0 :quick-move 1 :swap 2 :throw 4})

(def left-button 0)                    ; with :pickup, the whole stack
(def right-button 1)                   ; with :pickup, one item put down (or half picked up)
(def whole-stack 1)                    ; with :throw, the whole stack (0 throws one item)

(defn left-click
  "Pick up the whole stack at window slot s, or put the cursor's down there."
  [s]
  {:click/slot s :click/button left-button :click/mode (click-modes :pickup)})

(defn right-click
  "Put one item of the cursor down at window slot s."
  [s]
  {:click/slot s :click/button right-button :click/mode (click-modes :pickup)})

(defn shift-click
  "Move the stack at window slot s to the other part of the window (a quick move)."
  [s]
  {:click/slot s :click/button left-button :click/mode (click-modes :quick-move)})

(defn toss-click
  "Throw the whole stack at window slot s out of the window, onto the ground (ctrl+Q)."
  [s]
  {:click/slot s :click/button whole-stack :click/mode (click-modes :throw)})

(defn swap-click
  "Trade the stack at window slot s with hotbar slot n."
  [s n]
  {:click/slot s :click/button n :click/mode (click-modes :swap)})

(defn layout-of-menu
  "The layout of a container by its menu type, or nil for one the bot does not know."
  [menu-type]
  (some #(when (= menu-type (:menu-type %)) %) (vals layouts)))

(def main-slots 27)                    ; player slots 9-35, shown first
(def hotbar-slots 9)                   ; player slots 0-8, shown after the main ones
(def head-slot 39)                     ; the player slot of the helmet; the boots are 36, the armour counts down
(def offhand-slot 40)                  ; the player slot of the offhand

(defn hotbar?
  "Is player slot p one of the hotbar's, the only slots the hand can hold from?"
  [p]
  (< -1 p hotbar-slots))

(def main-then-hotbar
  "Every player slot of the main inventory and the hotbar, the main inventory first: the order a
   free slot is looked for in, so the hotbar stays for what the hand needs."
  (concat (range hotbar-slots (+ hotbar-slots main-slots)) (range hotbar-slots)))

(defn window->player-slot
  "A slot of a window with layout → the player-inventory slot it shows (the key space of
   :player/inventory), or nil when it is the window's own (the result and the grid)."
  [{:keys [inventory armour offhand]} s]
  (let [i (- s inventory)]
    (cond (< -1 i main-slots) (+ i hotbar-slots)
          (< (dec main-slots) i (+ main-slots hotbar-slots)) (- i main-slots)
          (and armour (<= armour s (+ armour 3))) (- head-slot (- s armour)) ; head to feet
          (= s offhand) offhand-slot
          :else nil)))

(defn player->window-slot
  "A player-inventory slot → the slot of a window with layout that shows it, or nil. The armour
   is not offered: nothing the bot does takes from it."
  [{:keys [inventory offhand]} p]
  (cond (<= 0 p (dec hotbar-slots)) (+ inventory main-slots p)
        (<= hotbar-slots p (+ hotbar-slots main-slots -1)) (+ inventory (- p hotbar-slots))
        (and offhand (= p offhand-slot)) offhand
        :else nil))

(defn store-slots
  "The window slots of a layout that show the player's main inventory and hotbar, in order."
  [{:keys [inventory]}]
  (range inventory (+ inventory main-slots hotbar-slots)))

(defn hotbar-start
  "The window slot of a layout that shows hotbar slot 0."
  [{:keys [inventory]}]
  (+ inventory main-slots))

(defn crafting?
  "Does a window with layout craft: a grid whose slot 0 shows what it makes? A furnace does not."
  [layout]
  (pos? (:size layout)))

(defn slot-in
  "The slot of window k (a layout name: :inventory, :table, :furnace) that shows a place, named:
   [:hotbar n] (0-8, left to right), [:main n] (0-26, from the top left), in window 0 also
   [:armour n] (0-3, head to feet) and [:offhand]; a crafting window's [:result] and [:grid n]
   (1..size², by rows); a furnace's [:input], [:fuel] and [:output]. The numbers are read from
   layouts, so code and tests say where an item is rather than which wire slot that is
   ([:hotbar 0] is 36 in window 0, 30 in a furnace). nil for a place the window does not show."
  [k [area n]]
  (let [l (layouts k)
        n (or n 0)]
    (case area
      :hotbar (+ (hotbar-start l) n)
      :main (+ (:inventory l) n)
      :armour
      (some-> (:armour l)
              (+ n))
      :offhand (:offhand l)
      :result (when (crafting? l) result-slot)
      :grid (when (crafting? l) n)
      (:input :fuel :output) (get l area))))

(defn slot
  "The window-0 slot of a place in the player's own screen (slot-in :inventory): [:hotbar 0] is 36."
  [place]
  (slot-in :inventory place))

(defn grid-slots
  "The crafting-grid slots of a layout, 1..size²."
  [{:keys [size]}]
  (range 1 (inc (* size size))))

(defn own-slots
  "The slots of a window with layout that hold the container's own items, not the player's: a
   crafting grid's 1..size² (its slot 0 is computed, never held), or a furnace's input, fuel and
   output."
  [layout]
  (if (crafting? layout) (grid-slots layout) ((juxt :input :fuel :output) layout)))

(defn slot-count
  "How many slots a window with layout shows: its own, then the player's 36, then (window 0
   only) the offhand."
  [{:keys [inventory offhand]}]
  (if offhand (inc offhand) (+ inventory main-slots hotbar-slots)))

(defn translate
  "Slot s of the window with layout from, as the slot of the window with layout to that shows
   the same player slot; nil when to does not show it."
  [from to s]
  (some->> (window->player-slot from s)
           (player->window-slot to)))

(defn set-open-window-slot
  "An open container: its own slots live in :window/open's :window/slots; slots that are the
   player's inventory update :player/inventory, so items are never counted in two places."
  [w s item]
  (let [p (some-> (layout-of-menu (get-in w [:window/open :window/menu-type]))
                  (window->player-slot s))]
    (cond p (set-slot w p item)
          item (assoc-in w [:window/open :window/slots s] item)
          :else (update-in w [:window/open :window/slots] dissoc s))))

(defn open?
  "Is window-id the container the player has open?"
  [w window-id]
  (= window-id (get-in w [:window/open :window/id])))

(defn set-window-0-slot
  "Window 0 is the player's own screen: slots 0-4 are the crafting grid (0 is the result) and
   are kept verbatim in :window/grid, so nothing in the grid is ever invisible; the rest map to
   :player/inventory."
  [w i item]
  (cond (<= 0 i 4) (if item (assoc-in w [:window/grid i] item) (update w :window/grid dissoc i))
        (window->player-slot (layouts :inventory) i)
        (set-slot w (window->player-slot (layouts :inventory) i) item)
        :else w))
