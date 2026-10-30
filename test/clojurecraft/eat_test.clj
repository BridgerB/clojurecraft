(ns clojurecraft.eat-test
  "Eating (issue #259): the :eat row, its done predicate and the :eat intent over canned worlds,
   and a hungry bot against the server model."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.eat :as eat]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn standing
  "A bot in play on the stone floor of one column, holding inv, its food told as food."
  [food inv]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [0.5 64.0 0.5]
             :player/loaded? true
             :player/on-ground? true
             :world/chunks {[0 0] (world/column {})}
             :player/inventory inv)
      (cond-> food (assoc :player/food food))))

(def eat-row (first (filter #(= :eat (:goal/id %)) plan/goals)))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick (no planner)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (:plan/intent w)) (intent/run w (:plan/intent w) e) w)))

(defn handled
  "The packet names of fx an eat sends or could: the hand's slot, a use, a swing."
  [fx]
  (filterv #{:set-carried-item :use-item :swing :container-click} (fx/names fx)))

(deftest the-food-rides-on-set-health
  (let [[w _] (fx/fold game/step
                       (assoc (game/init fx/opts) :bot/phase :play)
                       [(packet {:packet/name :set-health :health 18.0 :food 9 :saturation 0.5})])]
    (is (= 9 (:player/food w)))
    (is (= 0.5 (:player/saturation w)))
    (is (= 18.0 (:player/health w)))))

(deftest a-hungry-bot-holding-bread-eats-before-anything-else
  (let [w (assoc (standing 6 {2 (item :bread 3)}) :plan/goals #{:wood})]
    (is (= :eat (:goal/id (plan/choose w plan/goals))))
    (is (= {:intent/kind :eat :intent/item :bread} (plan/next-intent w eat-row plan/goals)))))

(deftest with-the-portal-kit-held-the-meal-is-eaten-up-to-food-18
  ;; issue #501: R1/2 entered the Nether at food 16 with food held, and health comes back there
  ;; only from food 18
  (let [kit {0 (item :flint_and_steel 1) 1 (item :water_bucket 1) 2 (item :bread 3)}
        row #(plan/next-intent % eat-row plan/goals)]
    (is (= {:intent/kind :eat :intent/item :bread} (row (standing 16 kit)))
        "food 16 with the kit: eat")
    (is (nil? (row (standing 18 kit))) "food 18: no")
    (is (nil? (row (standing 16 (dissoc kit 1))))
        "without the bucket the portal is not next: under 14 only")
    (is (= {:intent/kind :eat :intent/item :bread} (row (standing 13 (dissoc kit 1)))))
    (is (nil? (row (assoc (standing 16 kit) :player/dimension dimension/nether)))
        "in the Nether the meal waits for under 14")))

(deftest fed-unknown-or-foodless-the-eat-row-is-done
  (is (plan/done-by (standing 14 {2 (item :bread 3)}) eat-row) "at the threshold it is fed")
  (is (plan/done-by (standing 6 {2 (item :oak_log 3)}) eat-row) "nothing edible is held")
  (is (plan/done-by (standing nil {2 (item :bread 3)}) eat-row) "the food was never told")
  (is (= :wood (:goal/id (plan/choose (assoc (standing 6 {}) :plan/goals #{:wood}) plan/goals)))
      "hungry with nothing to eat, the run goes on"))

(deftest a-trap-comes-before-the-meal
  (let [w (assoc (standing 6 {2 (item :bread 3)}) :plan/goals #{:wood} :plan/trap :water)]
    (is (= :escape-water (:goal/id (plan/choose w plan/goals))))))

(deftest poison-and-golden-apples-are-never-eaten
  (is (nil? (eat/food-held (standing 6
                                     {0 (item :rotten_flesh 9)
                                      1 (item :spider_eye 2)
                                      2 (item :golden_apple 1)
                                      3 (item :chicken 1)}))))
  (testing "the most nourishing of what is safe"
    (is (= :cooked_beef
           (eat/food-held
            (standing 6 {0 (item :rotten_flesh 9) 5 (item :bread 1) 30 (item :cooked_beef 2)}))))))

(deftest the-meal-is-the-food-in-hand-one-use-and-the-servers-word
  (let [w (assoc (standing 6 {2 (item :bread 3)})
                 :player/held-slot 0
                 :plan/intent {:intent/kind :eat :intent/item :bread :intent/status :active})
        [w fx] (fx/fold intent-step w [(tick 50)])]
    (is (= [:set-carried-item] (handled fx)) "the bread's hotbar slot is selected")
    (is (= 2 (:player/held-slot w)))
    (let [[w fx] (fx/fold intent-step w [(tick 100)])]
      (is (= [:use-item] (handled fx)) "one use, no swing")
      (is (= :eating (:intent/stage (:plan/intent w))))
      (let [[w fx] (fx/fold intent-step w (map tick (range 150 1700 50)))]
        (is (= [] (handled fx)) "nothing more is sent while the server eats")
        (is (= :active (:intent/status (:plan/intent w))))
        (is (= {} (:player/controls w)) "it stands still")
        (let [[w _] (fx/fold intent-step
                             w
                             [(packet
                               {:packet/name :set-health :health 20.0 :food 11 :saturation 6.0})
                              (tick 1750)])]
          (is (= :done (:intent/status (:plan/intent w))) "the server's food rose"))))))

(deftest a-meal-the-server-never-gives-fails-not-eaten
  (let [w (assoc (standing 6 {0 (item :bread 3)})
                 :player/held-slot 0
                 :plan/intent {:intent/kind :eat :intent/item :bread :intent/status :active})
        [w _] (fx/fold intent-step w (map tick (range 50 4700 50)))]
    (is (= :active (:intent/status (:plan/intent w))) "inside the eating time and the answer's")
    (let [[w _] (fx/fold intent-step w (map tick (range 4700 5000 50)))]
      (is (= [:failed :not-eaten] ((juxt :intent/status :intent/reason) (:plan/intent w)))))))

(deftest a-hungry-bot-eats-its-bread-against-the-model
  ;; 6 + 5 is still hungry, 6 + 5 + 5 is not: two breads eaten, two left
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [0.5 64.0 0.5]
                                    :food 6
                                    :inventory {[:hotbar 3] (item :bread 4)}})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:eat]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 16 (:player/food w)))
    (is (= (:sim/food sim) (:player/food w)) "the bot's food is the server's")
    (is (= 2 (:bread (recipe/counts (:player/inventory w)))))
    (is (= [] (:sim/violations sim)))))

(deftest no-meal-is-begun-in-the-middle-of-the-blows
  ;; gym chain-w16 run 1 (plan run 40863839525): `:dig-down`, `:eat`, `:dig-down` on a crystal's
  ;; pillar while the dragon's blows took 13.7 to 11.8 to 8.9 to 6.0, then the cloud the rest
  (let [w (assoc (standing 6 {2 (item :bread 3)}) :plan/goals #{:wood} :time/tick 1000)
        hurt (fn [at] (assoc w :player/hurt-at at))]
    (is (eat/under-attack? (hurt 990)) "ten ticks ago")
    (is (not (eat/under-attack? (hurt 900))) "five seconds ago")
    (is (not (eat/under-attack? w)) "never")
    (is (nil? (plan/next-intent (hurt 990) eat-row plan/goals)) "no meal under the blows")
    (is (plan/done-by (hurt 990) eat-row) "the row steps aside")
    (is (= {:intent/kind :eat :intent/item :bread} (plan/next-intent (hurt 900) eat-row plan/goals))
        "the meal comes back once the blows stop")))
