(ns clojurecraft.craft
  "The :craft intent: one craft of a recipe, in the player's own 2x2 grid or in an open
   crafting table.

     {:intent/kind :craft :intent/recipe :stick :intent/window :inventory}   ; or :table

   Stages, each advanced one tick at a time against the world value:
     :settle  put a loaded cursor down, shift-click every item left in the grid back into the
              inventory, then plan the clicks from the inventory (window 0 also closes any
              open container first; :table requires the table window to be open)
     :click   one click per round trip: send it, then wait until the window's state id moves
     :verify  only when the server says slot 0 holds exactly the expected result, shift-click it
     :take    done when the inventory shows the result; a table window is then closed

   Rules that come from the siblings' failures: nothing is predicted; a click is never sent
   while the previous one is unanswered; slot 0 is never clicked unless it holds what the recipe
   makes (a stray plank turns a take into a button); every deadline is an absolute time."
  (:require [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(def stage-timeout 5000)              ; ms a craft stage waits on the server before it fails

(defn- now [world] (:time/now world))
(defn- set-intent [world & kvs] (apply update world :plan/intent assoc kvs))

(defn dirty-grid-slot
  "The first crafting-grid slot (1..size²) of the view that still holds an item, or nil when
   the grid is clean. A craft lays its ingredients only into a clean grid."
  [{:view/keys [size grid]}]
  (first (filter #(get grid %) (range 1 (inc (* size size))))))

(defmulti stage
  "Advance a :craft intent one tick in its current :intent/stage (:settle :click :verify :take)
   against view v. Called only when no click is unanswered."
  (fn [_world i _view] (:intent/stage i)))

(defmethod stage :settle [world {:intent/keys [recipe since window]} v]
  (let [r (recipe/by-id recipe)]
    (cond
      (nil? r) (intent/fail world :unknown-recipe)
      (and (= window :inventory) (:window/open world))
      (-> world
          (game/emit {:packet/name :container-close :window-id (get-in world [:window/open :window/id])})
          (dissoc :window/open))
      (nil? (:view/state-id v))
      (if (> (- (now world) since) stage-timeout) (intent/fail world :no-window) world)
      (:window/cursor world)
      (if-let [s (window/free-slot world v)]
        (window/click world v {:click/slot s :click/button 0 :click/mode 0})
        (intent/fail world :inventory-full))
      (dirty-grid-slot v)
      (window/click world v {:click/slot (dirty-grid-slot v) :click/button 0 :click/mode 1})
      :else
      (if-let [cs (recipe/clicks (:player/inventory world) r (:view/size v) (:view/slot-of v))]
        (set-intent world :intent/stage :click :intent/clicks cs :intent/since (now world)
                    :intent/result (recipe/item-id (:recipe/result r)) :intent/makes (:recipe/count r)
                    :intent/before (game/item-count world (recipe/item-id (:recipe/result r))))
        (intent/fail world :no-ingredient)))))

(defmethod stage :click [world {:intent/keys [clicks]} v]
  (if (seq clicks)
    (-> world (window/click v (first clicks)) (set-intent :intent/clicks (vec (rest clicks))))
    (set-intent world :intent/stage :verify :intent/since (now world))))

(defmethod stage :verify [world {:intent/keys [result makes since]} v]
  (let [out (get-in v [:view/grid 0])]
    (cond
      (= out {:item result :count makes})
      (-> world (window/click v {:click/slot 0 :click/button 0 :click/mode 1})
          (set-intent :intent/stage :take :intent/since (now world)))
      (some? out) (intent/fail world :wrong-result)
      (> (- (now world) since) stage-timeout) (intent/fail world :no-result)
      :else world)))

(defmethod stage :take [world {:intent/keys [result makes before since window]} v]
  (cond
    (>= (game/item-count world result) (+ before makes))
    (cond-> (intent/done world)
      (= window :table) (-> (game/emit {:packet/name :container-close :window-id (:view/id v)})
                            (dissoc :window/open)))
    (> (- (now world) since) stage-timeout) (intent/fail world :not-taken)
    :else world))

(defmethod intent/run :craft [world i _event]
  (let [i (cond-> i
            (nil? (:intent/window i)) (assoc :intent/window :inventory)
            (nil? (:intent/stage i)) (assoc :intent/stage :settle :intent/since (now world)))
        world (assoc world :plan/intent i :player/controls {})
        v (window/view world (:intent/window i))]
    (cond
      (nil? v) (intent/fail world :no-table-window)
      :else (case (window/waiting world i)
              :overdue (intent/fail world :stale-window)
              :waiting world
              (stage world i v)))))
