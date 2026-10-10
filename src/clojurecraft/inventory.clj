(ns clojurecraft.inventory
  "The player's items and screen as values: which slot of which window is which inventory slot,
   how a slot update changes :player/inventory, :window/grid and :window/open, and what the bot
   holds. Pure functions of the world; the packet handlers in game call them."
  (:require [clojurecraft.blocks :as blocks]))

(defn item-count
  "How many of an item (by id) the player holds; the crafting grid is not the inventory."
  [world id]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (= item id)] count)))

(defn logs-held
  "How many logs of any species the player holds (inventory only, never the grid)."
  [world]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (blocks/log-item? item)] count)))

(defn container->player-slot
  "Window-0 slot → player-inventory slot (the key space of :player/inventory), or nil."
  [s]
  (cond (<= 36 s 44) (- s 36)
        (<= 9 s 35) s
        (<= 5 s 8) (- 44 s)                ; window 5-8 are head..feet; player 36-39 are feet..head
        (= s 45) 40
        :else nil))

(defn set-slot
  "Put item in a player-inventory slot; a nil item empties it (the key is removed, never nil)."
  [world slot item]
  (if item (assoc-in world [:player/inventory slot] item) (update world :player/inventory dissoc slot)))

(defn fresh-menus
  "A login or respawn gives the player a fresh menu: nothing open, an empty cursor, and a grid
   the server empties back into the inventory (it then sends the window's contents)."
  [w]
  (-> w (dissoc :window/open :window/cursor) (assoc :window/grid {})))

(def menus
  "Container layouts by menu type id (registry minecraft:menu). :menu/grid are the crafting
   slots (0 is the result); :menu/inventory is the first of the 36 player-inventory slots,
   27 main then 9 hotbar, as every vanilla container appends them."
  {12 {:menu/name :crafting :menu/size 3 :menu/grid (range 0 10) :menu/inventory 10}})

(defn window->player-slot
  "A slot of an open container → player-inventory slot, or nil when it is the container's own."
  [menu-type s]
  (when-let [start (:menu/inventory (menus menu-type))]
    (let [i (- s start)]
      (cond (<= 0 i 26) (+ i 9)
            (<= 27 i 35) (- i 27)
            :else nil))))

(defn set-open-window-slot
  "An open container: its own slots live in :window/open's :window/slots; slots that are the
   player's inventory update :player/inventory, so items are never counted in two places."
  [w s item]
  (let [menu-type (get-in w [:window/open :window/menu-type])]
    (if-let [p (window->player-slot menu-type s)]
      (set-slot w p item)
      (if item
        (assoc-in w [:window/open :window/slots s] item)
        (update-in w [:window/open :window/slots] dissoc s)))))

(defn open? "Is window-id the container the player has open?" [w window-id] (= window-id (get-in w [:window/open :window/id])))

(defn set-window-0-slot
  "Window 0 is the player's own screen: slots 0-4 are the crafting grid (0 is the result) and
   are kept verbatim in :window/grid, so nothing in the grid is ever invisible; the rest map to
   :player/inventory."
  [w i item]
  (cond
    (<= 0 i 4) (if item (assoc-in w [:window/grid i] item) (update w :window/grid dissoc i))
    (container->player-slot i) (set-slot w (container->player-slot i) item)
    :else w))
