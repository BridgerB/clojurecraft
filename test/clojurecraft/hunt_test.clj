(ns clojurecraft.hunt-test
  "The food producer (issue #259): hungry with nothing to eat, the nearest animal is walked to,
   killed and its meat picked up, then eaten; over canned worlds and against the server model."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn animal
  "A tracked living entity of type k (a name) at pos."
  [k pos]
  {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0 :entity/health 10.0})

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column, its food told as food, with
   inv and the given entities."
  [food inv entities]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :player/health 20.0
             :player/food food
             :world/chunks {[0 0] (world/column {})}
             :player/inventory inv
             :world/entities entities)))

(def hunt-row (first (filter #(= :hunt (:goal/id %)) plan/goals)))

(deftest hungry-with-nothing-to-eat-the-nearest-cow-is-walked-to-then-fought
  (let [far (standing 8 {} {5 (animal :cow [14.5 64.0 8.5])})
        near (standing 8 {} {5 (animal :cow [11.5 64.0 8.5])})]
    (is (= :hunt (:goal/id (plan/choose (assoc far :plan/goals #{:wood}) plan/goals))))
    (is (= {:intent/kind :walk :intent/target [14 64 8] :intent/for :hunt}
           (plan/next-intent far hunt-row plan/goals)))
    (is (= {:intent/kind :fight :intent/target 5 :intent/for :hunt}
           (plan/next-intent near hunt-row plan/goals))
        "beside it (within a walk's reach) it is fought")))

(deftest fed-holding-food-or-with-no-game-the-hunt-is-done
  (let [cow {5 (animal :cow [11.5 64.0 8.5])}]
    (is (plan/done-by (standing 16 {} cow) hunt-row) "not hungry")
    (is (plan/done-by (standing 8 {0 (item :bread 1)} cow) hunt-row)
        "food held: the meal comes first")
    (is (plan/done-by (standing 8 {} {}) hunt-row) "nothing to hunt")
    (is (plan/done-by (standing 8 {} {6 (animal :chicken [11.5 64.0 8.5])}) hunt-row)
        "a chicken is not hunted")
    (is (plan/done-by (standing 8 {} {7 (animal :zombie [11.5 64.0 8.5])}) hunt-row))))

(deftest a-hunt-running-is-never-done-under-it
  ;; the cow dies, game has none, but the fight is not over until it sees the death: the row
  ;; stays undone so the planner does not abandon it (feat/259-hunt 8ed7f50)
  (let [w (assoc (standing 8 {} {})
                 :plan/intent
                 {:intent/kind :fight :intent/target 5 :intent/for :hunt :intent/status :active})]
    (is (not (plan/done-by w hunt-row)))
    (is (plan/done-by (assoc-in w [:plan/intent :intent/for] :defend) hunt-row)
        "another row's fight does not hold it")))

(deftest a-kill-just-made-is-collected-where-it-fell
  (let [w (assoc (standing 8 {} {})
                 :plan/last
                 {:intent/kind :fight
                  :intent/for :hunt
                  :intent/target 5
                  :intent/target-pos [11.7 64.0 8.2]
                  :intent/status :done})]
    (is (not (plan/done-by w hunt-row)) "no animal left, but the drop is still to pick up")
    (is (=
         {:intent/kind :collect :intent/target [11 64 8] :intent/drops hunt/meats :intent/for :hunt}
         (plan/next-intent w hunt-row plan/goals)))))

(deftest a-hungry-bot-kills-a-cow-picks-up-its-beef-and-eats-it-against-the-model
  ;; food 10: the model's cow (mob.clj, issue #258) drops one to three beef, three each; the bot
  ;; eats until it is fed or has none left
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 4.5]
                                    :food 10
                                    :mobs [{:type :cow :pos [8.5 64.0 11.5]}]})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:eat]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (not-any? (fn [[_ m]] (and (= :cow (:type m)) (not (:dead-at m)))) (:sim/mobs sim))
        "the cow was killed")
    (is (>= (:player/food w) 13)
        (pr-str {:story (mapv (juxt :intention/kind :intention/event
                                    :intention/reason :intention/target)
                              (memory/intentions w))
                 :held (recipe/counts (:player/inventory w))}))
    (is (= (:sim/food sim) (:player/food w)) "the bot's food is the server's")
    (is (= [] (:sim/violations sim)))))

(deftest a-starving-bot-hunts-further-afield
  ;; issues #69, #62, #135: food 0, one health, and the nearest game thirty blocks off was never
  ;; looked for beyond hunt-radius
  (let [col (world/column {})
        far {5 (animal :cow [38.5 64.0 8.5])}
        wide (fn [food]
               (assoc (standing food {} far)
                      :world/chunks
                      (zipmap [[0 0] [1 0] [2 0] [3 0]] (repeat col))))]
    (is (= hunt/hunt-radius (hunt/reach (wide 8))))
    (is (= hunt/starving-radius (hunt/reach (wide hunt/starving-below))))
    (is (plan/done-by (wide 8) hunt-row) "hungry only: a cow thirty blocks off is out of reach")
    (is (not (plan/done-by (wide 4) hunt-row)) "starving: it is hunted")
    (is (= {:intent/kind :walk :intent/target [38 64 8] :intent/for :hunt}
           (plan/next-intent (wide 4) hunt-row plan/goals)))))
