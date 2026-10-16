(ns clojurecraft.blaze-test
  "Blaze rods (issue #262): the :blaze-rods row's rules over canned worlds, the :camp intent,
   and the rods against the server model's blazes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn entity
  "A tracked entity of type k (a name) at pos, with more attributes."
  [k pos & {:as more}]
  (merge {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0} more))

(defn standing
  "A bot in play in the Nether at [8.5 64 8.5] on the stone floor of one column, an iron sword in
   hotbar slot 0 and inv besides, with the given entities."
  ([entities] (standing {} entities))
  ([inv entities]
   (-> (game/init fx/opts)
       (assoc :bot/phase :play
              :player/dimension dimension/nether
              :player/pos [8.5 64.0 8.5]
              :player/loaded? true
              :player/on-ground? true
              :player/health 20.0
              :player/food 20
              :world/chunks {[0 0] (world/column {})}
              :window/state-id 5
              :player/inventory (merge {0 (item :iron_sword 1)} inv)
              :world/entities entities))))

(def row (first (filter #(= :blaze-rods (:goal/id %)) plan/goals)))

(defn next-intent [w] (plan/next-intent w row plan/goals))

(def spawner-at [15 64 15])

(defn with-spawner
  "w remembering a spawner at spawner-at, seen in its dimension."
  [w]
  (memory/observe w spawner-at blaze/spawner))

(deftest seven-rods-held-is-done
  (is (not (plan/done-by (standing {1 (item :blaze_rod 6)} {}) row)))
  (is (plan/done-by (standing {1 (item :blaze_rod 6) 2 (item :blaze_rod 1)} {}) row)
      "counted across slots")
  (is (= dimension/nether (:goal/dimension row)) "worked in the Nether only"))

(deftest a-rod-lying-near-then-the-nearest-blaze-then-the-spawner
  (let [rod (entity :item [10.5 64.0 9.5] :entity/item blaze/rod)
        near (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)
        far (entity :blaze [8.5 65.0 23.5] :entity/health 20.0)]
    (is (= {:intent/kind :collect-drops :intent/item :blaze_rod :intent/target [10 64 9]}
           (next-intent (standing {40 rod 7 near})))
        "the rod first: it lies there now, the blaze stays")
    (is (= {:intent/kind :fight :intent/target 7} (next-intent (standing {7 near 8 far})))
        "the nearest blaze within 12")
    (is (= {:intent/kind :fight :intent/target 7}
           (next-intent (standing {40 (entity :item [10.5 64.0 9.5] :entity/item 1) 7 near})))
        "a drop that says it holds something else is left")
    (is (= {:intent/kind :walk :intent/target spawner-at}
           (next-intent (with-spawner (standing {8 far}))))
        "none within 12: to the spawner, by a route")
    (is (= {:plan/wait :no-blaze} (next-intent (standing {8 far}))) "no spawner known: a wait")
    (testing "a ghost is left alone, and a spawner no walk nor tunnel reached"
      (is (= {:plan/wait :no-blaze}
             (next-intent
              (assoc (with-spawner (standing {7 near})) :plan/blacklist #{7 spawner-at})))))))

(deftest by-the-spawner-the-bot-camps
  (let [w (memory/observe (standing {}) [11 64 10] blaze/spawner)]
    (is (= {:intent/kind :camp :intent/spawner [11 64 10]} (next-intent w)))
    (let [w (assoc w
                   :plan/intent
                   {:intent/kind :camp :intent/spawner [11 64 10] :intent/status :active})
          camp
          (fn [w t]
            (intent/run (assoc w :time/now t) (:plan/intent w) {:event/kind :tick :event/now t}))
          w1 (camp w 1000)]
      (is (= :active (:intent/status (:plan/intent w1))) "nothing yet: it stands")
      (is (= :done
             (:intent/status (:plan/intent
                              (camp (assoc w1 :world/entities {7 (entity :blaze [12.5 65.0 8.5])})
                                    2000))))
          "a blaze came")
      (is (= [:failed :no-blaze]
             ((juxt :intent/status :intent/reason) (:plan/intent (camp w1 (+ 1001 blaze/camp-ms)))))
          "none in a minute"))))

(deftest hurt-the-bot-backs-away-eats-or-waits
  (let [near (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        hurt (fn [w] (assoc w :player/health 8.0))]
    (is (= {:intent/kind :back-off :intent/target 7} (next-intent (hurt (standing {7 near})))))
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent (assoc (hurt (standing {1 (item :cooked_beef 4)} {})) :player/food 15)))
        "the food under the healing level: a meal")
    (is (= {:plan/wait :healing} (next-intent (hurt (standing {1 (item :cooked_beef 4)} {}))))
        "fed: the health comes back by itself")
    (is (= {:plan/wait :other-dimension}
           (next-intent (assoc (standing {}) :player/dimension dimension/overworld))))))

;; ---------------------------------------------------------------- against the server model

(def blazes
  "Five of the model's blazes, hovering a block over the Nether floor round the landing, all
   within the row's 12: they do not move and shoot when they see the bot (mob/kinds)."
  (mapv (fn [p] {:type :blaze :pos p})
        [[11.5 65.0 8.5] [5.5 65.0 11.5] [12.5 65.0 13.5] [3.5 65.0 4.5] [13.5 65.0 3.5]]))

(deftest the-seventh-rod-from-the-models-blazes
  ;; six rods given, one more wanted, as gym row blaze-1 starts: the fight, the pickup of a drop
  ;; that comes one kill in two (rolled from the model's seed), the bot hurt by their fireballs at
  ;; Easy and healing from a full bar
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {} dimension/nether)
                                    :spawn [8.5 64.0 8.5]
                                    :dimension dimension/nether
                                    :difficulty :easy
                                    :food 20
                                    :seed 3
                                    :inventory {[:hotbar 0] (item :iron_sword 1)
                                                [:hotbar 1] (item :blaze_rod 6)}
                                    :mobs blazes})
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:blaze-rods]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (>= (inventory/item-count w blaze/rod) blaze/rods-wanted))
    (is (pos? (:sim/health sim)) "alive")
    (is (= [] (:sim/violations sim)))))
