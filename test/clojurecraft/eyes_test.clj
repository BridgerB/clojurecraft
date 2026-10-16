(ns clojurecraft.eyes-test
  "Eyes of ender (issue #264): the needs planner through the craft rows, and a wait that names
   the stage that is short."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.eyes :as eyes]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(def goal (first (filter #(= :eyes-of-ender (:goal/id %)) plan/goals)))

(defn holding
  "On bare ground, holding items ({item-name count}, one slot each)."
  [items]
  (-> (game/init fx/opts)
      (assoc
       :bot/phase :play
       :player/pos [0.5 64.0 0.5]
       :player/loaded? true
       :player/on-ground? true
       :player/inventory
       (into {} (map-indexed (fn [i [item n]] [i {:item (recipe/item-id item) :count n}])) items)
       :world/chunks {[0 0] (world/column {})})))

(deftest the-row-is-an-aside-after-the-pearls
  (is (= 20 (:goal/priority goal)))
  (is (plan/aside? goal))
  (is (= [:eyes-of-ender] (plan/goals-for "eyes")))
  (is (= "fortress" (plan/current-until)) "the chain's stage is still the fortress")
  (is (= 2 eyes/powder-per-rod) "from the game's recipe"))

(deftest what-is-short-is-named
  (testing "the pearls first, then the rods (two powder a rod), net of what is made already"
    (is (= :no-pearls (eyes/lacking {} 12)))
    (is (= :no-pearls (eyes/lacking {:blaze_rod 7 :ender_pearl 11} 12)))
    (is (= :no-rods (eyes/lacking {:blaze_rod 5 :ender_pearl 12} 12)))
    (is (nil? (eyes/lacking {:blaze_rod 6 :ender_pearl 12} 12)))
    (is (nil? (eyes/lacking {:blaze_rod 1 :blaze_powder 2 :ender_pearl 4 :ender_eye 8} 12)))
    (is (nil? (eyes/lacking {:ender_eye 12} 12)))))

(deftest the-planner-crafts-or-waits-for-its-stage
  (testing "nothing held: the pearls' stage is the one to look at"
    (is (= {:plan/wait :no-pearls} (plan/next-intent (holding {}) goal plan/goals))))
  (testing "pearls but no rods"
    (is (= {:plan/wait :no-rods} (plan/next-intent (holding {:ender_pearl 12}) goal plan/goals))))
  (testing "rods and pearls: blaze powder first, in the 2x2 grid"
    (is (= {:intent/kind :craft :intent/recipe :blaze_powder :intent/window :inventory}
           (plan/next-intent (holding {:blaze_rod 7 :ender_pearl 12}) goal plan/goals))))
  (testing "a powder held: the eye"
    (is (= {:intent/kind :craft :intent/recipe :ender_eye :intent/window :inventory}
           (plan/next-intent (holding {:blaze_rod 6 :blaze_powder 2 :ender_pearl 12})
                             goal
                             plan/goals)))))
