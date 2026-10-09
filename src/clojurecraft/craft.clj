(ns clojurecraft.craft
  "The :craft intent: one craft of a recipe in the player's own 2x2 grid (window 0).

     {:intent/kind :craft :intent/recipe :stick}

   Stages, each advanced one tick at a time against the world value:
     :settle  close any open container, put a loaded cursor down, shift-click every item left
              in the grid back into the inventory, then plan the clicks from the inventory
     :click   one click per round trip: send it, then wait until window 0's state id moves
     :verify  only when the server says slot 0 holds exactly the expected result, shift-click it
     :take    done when the inventory shows the result

   Rules that come from the siblings' failures: nothing is predicted (every click carries an
   empty prediction, so the server answers with authoritative slots); a click is never sent
   while the previous one is unanswered; slot 0 is never clicked unless it holds what the recipe
   makes (a stray plank turns a take into a button); every deadline is an absolute time."
  (:require [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.recipe :as recipe]))

(def grid-size 2)
(def answer-timeout 3000)          ; a click the server never answers fails the intent
(def stage-timeout 5000)

(defn- now [world] (:time/now world))
(defn- set-intent [world & kvs] (apply update world :plan/intent assoc kvs))

(defn- click
  "Send one click. In 775 the click's cursor field is a claim the server adopts as its record
   of our cursor, and it sends set-cursor-item only when the real cursor differs. We claim an
   empty cursor, so the world must believe the same until corrected: otherwise a cursor emptied
   by the click is never reported and stays loaded in our model forever."
  [world {:click/keys [slot button mode]}]
  (-> world
      (game/emit {:packet/name :container-click :window-id 0 :state-id (:window/state-id world)
                  :slot slot :button button :mode mode :changed [] :cursor nil})
      (dissoc :window/cursor)
      (set-intent :intent/awaiting (:window/state-id world) :intent/sent-at (now world))))

(defn- waiting?
  "Is a click still unanswered? Fails the intent when the answer is overdue."
  [world {:intent/keys [awaiting sent-at]}]
  (and awaiting (= awaiting (:window/state-id world))
       (if (> (- (now world) sent-at) answer-timeout)
         :overdue
         true)))

(defn- empty-slot
  "A free window-0 inventory slot to put a stray cursor down in."
  [world]
  (first (remove (fn [s] (get-in world [:player/inventory (game/container->player-slot s)])) (range 9 45))))

(defn- dirty-grid-slot [world] (first (filter #(get-in world [:window/grid %]) [1 2 3 4])))

(defmulti stage (fn [_world i _event] (:intent/stage i)))

(defmethod stage :settle [world {:intent/keys [recipe since]} _]
  (let [r (recipe/by-id recipe)]
    (cond
      (nil? r) (intent/fail world :unknown-recipe)
      (:window/open world)
      (-> world
          (game/emit {:packet/name :container-close :window-id (get-in world [:window/open :window/id])})
          (dissoc :window/open))
      (nil? (:window/state-id world))
      (if (> (- (now world) since) stage-timeout) (intent/fail world :no-window) world)
      (:window/cursor world)
      (if-let [s (empty-slot world)]
        (click world {:click/slot s :click/button 0 :click/mode 0})
        (intent/fail world :inventory-full))
      (dirty-grid-slot world)
      (click world {:click/slot (dirty-grid-slot world) :click/button 0 :click/mode 1})
      :else
      (if-let [cs (recipe/clicks (:player/inventory world) r grid-size)]
        (set-intent world :intent/stage :click :intent/clicks cs :intent/since (now world)
                    :intent/result (recipe/item-id (:recipe/result r)) :intent/makes (:recipe/count r)
                    :intent/before (game/item-count world (recipe/item-id (:recipe/result r))))
        (intent/fail world :no-ingredient)))))

(defmethod stage :click [world {:intent/keys [clicks]} _]
  (if (seq clicks)
    (-> world (click (first clicks)) (set-intent :intent/clicks (vec (rest clicks))))
    (set-intent world :intent/stage :verify :intent/since (now world))))

(defmethod stage :verify [world {:intent/keys [result makes since]} _]
  (let [out (get-in world [:window/grid 0])]
    (cond
      (= out {:item result :count makes})
      (-> world (click {:click/slot 0 :click/button 0 :click/mode 1})
          (set-intent :intent/stage :take :intent/since (now world)))
      (some? out) (intent/fail world :wrong-result)
      (> (- (now world) since) stage-timeout) (intent/fail world :no-result)
      :else world)))

(defmethod stage :take [world {:intent/keys [result makes before since]} _]
  (cond
    (>= (game/item-count world result) (+ before makes)) (intent/done world)
    (> (- (now world) since) stage-timeout) (intent/fail world :not-taken)
    :else world))

(defmethod intent/run :craft [world i event]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :settle :intent/since (now world)))
        world (assoc world :plan/intent i :player/controls {})]
    (case (waiting? world i)
      :overdue (intent/fail world :stale-window)
      true world
      (stage world i event))))
