(ns clojurecraft.window
  "Clicking in a window, as values. A view describes one window the way the click rules need
   it: which window id, its latest state id, its crafting grid, and where each player-inventory
   slot appears in it. Window 0 (the player's own screen) and an open crafting table share
   every rule here.

   775 click semantics, all verified live: a click carries the client's prediction (changed
   slots and cursor); the server adopts it and then sends only what differs. We predict no
   changed slots, so every changed slot comes back; and an empty cursor, so the world drops
   :window/cursor on every click and the server corrects it only when it is not empty."
  (:require [clojurecraft.game :as game]
            [clojurecraft.recipe :as recipe]))

(def answer-timeout 3000)             ; a click the server never answers fails the intent

(defn table-slot
  "Player-inventory slot → crafting-table window slot (hotbar 0-8 → 37-45, store 9-35 → 10-36),
   or nil for armor and offhand, which the table window does not show."
  [p] (cond (<= 0 p 8) (+ 37 p) (<= 9 p 35) (inc p) :else nil))

(defn view
  "The window an intent clicks in: :inventory (window 0, 2x2 grid) or :table (the open
   crafting table, 3x3 grid). nil when that window is not open."
  [world which]
  (case which
    :inventory {:view/id 0 :view/state-id (:window/state-id world) :view/size 2
                :view/grid (:window/grid world) :view/slot-of recipe/player->container-slot}
    :table (let [{:window/keys [id menu-type state-id slots]} (:window/open world)]
             (when (= 12 menu-type)
               {:view/id id :view/state-id state-id :view/size 3
                :view/grid slots :view/slot-of table-slot}))))

(defn click
  "Send one click into a view and remember which state id the answer must move."
  [world {:view/keys [id state-id]} {:click/keys [slot button mode]}]
  (-> world
      (game/emit {:packet/name :container-click :window-id id :state-id state-id
                  :slot slot :button button :mode mode :changed [] :cursor nil})
      (dissoc :window/cursor)
      (update :plan/intent assoc :intent/awaiting state-id :intent/awaiting-window id
              :intent/sent-at (:time/now world))))

(defn state-id-of
  "The latest state id the server sent for window-id: window 0's, or the open container's
   when it is that window. nil for a window that is not open."
  [world window-id]
  (if (zero? window-id)
    (:window/state-id world)
    (when (= window-id (get-in world [:window/open :window/id])) (get-in world [:window/open :window/state-id]))))

(defn waiting
  "nil when no click is unanswered; :waiting while the window's state id has not moved;
   :overdue once the answer is later than answer-timeout."
  [world {:intent/keys [awaiting awaiting-window sent-at]}]
  (when (and awaiting (= awaiting (state-id-of world awaiting-window)))
    (if (> (- (:time/now world) sent-at) answer-timeout) :overdue :waiting)))

(defn free-slot
  "A player-inventory slot with nothing in it, as a slot of this view."
  [world {:view/keys [slot-of]}]
  (some (fn [p] (when-not (get-in world [:player/inventory p]) (slot-of p))) (concat (range 9 36) (range 0 9))))
