(ns clojurecraft.blaze-test
  "Blaze rods (issue #262): the :blaze-rods row's rules over canned worlds, the :camp intent,
   and the rods against the server model's blazes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
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

(def boxed-in
  "Stone round the feet cell [8 64 8], two high, and a roof over it: no route leaves it."
  (merge (into {}
               (for [x (range 7 10)
                     z (range 7 10)
                     y [64 65]
                     :when (not= [x z] [8 8])]
                 [[x y z] (blocks/default-state :stone)]))
         {[8 66 8] (blocks/default-state :stone)}))

(deftest only-a-blaze-the-bot-can-reach-is-fought
  ;; gym blaze-fortress-a (run 38892386220): three fights in a row failed :unreachable on blazes
  ;; seen through the fortress's walls from the tunnel, every run
  (let [near (entity :blaze [12.5 65.0 8.5] :entity/health 20.0)]
    (is (= {:plan/wait :no-blaze}
           (next-intent (assoc (standing {7 near}) :world/chunks {[0 0] (world/column boxed-in)})))
        "behind a wall: no route, no fight")
    (is (= {:plan/wait :no-blaze}
           (next-intent (standing {7 (entity :blaze [10.5 72.0 8.5] :entity/health 20.0)})))
        "eight over the feet: out of reach from the floor")
    (testing "a camp that began with it there goes on"
      (let [w (assoc (standing {7 near})
                     :world/chunks {[0 0] (world/column boxed-in)}
                     :time/now 1000
                     :plan/intent
                     {:intent/kind :camp :intent/spawner [11 64 10] :intent/status :active})
            w (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000})]
        (is (= #{7} (:intent/blazes (:plan/intent w))))
        (is (= :active (:intent/status (:plan/intent w))))))))

(deftest a-spawner-under-the-feet-is-tunnelled-to-its-floor
  ;; gym blaze-fortress-b (run 38893941350): a tunnel ended within 6 of the spawner, four over it
  ;; in the wall, and the bot camped there out of reach of its blazes for ten minutes;
  ;; blaze-fortress-c (run 38895759229) run 3 came up three under the spawner's floor and every
  ;; rod lay out of reach over it
  (let [w (memory/observe (standing {}) [8 60 11] blaze/spawner)]
    (is (= {:intent/kind :tunnel-to
            :intent/target [8 60 11]
            :intent/steps fortress/steps-per-tunnel
            :intent/reach blaze/spawner-reach
            :intent/climb 0}
           (next-intent w))
        "within 8 of the eye, four under the feet, nothing open beside it: a tunnel down to it")
    (let [room (assoc w
                      :world/chunks
                      {[0 0] (world/column {[9 60 11] blocks/air [9 61 11] blocks/air})})]
      (is (= [9 60 11] (blaze/stand room [8 60 11])))
      (is (= {:intent/kind :tunnel-to
              :intent/target [9 60 11]
              :intent/steps fortress/steps-per-tunnel
              :intent/reach 0.5
              :intent/climb 0}
             (next-intent room))
          "with room beside it on its floor, into that cell")
      (is (= {:intent/kind :camp :intent/spawner [8 60 11]}
             (next-intent (assoc room :player/pos [9.5 60.0 11.5])))
          "standing there, a camp"))))

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

(def closet
  "A room five by three round the feet, walled and roofed in stone: nowhere ten blocks off to hide."
  (into {}
        (for [x (range 6 13)
              z (range 6 11)
              y [64 65 66]
              :when (or (= y 66) (not (and (<= 7 x 11) (<= 7 z 9))))]
          [[x y z] (blocks/default-state :stone)])))

(deftest hurt-the-bot-hides-backs-away-eats-or-waits
  (let [near (entity :blaze [10.5 65.0 8.5] :entity/health 20.0)
        hurt (fn [w] (assoc w :player/health 8.0))]
    (testing
      "seen by a blaze, into the cell behind a pillar, out of its sight (gym blaze-fortress-e, f)"
      (let [pillar {[6 64 8] (blocks/default-state :stone) [6 65 8] (blocks/default-state :stone)}]
        (is (= {:intent/kind :walk :intent/target [5 64 8] :intent/for :cover}
               (next-intent
                (hurt (assoc (standing {7 near}) :world/chunks {[0 0] (world/column pillar)})))))
        (is (= {:intent/kind :back-off :intent/target 7} (next-intent (hurt (standing {7 near}))))
            "in the open nothing hides: backed away from")))
    (is (= {:intent/kind :back-off :intent/target 7}
           (next-intent (hurt
                         (assoc (standing {7 near}) :world/chunks {[0 0] (world/column closet)}))))
        "nowhere to hide: backed away from")
    (is (= {:plan/wait :healing}
           (next-intent (assoc (standing {1 (item :cooked_beef 4)} {}) :player/health 12.0)))
        "out of sight under 14: rest")
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (next-intent
            (assoc (hurt (standing {1 (item :cooked_beef 4)} {7 near})) :player/food 15)))
        "the food under the healing level: a meal, even with a blaze at hand (gym blaze-7 run 1)")
    (is (= {:plan/wait :healing} (next-intent (hurt (standing {1 (item :cooked_beef 4)} {}))))
        "fed: the health comes back by itself")
    (testing "under 14 a meal comes before the next fight when the food is low (gym blaze-7b run 2)"
      (is (= {:intent/kind :eat :intent/item :cooked_beef}
             (next-intent (assoc (standing {1 (item :cooked_beef 4)} {7 near})
                                 :player/health 12.0
                                 :player/food 17))))
      (is (= {:intent/kind :fight :intent/target 7}
             (next-intent
              (assoc (standing {1 (item :cooked_beef 4)} {7 near}) :player/health 12.0)))
          "fed, it fights on"))
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
