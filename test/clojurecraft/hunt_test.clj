(ns clojurecraft.hunt-test
  "The food producer (issue #259): hungry with nothing to eat, the nearest animal is walked to,
   killed and its meat picked up, then eaten; over canned worlds and against the server model."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.terrain :as terrain]
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

(deftest with-the-portal-iron-in-hand-meals-are-hunted-for-the-nether
  ;; sim race 39712220957 (issue #501): a bot at food 16 reached a fortress with nothing to eat,
  ;; hurt, and no food under 18 heals: a rest failed :no-heal
  (let [cow {5 (animal :cow [11.5 64.0 8.5])}
        kit {0 (item :flint_and_steel 1) 1 (item :water_bucket 1)}]
    (is (not (plan/done-by (standing 20 kit cow) hunt-row))
        "fed, kit held, no food: a cow is hunted")
    (is (= {:intent/kind :fight :intent/target 5 :intent/for :hunt}
           (plan/next-intent (standing 20 kit cow) hunt-row plan/goals)))
    (is (plan/done-by (standing 20 (assoc kit 2 (item :bread hunt/provision-wanted)) cow) hunt-row)
        "provision-wanted foods held")
    (is (not (plan/done-by (standing 20 (assoc kit 2 (item :bread (dec hunt/provision-wanted))) cow)
                           hunt-row))
        "one fewer is under it")
    (is (plan/done-by (standing 20 {} cow) hunt-row) "no kit yet: no trip for food")
    (is (plan/done-by (standing 20 {0 (item :flint_and_steel 1)} cow) hunt-row)
        "the water bucket is the kit's too")
    (is (plan/done-by (assoc (standing 20 kit {})
                             :hunt/forage
                             {:forage/since 0 :forage/leg 4 :forage/spent true})
                      hunt-row)
        "no animal and the search spent: the portal is not held up")
    (is (plan/done-by (assoc (standing 20 kit cow) :player/dimension "minecraft:the_nether")
                      hunt-row)
        "the Nether has no game: only the overworld provisions")))

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

(deftest an-animal-up-a-tree-is-not-game-from-beneath-it
  ;; sim race 39768347249 (issue #515, R2/30): sheep on a spruce crown five over the walk's end
  ;; were fought three times from beneath and the third :unreachable ended the plan. The route is
  ;; given (a flat floor has none to a cell in the air): found, ending at [11 64 8].
  (let [on (fn [y] (standing 20 {} {5 (animal :sheep [11.5 y 8.5])}))
        fightable? (fn [y]
                     (let [w (on y)]
                       (with-redefs [path/plan (constantly {:path/status :found
                                                            :path/waypoints [[10 64 8] [11 64 8]]})]
                         (hunt/fightable-from-route? w (get-in w [:world/entities 5])))))]
    (is (fightable? 65.0) "a step up is within a blow and the meat's pickup")
    (is (not (fightable? 69.5)) "five over is out of reach from where the walk ends")
    (is (with-redefs [path/plan (constantly {:path/status :none :path/waypoints []})]
          (let [w (on 69.5)] (hunt/fightable-from-route? w (get-in w [:world/entities 5]))))
        "no route found yet is not held against it")))

(deftest a-fight-that-left-the-animal-alive-is-no-kill
  ;; issue #178 (R2/37): a starving bot at 0.7 health broke the fight off, the animal still
  ;; standing, and collected the meat of a kill that never was, again and again
  (let [w (assoc (standing 0 {} {5 (animal :cow [11.7 64.0 8.2])})
                 :plan/last
                 {:intent/kind :fight
                  :intent/for :hunt
                  :intent/target 5
                  :intent/target-pos [11.7 64.0 8.2]
                  :intent/status :done})]
    (is (nil? (hunt/fresh-kill w)))
    (is (= :fight (:intent/kind (plan/next-intent w hunt-row plan/goals))))))

(def kit-held {0 (item :flint_and_steel 1) 1 (item :water_bucket 1)})

(defn provisioning
  "A bot in play at ms, the portal kit held and no food, no animal in sight, on the open surface."
  [now]
  (assoc (standing 20 kit-held {}) :time/now now :time/tick 0))

(defn route-to
  "A path/plan stand-in whose every route is found and ends at cell."
  [cell]
  (constantly {:path/status :found :path/waypoints [cell]}))

(deftest with-no-animal-in-reach-the-portal-bot-searches-for-one
  ;; issue #501: herds are about one chunk in ten, and the Nether has no game
  (let [w (provisioning 1000)]
    (is (hunt/foraging? w))
    (is (not (plan/done-by w hunt-row)) "the row is undone: a search is planned")
    (is (= {:intent/kind :forage :intent/for :hunt} (plan/next-intent w hunt-row plan/goals)))
    (is (= {:intent/kind :fight :intent/target 5 :intent/for :hunt}
           (plan/next-intent (assoc-in w [:world/entities 5] (animal :cow [11.5 64.0 8.5]))
                             hunt-row
                             plan/goals))
        "an animal in reach is hunted, not searched for")))

(deftest the-search-is-bounded-and-never-held-against-a-build-or-the-mine
  (let [w (provisioning 1000)
        began (assoc w :hunt/forage {:forage/since 1000 :forage/leg 2})]
    (is (hunt/foraging? (assoc began :time/now (+ 1000 (dec hunt/forage-ms)))))
    (is (not (hunt/foraging? (assoc began :time/now (+ 1000 hunt/forage-ms))))
        "forage-ms after its first leg it is over, however many legs")
    (is (plan/done-by (assoc began :time/now (+ 1000 hunt/forage-ms)) hunt-row)
        "and the row is done, the portal goes on with what is held")
    (is (not (hunt/foraging? (assoc-in w [:hunt/forage :forage/spent] true))) "no route: spent")
    (is (not (hunt/foraging? (assoc w :plan/attempts 1))) "a failed leg ends it for the moment")
    (is (not (hunt/foraging? (assoc w :plan/unwalkable 1))) "so does a leg that found no path")
    (is (not (hunt/foraging? (assoc w :player/health (dec hunt/forage-health)))) "hurt: it stays")
    (is (not (hunt/foraging? (assoc-in w [:world/entities 9] (animal :zombie [30.5 64.0 8.5]))))
        "a zombie within forage-wary: R1/5 searched at night through creepers and zombies and died")
    (is (hunt/foraging? (assoc-in w [:world/entities 9] (animal :zombie [60.5 64.0 8.5])))
        "one farther is no reason")
    (is (hunt/foraging? (assoc-in w [:world/entities 9] (animal :cow [11.5 64.0 40.5])))
        "an animal is no hazard")
    (is (not (hunt/foraging? (assoc w :plan/intent {:intent/kind :build :intent/status :active})))
        "a cast is never left half made")
    (is (not (hunt/foraging? (assoc (standing 20 {} {}) :time/now 1000))) "no kit, no search")
    (is (not (hunt/foraging?
              (assoc (standing 20 (assoc kit-held 2 (item :bread 6)) {}) :time/now 1000)))
        "six foods held: enough")
    (is (not (hunt/foraging? (assoc w :player/dimension "minecraft:the_nether")))
        "the Nether has no game")
    (with-redefs [terrain/under-sky? (constantly false)]
      (is (not (hunt/foraging? w)) "deep in the ground there are no animals to find"))))

(deftest a-forage-leg-turns-a-quarter-right-and-falls-back-to-the-next-way
  (let [w (provisioning 1000)
        asked (atom [])
        plan-at (fn [none?]
                  (fn [_ _ goal _]
                    (swap! asked conj [(:goal/pos goal) (:goal/range goal)])
                    (if (none? (:goal/pos goal))
                      {:path/status :none :path/waypoints []}
                      {:path/status :found :path/waypoints [[60 64 8]]})))
        leg-from (fn [leg] (hunt/forage-leg w {:forage/since 0 :forage/leg leg}))]
    (with-redefs [path/plan (plan-at (constantly false))]
      (is (= [[60 64 8] 1] (leg-from 0)))
      (is
       (= [[-40 64 8] 95] (first @asked))
       "leg 0 goes east: the goal's point is the reach behind the feet, the range twice it less one")
      (is (= [[60 64 8] 3] (leg-from 2)) "leg 2 goes west")
      (is (= [[60 64 8] 5] (leg-from 4)) "the way round starts again after four"))
    (with-redefs [path/plan (plan-at (fn [[x _ _]] (< x 8)))]
      (is
       (= [[60 64 8] 2] (leg-from 0))
       "no route east at 48: south, a quarter right, is taken, and the next leg turns on from it"))
    (with-redefs [path/plan (plan-at (fn [[x _ _]] (> x 8)))]
      (is (= [[60 64 8] 4] (leg-from 2)) "no route west at 48: north"))
    (with-redefs [path/plan (constantly {:path/status :none :path/waypoints []})]
      (is (nil? (leg-from 0)) "no way at all"))))

(deftest the-forage-intent-becomes-the-walk-it-chose-or-ends-the-search
  (let [w (assoc (provisioning 1000)
                 :plan/intent
                 {:intent/kind :forage :intent/for :hunt :intent/status :active})]
    (with-redefs [path/plan (route-to [60 64 8])]
      (let [out (intent/run w (:plan/intent w) nil)]
        (is (= :walk (get-in out [:plan/intent :intent/kind])))
        (is (= [60 64 8] (get-in out [:plan/intent :intent/target])))
        (is (= :hunt (get-in out [:plan/intent :intent/for])))
        (is (= {:forage/since 1000 :forage/leg 1} (:hunt/forage out)))))
    (with-redefs [path/plan (constantly {:path/status :none :path/waypoints []})]
      (let [out (intent/run w (:plan/intent w) nil)]
        (is (intent/done? (:plan/intent out)))
        (is (true? (get-in out [:hunt/forage :forage/spent])))
        (is (plan/done-by (dissoc out :plan/intent) hunt-row)
            "the search is over, the portal goes on")))))
