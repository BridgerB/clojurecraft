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
    (testing "each attack goes before its swing, the slot never restated (gym attack-259a)"
      (is (= [:attack :swing]
             (->> sent
                  (drop-while #(not= :attack %))
                  (take 2))))
      (is (not-any? #{:set-carried-item} sent) "the sword was in hand already"))
    (is (= :engage (:intent/stage (:plan/intent w))))
    (is (= 3 (:intent/swings (:plan/intent w))))))

(deftest a-hovering-target-out-of-reach-is-pressed-in-on
  ;; issue #262: a blaze two over the floor, three off on the plane, has its aim 3.3 from the eye,
  ;; past attack-reach; the engage walks on until it is in reach, then attacks
  (let [w (assoc (standing {7 (mob :blaze [11.5 66.0 8.5] 20.0)})
                 :plan/intent
                 {:intent/kind :fight :intent/target 7 :intent/status :active})
        [w fx] (fx/fold intent-step w (map tick (range 50 2050 50)))]
    (is (seq (attacks fx)) "it attacked")
    (is (< (physics/horizontal-distance (:player/pos w) [11.5 66.0 8.5]) 3.0) "from nearer"))
  (testing "one overhead is not walked under"
    (is (not (combat/pressing? (standing {}) (mob :blaze [8.5 70.0 8.5] 20.0))))))

(deftest a-target-behind-a-wall-is-reached-along-a-route
  ;; gym blaze-fortress-h (run 38905665758): fights walked straight into the fortress's fences for
  ;; twenty seconds and failed :unreachable three in a row; a straight approach that comes no
  ;; nearer takes a route round
  (let [wall (into {} (for [z (range 6 11) y [64 65]] [[10 y z] (blocks/default-state :stone)]))
        w (assoc (standing {7 (mob :zombie [13.5 64.0 8.5] 20.0)})
                 :world/chunks {[0 0] (world/column wall)}
                 :plan/intent {:intent/kind :fight :intent/target 7 :intent/status :active})
        [w fx] (fx/fold intent-step w (map tick (range 50 12050 50)))]
    (is (not= :failed (:intent/status (:plan/intent w))) (pr-str (:intent/reason (:plan/intent w))))
    (is (seq (attacks fx)) "round the wall, and hit")))

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

(deftest a-yielding-fight-ends-under-ten-health
  ;; issue #262: the blaze-rods row plans its own retreat (cover, a meal, a rest), so its fights
  ;; end where combat's would walk straight away
  (let [[w fx] (fx/fold intent-step
                        (assoc-in (fighting 2.0) [:plan/intent :intent/yield?] true)
                        (concat (map tick (range 50 200 50))
                                [(packet
                                  {:packet/name :set-health :health 8.0 :food 20 :saturation 5.0})
                                 (tick 200)]))]
    (is (= 1 (count (attacks fx))))
    (is (= :done (:intent/status (:plan/intent w))) "done at once, not a failure")
    (is (= {} (:player/controls w)) "standing: no retreat")))

(def fence
  "A nether brick fence across the column at x 10, one high: the eye sees over it, no route
   crosses it (path/awkward-types)."
  (into {} (for [z (range 16)] [[10 64 z] (blocks/default-state :nether_brick_fence)])))

(deftest a-target-the-approach-cannot-come-to-is-not-approachable
  ;; gym blaze-fire-a (run 39131023637) runs 2 and 4: fights chosen on blazes a walk's route came
  ;; within three of failed :unreachable in a second each, the approach's route finding none
  (let [w (assoc (standing {}) :world/chunks {[0 0] (world/column fence)})
        beyond (mob :blaze [12.5 64.0 8.5] 20.0)]
    (is (combat/hittable? w beyond) "seen over the fence")
    (is (intent/routable? w {:intent/kind :walk :intent/target [12 64 8]})
        "a walk's route ends within three of it, at the fence")
    (is (not (combat/approachable? w beyond)) "the approach's, within two, finds none")
    (is (combat/approachable? (standing {}) beyond) "no fence: walked to")
    (is (combat/approachable? w (mob :blaze [11.0 64.0 8.5] 20.0)) "near on the plane"))
  (testing "a blaze two over its floor is walked under on the bot's own floor"
    (is (= [12 64 8] (combat/under-cell (standing {}) (mob :blaze [12.5 66.0 8.5] 20.0))))
    (is (= [12 63 8] (combat/under-cell (standing {}) (mob :blaze [12.5 63.0 8.5] 20.0)))
        "one under the feet stays where it is")))

(deftest a-blaze-the-press-cannot-stand-under-is-not-approachable
  ;; issue #564, sim R2-17-2: within approach-range on the plane, the sword out of reach from any
  ;; cell the press can stand in, route-walk nil. An engage already running ends done, not
  ;; :unreachable, so the blaze is not blacklisted.
  (let [stone (blocks/default-state :stone)
        low (mob :blaze [10.5 62.0 8.5] 20.0)
        w (assoc (standing {7 low})
                 :world/chunks
                 {[0 0] (world/column
                         {[9 64 8] stone [9 65 8] stone [10 64 8] stone [10 65 8] stone})})
        buried (mob :blaze [10.5 58.0 8.5] 20.0)]
    (is (combat/press-reaches? w low) "at press-within the aim would be in reach")
    (is (not (combat/press-stands? w low)) "the press's cell is stone")
    (is (not (combat/approachable? w low)))
    (is (not (combat/press-reaches? w buried)) "four under: the sword misses even stood over it")
    (is (not (combat/approachable? w buried)))
    (is (combat/approachable? w (mob :blaze [11.0 64.0 8.5] 20.0))
        "a level blaze the sword already reaches")
    (let [engaged (assoc w
                         :plan/intent
                         {:intent/kind :fight
                          :intent/target 7
                          :intent/status :active
                          :intent/stage :engage
                          :intent/since 0
                          :intent/to-the-end? true})
          [done _] (fx/fold intent-step engaged (map tick (range 50 2000 50)))]
      (is (= :done (:intent/status (:plan/intent done))))
      (is (nil? (:intent/reason (:plan/intent done))))
      (is (empty? (:player/controls done))))))

(deftest a-mob-in-water-below-is-not-defended-again-once-given-up
  ;; issues #69, #62, #582 and #161: a zombie in water two blocks below is hittable but
  ;; never strikable with no route to it; its fight ends done at once and :defend picked
  ;; it again every tick, so the stage's own task never ran
  (let [water (blocks/default-state :water)
        pit {[10 63 8] water [11 63 8] water [10 62 8] water [11 62 8] water}
        zombie (mob :zombie [11.5 62.0 8.5] 20.0)
        w (assoc (standing {7 zombie}) :world/chunks {[0 0] (world/column pit)})
        given (combat/given-up (assoc w :time/now 1000) 7)]
    (is (combat/hittable? w zombie) "seen in the water below")
    (is (empty? (combat/threats w)) "no threat: no route walks to it (approachable?)")
    (is (combat/given-up? (assoc given :time/now 2000) 7 zombie))
    (is (empty? (combat/threats (assoc given :time/now 2000))) "not picked again a while")
    (is (not (combat/given-up? (assoc given :time/now (+ 1000 combat/give-up-ms)) 7 zombie))
        "picked again after give-up-ms")
    (is (not (combat/given-up? (assoc given :time/now 2000) 7 (mob :zombie [9.5 64.0 8.5] 20.0)))
        "fought at once once within reach")
    (testing "an engage that can neither strike nor route records the give-up as it ends done"
      (let [stone (blocks/default-state :stone)
            buried (assoc (standing {7 (mob :zombie [10.5 62.0 8.5] 20.0)})
                          :world/chunks
                          {[0 0] (world/column
                                  {[9 64 8] stone [9 65 8] stone [10 64 8] stone [10 65 8] stone})}
                          :plan/intent
                          {:intent/kind :fight
                           :intent/target 7
                           :intent/status :active
                           :intent/stage :engage
                           :intent/since 0})
            [done _] (fx/fold intent-step buried (map tick (range 50 2000 50)))]
        (is (= :done (:intent/status (:plan/intent done))))
        (is (contains? (:plan/given-up done) 7))))))

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
    (is (= {:intent/kind :back-off
            :intent/target 7
            :intent/why :hurt
            :intent/distance combat/flee-radius}
           (plan/next-intent (assoc zombie :player/health 6.0) (row :defend) plan/goals))
        "hurt, the bot backs away, out to flee-radius")
    (is (plan/done-by (assoc zombie :plan/blacklist #{7}) (row :defend)) "a ghost is left alone")
    (let [angry (assoc (standing
                        {7 (assoc (mob :enderman [11.0 64.0 8.5] 40.0) :entity/creepy? true)})
                       :plan/goals
                       #{:dragon})] ; the pearls rows, in play, answer endermen themselves
      (is (= {:intent/kind :fight :intent/target 7 :intent/to-the-end? true}
             (plan/next-intent (assoc angry :player/health 6.0) (row :defend) plan/goals))
          "an angry enderman is fought to the end, hurt or not: it cannot be outrun")
      (is (plan/done-by (standing {7 (mob :enderman [11.0 64.0 8.5] 40.0)}) (row :defend))
          "a calm one is no threat"))
    (is (plan/done-by (standing {9 (mob :pig [10.0 64.0 8.5] 10.0)}) (row :defend))
        "a pig is no threat")))

(deftest only-a-hostile-in-sight-and-in-reach-of-the-floor-is-a-threat
  ;; gym blaze-fortress-c (run 38895759229): the :defend row went after blazes through the
  ;; fortress's walls and hovering over the bot's tunnel, and failed :unreachable
  (let [zombie {7 (mob :zombie [11.0 64.0 8.5] 20.0)}
        walled (assoc (standing zombie)
                      :world/chunks
                      {[0 0] (world/column {[10 64 8] (blocks/default-state :stone)
                                            [10 65 8] (blocks/default-state :stone)})})]
    (is (= #{7} (set (keys (combat/threats (standing zombie))))) "in the open, a threat")
    (is (empty? (combat/threats walled)) "behind a wall, none")
    (is (plan/done-by walled (row :defend)))
    (is (empty? (combat/threats (standing {8 (mob :blaze [9.5 68.0 8.5] 20.0)})))
        "a blaze four over the feet is out of reach from the floor")))

(deftest a-back-off-ends-eight-blocks-away
  (let [w (assoc (standing {8 (mob :creeper [10.5 64.0 8.5] 20.0)})
                 :plan/intent
                 {:intent/kind :back-off :intent/target 8 :intent/status :active})
        [w _] (fx/fold intent-step w (map tick (range 50 4000 50)))]
    (is (= :done (:intent/status (:plan/intent w))))
    (is (>= (physics/horizontal-distance (:player/pos w) [10.5 64.0 8.5]) combat/back-off-to))))

(deftest a-back-off-stops-at-a-drop-it-cannot-land
  ;; issue #514, sim R3-5-1: the cell one north was a nether-brick lip, so a look of 0.6 never
  ;; saw the void. The jump cleared the lip and the bot fell 31 into lava.
  (let [air (blocks/default-state :air)
        lip (blocks/default-state :nether_bricks)
        blocks (into {[8 64 7] lip} (for [z [5 6] y (range 60 64)] [[8 y z] air]))
        w (assoc (standing {8 (mob :creeper [8.5 64.0 10.5] 20.0)})
                 :world/chunks {[0 0] (world/column blocks)}
                 :plan/intent {:intent/kind :back-off :intent/target 8 :intent/status :active})
        [gone _] (fx/fold intent-step w [(tick 50)])]
    (is (combat/void-retreat? w (get-in w [:world/entities 8])))
    (is (= :done (:intent/status (:plan/intent gone))))
    (is (contains? (:plan/cornered gone) 8))
    (is (= [8.5 64.0 8.5] (:player/pos gone)) "it never steps onto the lip")))

(deftest a-back-off-stops-at-lava-it-cannot-step-into
  ;; reviewer finding (round 3 bot survival): backing away from an entity toward
  ;; or beside lava marks the entity cornered and stops rather than stepping into lava
  (let [lava (blocks/default-state :lava)
        blocks {[8 64 6] lava [8 63 7] lava}
        w (assoc (standing {8 (mob :creeper [8.5 64.0 10.5] 20.0)})
                 :world/chunks {[0 0] (world/column blocks)}
                 :plan/intent {:intent/kind :back-off :intent/target 8 :intent/status :active})
        [gone _] (fx/fold intent-step w [(tick 50)])]
    (is (combat/lava-retreat? w (get-in w [:world/entities 8])))
    (is (= :done (:intent/status (:plan/intent gone))))
    (is (contains? (:plan/cornered gone) 8))
    (is (= [8.5 64.0 8.5] (:player/pos gone)) "it never steps into lava")))

(deftest a-creeper-that-stands-still-is-shunned-not-fled-from-for-ever
  ;; issues #69, #62, #161: a starving bot at one health backed away from a creeper five blocks
  ;; off that never moved, again and again, and the walk it was on never finished
  (let [creeper (mob :creeper [12.0 64.0 8.5] 20.0)
        seen (fn [times pos] {:plan/backed-off {8 {:pos pos :times times}}})
        world (fn [times pos] (merge (standing {8 creeper}) (seen times pos)))]
    (is (= #{8} (set (keys (combat/threats (world 1 [12.0 64.0 8.5])))))
        "backed away from once: still a threat at three and a half blocks")
    (is (empty? (combat/threats (world combat/shun-after [12.0 64.0 8.5])))
        "backed away from twice and it stood: only within shunned-radius")
    (is (= #{8}
           (set (keys (combat/threats (-> (standing {8 (mob :creeper [10.5 64.0 8.5] 20.0)})
                                          (merge (seen combat/shun-after [10.5 64.0 8.5])))))))
        "two blocks off, a shunned creeper is a threat again")
    (is (empty? (combat/threats (-> (standing {8 (mob :creeper [10.5 64.0 8.5] 20.0)})
                                    (merge (seen combat/given-up-after [10.5 64.0 8.5])))))
        "given up on, only one at the feet is")
    (is (= #{8} (set (keys (combat/threats (world combat/given-up-after [20.0 64.0 8.5])))))
        "a creeper that came on from where it stood is counted afresh")))

(deftest a-back-off-that-ran-out-its-clock-fights-the-next-time
  ;; issue #161 (sim race 39593219246, world R3/41): at under one health in a dead end, the bot
  ;; backed away from a zombie two blocks off every ten seconds for a quarter hour and never moved
  (let [zombie (mob :zombie [10.5 64.0 8.5] 20.0)
        hurt (assoc (standing {7 zombie}) :player/health 0.7)
        backing (assoc
                 hurt
                 :plan/intent
                 {:intent/kind :back-off :intent/target 7 :intent/status :active :intent/since 0})
        [timed-out _] (fx/fold intent-step backing [(tick (+ combat/back-off-timeout-ms 100))])]
    (is (= :done (:intent/status (:plan/intent timed-out))))
    (is (= #{7} (:plan/cornered timed-out)) "the target is kept")
    (is (= {:intent/kind :back-off
            :intent/target 7
            :intent/why :hurt
            :intent/distance combat/flee-radius}
           (plan/next-intent hurt (row :defend) plan/goals))
        "before, hurt, the bot backs away")
    (is
     (= {:intent/kind :back-off
         :intent/target 7
         :intent/why :hurt
         :intent/distance combat/flee-radius}
        (plan/next-intent timed-out (row :defend) plan/goals))
     "after, at 0.7 health it backs away again: under one hit plus a margin a cornered zombie is never fought on to death (issue #563)")
    (is (= {:intent/kind :fight :intent/target 7 :intent/to-the-end? true}
           (plan/next-intent (assoc timed-out :player/health 20.0) (row :defend) plan/goals))
        "at full health, it fights what it could not get away from, to the end")
    (let [[going _] (fx/fold intent-step backing [(tick 1000) (tick 2000)])]
      (is (not (contains? going :plan/cornered)) "a back-off still going has cornered nothing"))))

(deftest a-zombie-that-stands-still-is-shunned-too
  ;; issue #62 (sim race 39076908669, world R1/11): at a third of a health a zombie stuck three
  ;; blocks off was backed away from, the climb went back toward it, and so on, seven thousand
  ;; times
  (let [zombie (mob :zombie [8.5 64.0 5.5] 20.0)
        hurt (assoc (standing {7 zombie}) :player/health 0.3)
        seen (fn [times] {:plan/backed-off {7 {:pos [8.5 64.0 5.5] :times times}}})]
    (is (= #{7} (set (keys (combat/threats hurt))))
        "never backed away from: a threat at three blocks")
    (is (= #{7} (set (keys (combat/threats (merge hurt (seen 1)))))) "once: still one")
    (is (= #{7} (set (keys (combat/threats (merge hurt (seen combat/shun-after))))))
        "twice and it stood: three blocks is still inside shunned-radius")
    (is (empty? (combat/threats (merge (assoc hurt :player/pos [8.5 64.0 9.5])
                                       (seen combat/shun-after))))
        "four blocks off and shunned: not a threat")
    (is (empty? (combat/threats (merge hurt (seen combat/given-up-after))))
        "given up on: only one at the feet is")
    (let [w
          (assoc hurt :plan/intent {:intent/kind :back-off :intent/target 7 :intent/status :active})
          [w _] (fx/fold intent-step w [(tick 50)])]
      (is (= 1 (get-in w [:plan/backed-off 7 :times])) "the first tick of a back-off notes it")
      (is (nil? (:plan/fled w)) "a back-off that is not a break-off records no flee"))))

(deftest back-offs-from-a-creeper-count-where-it-stood
  (let [w (assoc (standing {8 (mob :creeper [13.0 64.0 8.5] 20.0)})
                 :plan/intent
                 {:intent/kind :back-off :intent/target 8 :intent/status :active})
        e (get-in w [:world/entities 8])
        once (combat/noted w 8 e)
        twice (combat/noted once 8 e)
        moved (combat/noted twice 8 (assoc e :entity/pos [9.5 64.0 8.5]))
        zombie (mob :zombie [11.0 64.0 8.5] 20.0)]
    (is (= {8 {:pos [13.0 64.0 8.5] :times 1}} (:plan/backed-off once)))
    (is (= 2 (get-in twice [:plan/backed-off 8 :times])))
    (is (= {:pos [9.5 64.0 8.5] :times 1} (get-in moved [:plan/backed-off 8]))
        "a creeper that came on starts its count again")
    (is (= 1 (get-in (combat/noted w 7 zombie) [:plan/backed-off 7 :times])) "any kind is kept"))
  (let [w (assoc (standing {8 (mob :creeper [13.0 64.0 8.5] 20.0)})
                 :plan/intent
                 {:intent/kind :back-off :intent/target 8 :intent/status :active})
        [w _] (fx/fold intent-step w [(tick 50)])]
    (is (= 1 (get-in w [:plan/backed-off 8 :times])) "the first tick of a back-off notes it")
    (is (nil? (:plan/fled w)) "a creeper is not a fled fact")))

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

(defn flee
  "The :back-off :defend starts from a break-off under retreat-health."
  [eid why]
  {:intent/kind :back-off :intent/target eid :intent/why why :intent/distance combat/flee-radius})

(deftest health-just-over-retreat-does-not-restart-a-fight
  ;; race 40466567590 Clj_r04 at 11.73m: regeneration put health at 10.2, at or over
  ;; retreat-health, and :defend picked :fight again. The zombie is on 4 and the bot holds a
  ;; stone sword, so only the hysteresis can refuse it. At resume-health it fights.
  (let [z (mob :zombie [11.0 64.0 8.5] 4.0)
        w
        (assoc (standing {7 z}) :player/health 10.2 :plan/fled {7 {:fled/at 1000 :fled/why :hurt}})]
    (is (= (flee 7 :hurt) (plan/next-intent w (row :defend) plan/goals)))
    (is (=
         {:intent/kind :fight :intent/target 7}
         (plan/next-intent (assoc w :player/health combat/resume-health) (row :defend) plan/goals))
        "at resume-health the break-off is over")
    (testing "the :hurt fact is cleared at resume-health, so a later dip does not reuse it"
      (let [rested (combat/spent (assoc w :player/health combat/resume-health))]
        (is (nil? (:plan/fled rested)))
        (is (not (combat/fled? (assoc rested :player/health 14.0) 7)))))
    (is (= :hurt (get-in (combat/spent (assoc w :player/health 14.0)) [:plan/fled 7 :fled/why]))
        "under resume-health the fact stays")
    (testing "the same shape as the death: no sword, a full zombie, health 10.2, already fled"
      (let [full (mob :zombie [11.0 64.0 8.5] 20.0)
            bare (assoc (standing {7 full})
                        :player/inventory {}
                        :player/health 10.2
                        :plan/fled {7 {:fled/at 1000 :fled/why :hurt}})]
        (is (= (flee 7 :hurt) (plan/next-intent bare (row :defend) plan/goals))))))
  (testing "the first tick of that back-off remembers the mob"
    (let [w (assoc (standing {7 (mob :zombie [11.0 64.0 8.5] 20.0)})
                   :player/health 9.0
                   :time/now 1000
                   :plan/intent (assoc (flee 7 :hurt) :intent/status :active))
          [gone _] (fx/fold intent-step w [(tick 50)])]
      (is (= :hurt (get-in gone [:plan/fled 7 :fled/why])))
      (is (= :hurt (get-in (combat/fled gone 7) [:plan/fled 7 :fled/why]))))))

(deftest a-back-off-does-not-resume-the-walk-it-interrupted
  ;; Clj_r04 at 11.71m: back-off 10189 and walk [2898 11 2236] every tick. The zombie was just
  ;; past defend-radius, the walk's target was past the zombie, and :defend let go.
  (let [z (mob :zombie [13.0 64.0 8.5] 20.0)
        w (assoc (standing {7 z})
                 :player/health 9.0
                 :time/now 2000
                 :time/tick 1
                 :plan/status :active
                 :plan/since 0
                 :plan/goals #{:past-zombie}
                 :plan/fled {7 {:fled/at 1000 :fled/why :hurt}}
                 :plan/intent
                 {:intent/kind :back-off
                  :intent/target 7
                  :intent/status :active
                  :intent/goal :defend
                  :intent/since 0
                  :intent/why :hurt
                  :intent/distance combat/flee-radius})
        table (conj plan/goals
                    {:goal/id :past-zombie
                     :goal/priority 5
                     :goal/target? true
                     :goal/done? :unmet
                     :goal/plan :past-zombie})
        walked {:intent/kind :walk :intent/target [20 64 8]}]
    (is (not (plan/done-by w (row :defend))) "4.5 off and fled: still a threat")
    (is (plan/done-by (dissoc w :plan/fled) (row :defend))
        "the same zombie with no flee is past defend-radius")
    (is (combat/passes? [8.5 64.0 8.5] (physics/centre [20 64 8]) [13.0 64.0 8.5])
        "the walk to [20 64 8] passes through the zombie")
    (is (not (combat/passes? [8.5 64.0 8.5] (physics/centre [0 64 8]) [13.0 64.0 8.5]))
        "a walk the other way does not")
    (is (= {:intent/kind :back-off
            :intent/target 7
            :intent/distance combat/flee-radius
            :intent/for :flee}
           (combat/not-toward-fled w walked))
        "inside flee-radius the walk becomes a back-off")
    (let [out (assoc-in w [:world/entities 7 :entity/pos] [21.0 64.0 8.5])]
      (is (= {:intent/kind :walk :intent/target [0 64 8] :intent/for :flee}
             (combat/not-toward-fled out {:intent/kind :walk :intent/target [30 64 8]}))
          "past flee-radius the walk is turned away, not handed back"))
    (let [after (plan/step-over table w {:event/kind :tick :event/now 2050 :event/rand 0.5})
          i (:plan/intent after)]
      (is (= :back-off (:intent/kind i)))
      (is (= 7 (:intent/target i)))
      (is (= :defend (:intent/goal i)) "the interrupted walk did not take the tick"))))

(defn zombies-alive
  "The model's zombies that are not dead."
  [sim]
  (filter (fn [[_ m]] (and (= :zombie (:type m)) (not (:dead-at m)))) (:sim/mobs sim)))

(deftest a-zombie-at-hand-is-killed-against-the-model
  ;; the model's zombie (mob.clj, issue #258) walks at the bot and hits it; the bot fights back
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 8.5]
                                    :inventory {[:hotbar 0] (item :stone_sword 1)}
                                    :mobs [{:type :zombie :pos [11.5 64.0 8.5]}]})
                         #(or (plan/done? %) (plan/failed? %))
                         30000
                         {:event/kind :go :go/goals [:defend]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (empty? (zombies-alive sim)) "the zombie is dead")
    (is (pos? (:sim/health sim)) "and the bot alive")
    (is (= [] (:sim/violations sim)))))

(deftest the-attack-trial-kills-a-zombie-coming-from-afar-against-the-model
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init {:column (world/column-bytes {})
                                    :spawn [8.5 64.0 1.5]
                                    :inventory {[:hotbar 2] (item :stone_sword 1)}
                                    :mobs [{:type :zombie :pos [8.5 64.0 13.5]}]})
                         #(or (plan/done? %) (plan/failed? %))
                         60000
                         {:event/kind :go :go/goals [:attack]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (empty? (zombies-alive sim)))
    (is (= 2 (:player/held-slot w)) "the sword was taken into the hand first")))

(deftest an-attack-trial-looks-round-before-it-finds-nothing
  ;; gym attack-259a run 3 (run 38881958899): done 0.2 s after the go, the husk still unseen
  (let [w (assoc (standing {}) :time/now 5000)]
    (is (not (plan/done-by (assoc w :plan/since 4000) (row :attack))) "a second in: still looking")
    (is (plan/done-by (assoc w :plan/since 1000) (row :attack))
        "four seconds in, nothing near: done")
    (is (not (plan/done-by
              (assoc w :plan/since 1000 :world/entities {7 (mob :husk [14.5 64.0 8.5] 20.0)})
              (row :attack))))))

(deftest a-fight-that-holds-its-ground-never-walks
  ;; issue #263: the pearl hunt fights from a burrow, where a step out is the one way to be hit
  (let [w (assoc-in (fighting 5.0) [:plan/intent :intent/hold?] true)
        [waited _] (fx/fold intent-step w (map tick (range 50 2000 50)))
        [hurt _] (fx/fold intent-step (assoc w :player/health 8.0) (map tick (range 50 600 50)))]
    (is (= [8.5 64.0 8.5] (:player/pos waited)) "it waits for the zombie five blocks off")
    (is (= :approach (:intent/stage (:plan/intent waited))))
    (is (= :active (:intent/status (:plan/intent waited))))
    (is (not= :retreat (:intent/stage (:plan/intent hurt))) "and never breaks off under 10")))

(deftest a-fight-that-holds-its-ground-gives-up-on-one-in-reach-on-the-plane-only
  ;; gym batch pearls-warped-b (plan run 39135767198) run 3: an enderman two blocks off on the
  ;; plane and fourteen below, never in reach, held twenty minutes
  (let [w (-> (standing {7 (mob :zombie [10.5 50.0 8.5] 20.0)})
              (assoc
               :plan/intent
               {:intent/kind :fight :intent/target 7 :intent/status :active :intent/hold? true}))
        [early _] (fx/fold intent-step w (map tick (range 500 30000 500)))
        [late _] (fx/fold intent-step w (map tick (range 500 70000 500)))]
    (is (= :engage (:intent/stage (:plan/intent early))))
    (is (= :active (:intent/status (:plan/intent early))))
    (is (= [:failed :unreachable] ((juxt :intent/status :intent/reason) (:plan/intent late)))
        "past hold-timeout-ms with no blow")))

(deftest a-threat-is-judged-from-the-floor-not-the-top-of-a-jump
  ;; issue #178 (R4/14): a stairs up's jump lifts the bot a block and more, enough to see two
  ;; zombies past a corner at its top; the fight began and ended every fourteen ticks, the stairs
  ;; up preempted before it placed its block, and never went on
  (let [w (standing {})
        jumped (assoc w :player/pos [8.5 65.3 8.5] :player/on-ground? false)
        zombie (mob :zombie [8.5 67.5 9.5] 20.0)]
    (is (= [8.5 64.0 8.5] (combat/floor-pos w)) "standing on the floor")
    (is (= [8.5 64.0 8.5] (combat/floor-pos jumped)) "the floor it jumped from")
    (is (= [8.5 65.0 8.5]
           (combat/floor-pos
            (assoc w
                   :player/pos [8.5 65.0 8.5]
                   :world/chunks {[0 0] (world/column {[8 64 8] (blocks/default-state :stone)})})))
        "on a block it placed")
    (is (= (combat/hittable? w zombie) (combat/hittable? jumped zombie)) "the jump changes nothing")
    (is (not (combat/hittable? w zombie))
        "three and a half over the floor is out of reach, jumped or not")))
