(ns clojurecraft.inventory
  "The player's items and screen as values: which slot of which window is which inventory slot,
   how a slot update changes :player/inventory, :window/grid and :window/open, and what the bot
   holds. Pure functions of the world; the packet handlers in game call them."
  (:require [clojurecraft.blocks :as blocks]))

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
   its :menu-type (registry minecraft:menu, 12 the crafting table). The bot's views and the
   server model's windows are both read from here."
  {:inventory {:size 2 :inventory 9 :armour 5 :offhand 45}
   :table {:size 3 :inventory 10 :menu-type 12}})

(defn layout-of-menu
  "The layout of a container by its menu type, or nil for one the bot does not know."
  [menu-type]
  (some #(when (= menu-type (:menu-type %)) %) (vals layouts)))

(def main-slots 27)                    ; player slots 9-35, shown first
(def hotbar-slots 9)                   ; player slots 0-8, shown after the main ones

(defn window->player-slot
  "A slot of a window with layout → the player-inventory slot it shows (the key space of
   :player/inventory), or nil when it is the window's own (the result and the grid)."
  [{:keys [inventory armour offhand]} s]
  (let [i (- s inventory)]
    (cond (< -1 i main-slots) (+ i hotbar-slots)
          (< (dec main-slots) i (+ main-slots hotbar-slots)) (- i main-slots)
          (and armour (<= armour s (+ armour 3))) (- 39 (- s armour)) ; head..feet are player 39..36
          (= s offhand) 40
          :else nil)))

(defn player->window-slot
  "A player-inventory slot → the slot of a window with layout that shows it, or nil. The armour
   is not offered: nothing the bot does takes from it."
  [{:keys [inventory offhand]} p]
  (cond (<= 0 p (dec hotbar-slots)) (+ inventory main-slots p)
        (<= hotbar-slots p (+ hotbar-slots main-slots -1)) (+ inventory (- p hotbar-slots))
        (and offhand (= p 40)) offhand
        :else nil))

(defn store-slots
  "The window slots of a layout that show the player's main inventory and hotbar, in order."
  [{:keys [inventory]}]
  (range inventory (+ inventory main-slots hotbar-slots)))

(defn hotbar-start
  "The window slot of a layout that shows hotbar slot 0."
  [{:keys [inventory]}]
  (+ inventory main-slots))

(defn grid-slots
  "The crafting-grid slots of a layout, 1..size²."
  [{:keys [size]}]
  (range 1 (inc (* size size))))

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
