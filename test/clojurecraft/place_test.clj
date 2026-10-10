(ns clojurecraft.place-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn standing [pos]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
      (assoc :world/chunks {[0 0] (world/column {})})))

(defn overlaps? [[px py pz] [x y z]]
  (and (< (- px 0.3) (inc x)) (> (+ px 0.3) x) (< py (inc y)) (> (+ py 1.8) y) (< (- pz 0.3) (inc z)) (> (+ pz 0.3) z)))

(deftest a-placement-spot-is-never-inside-the-player
  (let [r (tc/quick-check
           300
           (prop/for-all [x (gen/double* {:min 2.0 :max 13.9 :NaN? false :infinite? false})
                          z (gen/double* {:min 2.0 :max 13.9 :NaN? false :infinite? false})]
                         (let [w (standing [x 64.0 z])
                               [target support] (place/spot w)]
                           (and target
                                (not (overlaps? (:player/pos w) target))
                                (= support (update target 1 dec))
                                (<= (abs (- (second target) 64)) 1)
                                (<= (physics/distance (game/eye w) (intent/centre target)) place/place-reach)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(defn intent-step
  "The world reducer plus one run of the current intent per tick (no planner)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (:plan/intent w))
      (intent/run w (:plan/intent w) e)
      w)))

(deftest a-spot-is-found-on-an-uneven-floor
  (testing "every feet-level cell around is walled: place on top of the nearest wall, one up"
    (let [walls (into {} (for [x (range 3 10) z (range 3 10) :when (not= [x z] [6 6])] [[x 64 z] 1]))
          w (assoc (standing [6.5 64.0 6.5]) :world/chunks {[0 0] (world/column walls)})]
      (is (= [[5 65 5] [5 64 5]] (place/spot w))))))

(deftest a-rejected-placement-fails-instead-of-believing
  (let [w (-> (standing [5.5 64.0 5.5])
              (assoc :plan/status :active :plan/goals #{:pickaxe} :bot/sequence 7
                     :plan/intent {:intent/kind :place :intent/item :crafting_table :intent/status :active
                                   :intent/stage :sent :intent/target [7 64 5] :intent/sequence 7 :intent/sent-at 0}))
        [w _] (fx/fold intent-step w [(packet {:packet/name :block-update :pos [7 64 5] :state 0})
                                      (packet {:packet/name :block-changed-ack :sequence 7})
                                      {:event/kind :tick :event/now 600 :event/rand 0.5}])]
    (is (intent/failed? (:plan/intent w)))
    (is (= :rejected (:intent/reason (:plan/intent w))))
    (testing "a placement the server confirms is done"
      (let [w (-> (standing [5.5 64.0 5.5])
                  (assoc :plan/intent {:intent/kind :place :intent/item :crafting_table :intent/status :active
                                       :intent/stage :sent :intent/target [7 64 5] :intent/sequence 7 :intent/sent-at 0}))
            [w _] (fx/fold intent-step w [(packet {:packet/name :block-update :pos [7 64 5] :state memory/crafting-table})
                                          {:event/kind :tick :event/now 100 :event/rand 0.5}])]
        (is (intent/done? (:plan/intent w)))
        (is (= memory/crafting-table (memory/remembered w [7 64 5])) "remembered")))))
