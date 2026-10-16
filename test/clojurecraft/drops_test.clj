(ns clojurecraft.drops-test
  "Picking up what lies on the ground (issue #262): the :collect-drops intent tick by tick over
   canned worlds, and a drop told apart by the stack its metadata names."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.drops :as drops]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def rod (recipe/item-id :blaze_rod))

(defn drop-of
  "A tracked dropped item at pos, holding the item named k when given (unsaid otherwise)."
  ([pos] {:entity/type blocks/item-entity-type :entity/pos pos :entity/seen-at 0})
  ([pos k] (assoc (drop-of pos) :entity/item (recipe/item-id k))))

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column with the given entities and a
   :collect-drops of blaze rods round [12 64 8] running."
  [entities]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :world/chunks {[0 0] (world/column {})}
             :player/inventory {}
             :world/entities entities
             :plan/intent
             {:intent/kind :collect-drops
              :intent/item :blaze_rod
              :intent/target [12 64 8]
              :intent/status :active})))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(deftest a-drop-is-walked-to-until-one-more-is-held
  (let [[w _] (fx/fold intent-step
                       (standing {40 (drop-of [12.5 64.0 8.5] :blaze_rod)})
                       (map tick (range 50 1500 50)))]
    (is (= :active (:intent/status (:plan/intent w))) "still walking: nothing handed over yet")
    (is (< (physics/horizontal-distance (:player/pos w) [12.5 64.0 8.5]) 1.5) "beside the rod")
    (let [[w _] (fx/fold
                 intent-step
                 w
                 [(packet {:packet/name :take-item-entity :collected 40 :collector 1 :count 1})
                  (packet {:packet/name :remove-entities :ids [40]})
                  (packet {:packet/name :set-player-inventory :slot 3 :item {:item rod :count 1}})
                  (tick 1550)])]
      (is (= :done (:intent/status (:plan/intent w))))
      (is (= {} (:player/controls w))))))

(deftest nothing-of-it-lying-there-is-done-not-failed
  (testing "no drop at all"
    (let [[w _] (fx/fold intent-step (standing {}) [(tick 50)])]
      (is (= :done (:intent/status (:plan/intent w))))))
  (testing "a drop that holds cobblestone is not a rod, nor one beyond drop-radius"
    (let [[w _] (fx/fold intent-step
                         (standing {41 (drop-of [12.5 64.0 9.5] :cobblestone)
                                    42 (drop-of [12.5 64.0 (+ 9.5 drops/drop-radius)])})
                         [(tick 50)])]
      (is (= :done (:intent/status (:plan/intent w)))))))

(deftest a-drop-that-has-not-said-what-it-holds-may-be-it
  (is (drops/holds? (drop-of [0.0 0.0 0.0]) rod))
  (is (drops/holds? (drop-of [0.0 0.0 0.0] :blaze_rod) rod))
  (is (not (drops/holds? (drop-of [0.0 0.0 0.0] :cobblestone) rod)))
  (is (not (drops/holds? {:entity/type (blocks/entity-types :blaze) :entity/pos [0.0 0.0 0.0]} rod))
      "a blaze is no drop"))

(deftest a-drop-never-handed-over-fails-not-picked-up
  (let [[w _] (fx/fold intent-step
                       (standing {40 (drop-of [12.5 64.0 8.5])})
                       (map tick (range 50 (+ 200 intent/collect-timeout) 50)))]
    (is (= [:failed :not-picked-up] ((juxt :intent/status :intent/reason) (:plan/intent w))))))
