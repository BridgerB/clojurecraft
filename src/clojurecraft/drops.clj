(ns clojurecraft.drops
  "Picking up what lies on the ground (issue #262): the :collect-drops executor, :collect made
   general. :collect walks after a dug block's drop until one more log (or one of :intent/drops)
   is held, and fails :not-picked-up when none comes; a kill's loot is not a block's drop: a blaze
   drops a rod one time in two, so a collect that waited for one would fail every other kill.

   {:intent/kind :collect-drops :intent/item name :intent/target cell} walks onto the nearest
   dropped item within drop-radius of the cell that holds the item, and is done when one more of
   it is held than when it began, or when no such drop lies there (nothing to pick up is not a
   failure). A drop says what it holds in its metadata (entity/metadata-facts, :entity/item); one
   that has not said yet may hold it. No packet: the server hands a drop over on contact."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def drop-radius 8.0)                 ; blocks from the intent's cell a drop of its item is looked for within
(def underfoot 0.4)                   ; blocks on the plane from a drop where the walk stops pressing on

(defn holds?
  "May item entity e hold the item id: alive, and it says so (:entity/item), or it has not said
   (its metadata not come yet, or a server model that never sends it)."
  [e id]
  (and (entity/item? e) (not (entity/dead? e)) (contains? #{nil id} (:entity/item e))))

(defn lying
  "{eid entity} of the dropped items that may hold the item id (holds?) within radius of the
   point at, none of them blacklisted."
  [world id at radius]
  (let [bl (:plan/blacklist world #{})]
    (into {}
          (filter (fn [[eid e]]
                    (and (holds? e id)
                         (not (contains? bl eid))
                         (<= (physics/distance at (:entity/pos e)) radius))))
          (:world/entities world))))

(defn nearest
  "[eid entity] of the drop of the item named item nearest the feet within drop-radius of the
   point at, or nil when none lies there."
  [world item at]
  (entity/nearest (lying world (recipe/item-id item) at drop-radius) (:player/pos world)))

(defn- stop [world] (assoc world :player/controls {}))

(defmethod intent/run :collect-drops
  ;; done when one more of the item is held than at the start, or no drop of it lies within
  ;; drop-radius of the target cell; :not-picked-up past intent/collect-timeout; a step over a
  ;; drop with no landing (intent/ledge-ahead?) leaves the drop, as :collect does
  [world {:intent/keys [item target since before]} _]
  (let [now (:time/now world)
        held (inventory/item-count world (recipe/item-id item))
        before (or before held)
        since (or since now)
        world (intent/assoc-intent world :intent/since since :intent/before before)
        [_ e] (nearest world item (physics/centre target))
        p (:entity/pos e)
        d (when p (physics/horizontal-distance (:player/pos world) p))]
    (cond (or (> held before) (nil? e)) (intent/done (stop world))
          (> (- now since) intent/collect-timeout) (intent/fail (stop world) :not-picked-up)
          (<= d underfoot) (stop world)
          (intent/ledge-ahead? world p) (intent/done (stop world))
          :else (assoc world :player/controls (intent/toward world p 0.0)))))
