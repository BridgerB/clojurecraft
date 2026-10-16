(ns clojurecraft.combat-test
  "Fighting (issue #259): the attack packet, the :fight and :back-off intents tick by tick over
   canned worlds, the :defend and :attack rows' rules, the attacks' pacing as a property, and
   fights against the server model's mobs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx :refer [packet]]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn mob
  "A tracked living entity of type k (a name) at pos, with health h."
  [k pos h]
  {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0 :entity/health h})

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column, a stone sword in hotbar slot 0,
   with the given entities."
  [entities]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :player/health 20.0
             :world/chunks {[0 0] (world/column {})}
             :window/state-id 5
             :player/inventory {0 (item :stone_sword 1)}
             :world/entities entities)))

(defn fighting
  "standing with a zombie (eid 7) d blocks east and a :fight on it running."
  [d]
  (assoc (standing {7 (mob :zombie [(+ 8.5 d) 64.0 8.5] 20.0)})
         :plan/intent
         {:intent/kind :fight :intent/target 7 :intent/status :active}))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(defn attacks
  "[[ms packet] ...] of the attack packets in fx."
  [fx]
  (filterv #(= :attack (:packet/name (second %))) (fx/sent fx)))

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(deftest an-attack-is-its-own-packet-the-entity-id-alone
  ;; 775 split attacking out of interact: id 1, then the target's id as a varint
  (is (= [0x01 0xAC 0x02]
         (map #(bit-and % 0xff) (p/encode :play {:packet/name :attack :entity-id 300}))))
  (is (= (seq (b/with-out (fn [o] (b/write-varint o 1) (b/write-varint o 300))))
         (seq (p/encode :play {:packet/name :attack :entity-id 300})))))

(deftest a-fight-faces-its-target-then-attacks-650-ms-apart
  (let [[w fx] (fx/fold intent-step (fighting 2.0) (map tick (range 50 2050 50)))
        hits (attacks fx)
        sent (fx/names fx)]
    (is (= [150 800 1450] (mapv first hits)) "the look a tick before the first, then 650 ms apart")
    (is (every? #(= 7 (:entity-id (second %))) hits))
    (testing "each attack restates the slot and swings first"
      (is (= [:set-carried-item :swing :attack]
             (->> sent
                  (drop-while #(not= :set-carried-item %))
                  (take 3)))))
    (is (= :engage (:intent/stage (:plan/intent w))))
    (is (= 3 (:intent/swings (:plan/intent w))))))

(deftest a-target-gone-is-a-kill
  (let [[w _] (fx/fold intent-step
                       (fighting 2.0)
                       (concat (map tick (range 50 300 50))
                               [(packet {:packet/name :remove-entities :ids [7]}) (tick 300)]))]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (= {} (:player/controls w)))))

(deftest a-target-that-takes-25-swings-is-a-ghost
  (let [[w fx] (fx/fold intent-step (fighting 2.0) (map tick (range 50 18000 50)))]
    (is (= combat/max-swings (count (attacks fx))))
    (is (= [:failed :ghost] ((juxt :intent/status :intent/reason) (:plan/intent w))))))

(deftest under-ten-health-the-fight-breaks-off-and-walks-away
  (let [[w fx] (fx/fold intent-step
                        (fighting 2.0)
                        (concat (map tick (range 50 200 50))
                                [(packet
                                  {:packet/name :set-health :health 8.0 :food 20 :saturation 5.0})]
                                (map tick (range 200 4000 50))))]
    (is (= 1 (count (attacks fx))) "one attack before the hurt, none after")
    (is (= :done (:intent/status (:plan/intent w))) "a fight broken off is not a failure")
    (is (>= (physics/horizontal-distance (:player/pos w) [10.5 64.0 8.5])
            combat/retreat-distance))))

(deftest a-zombie-at-hand-is-fought-a-creeper-backed-away-from
  (let [zombie (standing {7 (mob :zombie [11.0 64.0 8.5] 20.0)})
        creeper (standing {8 (mob :creeper [13.0 64.0 8.5] 20.0)})
        far (standing {7 (mob :zombie [13.0 64.0 8.5] 20.0)})]
    (is (= :defend (:goal/id (plan/choose (assoc zombie :plan/goals #{:wood}) plan/goals))))
    (is (= {:intent/kind :fight :intent/target 7}
           (plan/next-intent zombie (row :defend) plan/goals)))
    (is (= {:intent/kind :back-off :intent/target 8}
           (plan/next-intent creeper (row :defend) plan/goals))
        "a creeper five blocks off is a threat, and never fought")
    (is (plan/done-by far (row :defend)) "a zombie four and a half blocks off is not yet one")
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent (assoc zombie :player/health 6.0) (row :defend) plan/goals))
        "hurt, the bot backs away instead")
    (is (plan/done-by (assoc zombie :plan/blacklist #{7}) (row :defend)) "a ghost is left alone")
    (is (plan/done-by (standing {9 (mob :pig [10.0 64.0 8.5] 10.0)}) (row :defend))
        "a pig is no threat")))

(deftest a-back-off-ends-eight-blocks-away
  (let [w (assoc (standing {8 (mob :creeper [10.5 64.0 8.5] 20.0)})
                 :plan/intent
                 {:intent/kind :back-off :intent/target 8 :intent/status :active})
        [w _] (fx/fold intent-step w (map tick (range 50 4000 50)))]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (>= (physics/horizontal-distance (:player/pos w) [10.5 64.0 8.5]) combat/back-off-to))))

(deftest no-two-attacks-within-600-ms
  ;; the property the issue asks for: whatever the clock does between ticks (a starved loop, a
  ;; burst), the attacks keep their distance
  (let [r (tc/quick-check 60
                          (prop/for-all
                           [gaps (gen/vector (gen/choose 1 250) 40 300) d
                            (gen/double* {:min 0.5 :max 3.0 :NaN? false :infinite? false})]
                           (let [times (reductions + 50 gaps)
                                 [_ fx] (fx/fold intent-step (fighting d) (map tick times))
                                 at (map first (attacks fx))]
                             (every? (fn [[a b]] (>= (- b a) 600)) (partition 2 1 at)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest a-zombie-at-hand-is-killed-against-the-model
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 8.5]
                                    :inventory {[:hotbar 0] (item :stone_sword 1)}
                                    :mobs {50 {:type :zombie :pos [10.5 64.0 8.5] :health 20.0}}})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:defend]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= 4 (:sim/hits sim)) "a stone sword takes five of the zombie's twenty a hit")
    (is (= (:sim/hits sim) (:sim/attacks sim))
        "no attack fell inside the hurt time or out of reach")
    (is (empty? (:sim/mobs sim)))
    (is (nil? (get-in w [:world/entities 50])))
    (is (= [] (:sim/violations sim)))))

(deftest the-attack-trial-walks-to-a-husk-and-kills-it-against-the-model
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 1.5]
                                    :inventory {[:hotbar 2] (item :stone_sword 1)}
                                    :mobs {51 {:type :husk :pos [8.5 64.0 12.5] :health 20.0}}})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:attack]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (empty? (:sim/mobs sim)))
    (is (= 4 (:sim/hits sim)) "the sword was taken into the hand first")))
