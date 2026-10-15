(ns clojurecraft.window
  "Clicking in a window, as values. A view describes one window the way the click rules need
   it: which window id, its latest state id, its own slots (a crafting grid, a furnace's input,
   fuel and output), and where each player-inventory slot appears in it. Window 0 (the
   player's own screen), an open crafting table and an open furnace share every rule here.

   775 click semantics, all verified live: a click carries the client's prediction (changed
   slots and cursor); the server adopts it and then sends only what differs. We predict no
   changed slots, so every changed slot comes back; and an empty cursor, so the world drops
   :window/cursor on every click and the server corrects it only when it is not empty."
  (:require [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]))

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

(defn resync
  "Learn the server's cursor after a lost click: put it down into a free slot of view v with a
   click the server answers in full (resync-click). A loaded cursor is put down, an empty one
   leaves everything as it was, and either way the whole window and the real cursor come back.
   Fails :inventory-full when no slot is free."
  [world v]
  (if-let [s (free-slot world v)]
    (-> world
        (resync-click v (inventory/left-click s))
        (intent/dissoc-intent :intent/resync))
    (intent/fail world :inventory-full)))
