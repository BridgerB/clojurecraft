(ns clojurecraft.combat-test
  "Fighting (issue #259): the attack packet, the :fight and :back-off intents tick by tick over
   canned worlds, the :defend and :attack rows' rules, the attacks' pacing as a property, and
   fights against the server model's mobs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
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

(deftest under-the-danger-line-the-fight-breaks-off-and-walks-away
  ;; a zombie's line is its hit (3) and the margin (3): 6 (combat/danger-line), no longer 10
  (let [[w fx] (fx/fold intent-step
                        (fighting 2.0)
                        (concat (map tick (range 50 200 50))
                                [(packet
                                  {:packet/name :set-health :health 5.0 :food 20 :saturation 5.0})]
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
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent (assoc zombie :player/health 5.0) (row :defend) plan/goals))
        "under a zombie's line (its hit and the margin, 6), the bot backs away instead")
    (is (= {:intent/kind :fight :intent/target 7}
           (plan/next-intent (assoc zombie :player/health 8.0) (row :defend) plan/goals))
        "at 8, over it: fought")
    (is (plan/done-by (assoc zombie :plan/blacklist #{7}) (row :defend)) "a ghost is left alone")
    (let [angry (assoc (standing
                        {7 (assoc (mob :enderman [11.0 64.0 8.5] 40.0) :entity/creepy? true)})
                       :plan/goals
                       #{:dragon})] ; the pearls rows, in play, answer endermen themselves
      (is
       (= {:intent/kind :back-off :intent/target 7}
          (plan/next-intent (assoc angry :player/health 6.0) (row :defend) plan/goals))
       "under danger-health, an angry enderman with nothing held to cover with is backed off from, never fought on to death, never the wait that ends the plan")
      (is (= {:intent/kind :fight :intent/target 7 :intent/to-the-end? true}
             (plan/next-intent (assoc angry :player/health 20.0) (row :defend) plan/goals))
          "healthy, an angry enderman is fought to the end: it cannot be outrun")
      (is (plan/done-by (standing {7 (mob :enderman [11.0 64.0 8.5] 40.0)}) (row :defend))
          "a calm one is no threat"))
    (is (plan/done-by (standing {9 (mob :pig [10.0 64.0 8.5] 10.0)}) (row :defend))
        "a pig is no threat")))

(deftest a-zombified-piglin-is-left-until-it-strikes-then-backed-away-from
  ;; gym blaze-wall-b (plan 40624888909) blaze-fortress run 1: a swing at blaze 442 also hit
  ;; zombified piglin 126 (damage-event source-cause 28, the bot). Piglins 144 and 126 then hit
  ;; the bot (causes 145 and 127) from 15.5 health to none while the intent was :rest.
  (let [calm (standing {7 (mob :zombified_piglin [11.0 64.0 8.5] 20.0)})
        struck (assoc calm
                      :player/health 13.0
                      :player/entity-id 27
                      :player/attacker 7
                      :player/struck-at 1000
                      :time/now 1500)]
    (is (empty? (combat/threats calm)) "a calm one is no threat, and not a fight")
    (is (plan/done-by calm (row :defend)))
    (is (= [7 (get-in struck [:world/entities 7])] (combat/striker struck)))
    (is (contains? (combat/threats struck) 7))
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent struck (row :defend) plan/goals))
        "struck: backed away from, never swung at, even over retreat-health")
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent (assoc struck :player/health 20.0 :plan/cornered #{7})
                             (row :defend)
                             plan/goals))
        "cornered or not, the pack is not fought")
    (testing "a damage-event on the bot records the attacker as source-cause minus one"
      (let [w (assoc calm :player/entity-id 27 :time/now 1500)
            hit (packet {:packet/name :damage-event
                         :entity-id 27
                         :source-type 28
                         :source-cause 8
                         :source-direct 8})
            other (packet {:packet/name :damage-event
                           :entity-id 7
                           :source-type 34
                           :source-cause 28
                           :source-direct 28})
            none (packet {:packet/name :damage-event
                          :entity-id 27
                          :source-type 10
                          :source-cause 0
                          :source-direct 0})]
        (is (= 7 (:player/attacker (game/step w hit))))
        (is (= 1500 (:player/struck-at (game/step w hit))))
        (is (nil? (:player/attacker (game/step w other)))
            "a hit on the piglin is not one on the bot")
        (is (nil? (:player/attacker (game/step w none))) "source-cause 0 is no entity")))))

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
        "a blaze four over the feet is out of reach from the floor")
    (let [in-pit (standing {9 (mob :silverfish [8.5 62.0 11.0] 8.0)})]
      (is
       (empty? (combat/threats in-pit))
       "a silverfish in a pit two below with no way down is not approachable and thus no threat"))))

(deftest a-wither-skeleton-is-not-fought-below-full-health-nor-two-of-them
  ;; blaze-race-f (real, 6e703362a) race row runs 2 and 3: :fight 82 at 14.9, three wither skeletons
  ;; (82, 83, 84) hit for 5 each within 0.03 min, dead at 2m42 and 2m52
  (let [ws (fn [& xs]
             (into {}
                   (map-indexed (fn [i x] [(+ 80 i) (mob :wither_skeleton [x 64.0 8.5] 20.0)]) xs)))
        defend (fn [entities health]
                 (plan/next-intent (assoc (standing entities) :player/health health)
                                   (row :defend)
                                   plan/goals))]
    (is (= :fight (:intent/kind (defend (ws 11.0) 20.0))) "one, at full health: fought")
    (is (= :back-off (:intent/kind (defend (ws 11.0) 15.0))) "one, hurt: not")
    (is (= :back-off (:intent/kind (defend (ws 11.0 12.0) 20.0))) "two: not")
    (is (= :fight (:intent/kind (defend {7 (mob :zombie [11.0 64.0 8.5] 20.0)} 15.0)))
        "a zombie is as before")))

(deftest a-ghast-is-never-a-fight
  ;; blaze-race-c job 4 (3a082f7b7, real): burned to a crisp while fighting a ghast
  (let [ghast {8 (mob :ghast [11.0 65.0 8.5] 10.0)}]
    (is (empty? (combat/threats (standing ghast))) "in reach, in sight, still not a fight")
    (is (plan/done-by (standing ghast) (row :defend)))
    (is (= #{7}
           (set (keys (combat/threats (standing
                                       (assoc ghast 7 (mob :zombie [11.0 64.0 8.5] 20.0)))))))
        "a zombie beside it still is")))

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
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent hurt (row :defend) plan/goals))
        "before, hurt, the bot backs away")
    (is
     (= {:intent/kind :back-off :intent/target 7}
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
      (is (= 1 (get-in w [:plan/backed-off 7 :times])) "the first tick of a back-off notes it"))))

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
    (is (= 1 (get-in w [:plan/backed-off 8 :times])) "the first tick of a back-off notes it")))

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

(deftest silverfish-are-fought-holding-ground-without-leaving-safe-footing
  ;; gym chain-w7 run 3: silverfish in the portal room must be fought without stepping
  ;; off safe footing into the lava pool below
  (let [silverfish (mob :silverfish [10.5 64.0 10.5] 8.0)
        w (standing {12 silverfish})]
    (is (= {:intent/kind :fight :intent/target 12 :intent/hold? true}
           (plan/next-intent w {:goal/id :defend :goal/plan :defend} plan/goals))
        "silverfish are fought with :intent/hold? true")
    (is (not (combat/pressing? w silverfish)) "pressing? never walks toward silverfish")))

(deftest nothing-is-fought-inside-the-dragons-breath
  ;; gym chain-w11 run 4 (plan run 40741523320): an enderman fought at 14 health inside a breath
  ;; cloud, six health every half second of magic, 14 to 0 in three seconds
  (let [zombie {7 (mob :zombie [11.0 64.0 8.5] 20.0)}
        cloud (fn [pos]
                {8 {:entity/type (blocks/entity-types :area_effect_cloud)
                    :entity/pos pos
                    :entity/radius 3.0}})
        end (fn [entities] (assoc (standing entities) :player/dimension dimension/end))]
    (is (= #{7} (set (keys (combat/threats (end zombie)))))
        "in the End, out of any cloud, a threat")
    (is (empty? (combat/threats (end (merge zombie (cloud [9.5 64.0 8.5])))))
        "inside a cloud, none")
    (is (= #{7} (set (keys (combat/threats (end (merge zombie (cloud [30.5 64.0 8.5])))))))
        "a cloud far off changes nothing")
    (is (= #{7} (set (keys (combat/threats (standing (merge zombie (cloud [9.5 64.0 8.5])))))))
        "outside the End a cloud is no reason to leave a fight")))

(deftest a-back-off-that-ends-at-once-is-never-chosen-again-and-never-a-fight
  ;; gym chain-w13 dragon run 2 (plan run 40798605016, job 4): an angry enderman on the End's
  ;; island, the bot under 10 health, `intent :back-off 86` every 50 ms from 04:03:36.6 and
  ;; each ended cornered in the tick it began; eight hits, four swings, dead. Under the line the
  ;; bot never fights (#577): a meal, a block between, a pillar, a hole
  (let [enderman (fn [pos] (assoc (mob :enderman pos 40.0) :entity/creepy? true))
        at (fn [pos inv]
             (assoc (standing {7 (enderman pos)})
                    :plan/goals #{:dragon}
                    :player/health 6.0
                    :player/food 10
                    :player/inventory inv))
        defend (row :defend)
        steak {0 (item :cooked_beef 4)}
        stone {1 (item :cobblestone 8)}
        close [11.0 64.0 8.5]
        e #(get-in % [:world/entities 7])]
    (is (= {:intent/kind :back-off :intent/target 7}
           (plan/next-intent (at close {}) defend plan/goals))
        "with room to back off to: backed away from")
    (is (not (combat/back-off-pointless? (at close {}) (e (at close {})))) "room to go")
    (is (not (combat/back-off-pointless? (assoc (at close {}) :plan/cornered #{7})
                                         (e (at close {}))))
        "a back-off that ran out its clock is tried again (issue #161)")
    (testing "cover, never the fight"
      (is (= {:intent/kind :eat :intent/item :cooked_beef}
             (combat/cover (at [13.0 64.0 8.5] steak) (e (at [13.0 64.0 8.5] steak))))
          "food held and the enderman out of attack-reach: the meal")
      (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [9 64 8]}
             (combat/cover (at close (merge steak stone)) (e (at close {}))))
          "in reach: a block between, at the feet's height first, not the meal")
      (is (= {:plan/wait :cornered} (combat/cover (at close {}) (e (at close {}))))
          "nothing held at all: the wait that ends the plan, never the fight")
      (let [pick (at close {2 (item :stone_pickaxe 1)})]
        (is (= {:intent/kind :dig :intent/target [8 63 8]} (combat/cover pick (e pick)))
            "a pickaxe and nothing to lay: a hole a block deep under the feet")))
    (testing "the wall cell taken, a pillar"
      (let [w (assoc-in (at close stone)
               [:world/chunks [0 0]]
               (world/column {[9 64 8] (blocks/default-state :stone)
                              [9 65 8] (blocks/default-state :stone)}))
            i (combat/cover w (e w))]
        (is (= :pillar (:intent/kind i)))
        (is (= 67 (:intent/top i)))))
    (testing "a target back-off-to away needs no back-off: the meal or rest"
      (let [far (at [17.0 64.0 8.5] steak)]
        (is (combat/back-off-pointless? far (e far)))
        (is (= {:intent/kind :eat :intent/item :cooked_beef} (combat/cover far (e far))))
        (is (= {:plan/wait :resting}
               (combat/cover (at [17.0 64.0 8.5] {}) (e (at [17.0 64.0 8.5] {})))))))))

(deftest on-the-islands-rim-with-the-enderman-inland-the-bot-covers
  ;; gym chain-w13 dragon run 2 (plan run 40798605016, job 4): the bot stood at [70.3 57 11.3] on
  ;; the End island's outer part when `intent :back-off 86` came every 50 ms; the way straight
  ;; away from an enderman inland is off the rim (void-retreat?), so each back-off ended cornered
  ;; in its first tick. The rim here is x 13 of the chunk; the enderman two and a half inland.
  (let [air (blocks/default-state :air)
        rim {:world/chunks
             {[0 0]
              (world/column
               (into {} (for [lx (range 13 16) lz (range 16) y (range 50 64)] [[lx y lz] air])))}}
        w (-> (standing {7 (assoc (mob :enderman [10.5 64.0 8.5] 40.0) :entity/creepy? true)})
              (merge rim)
              (assoc :player/pos [12.5 64.0 8.5]
                     :player/health 6.0
                     :player/food 10
                     :player/dimension dimension/end
                     :plan/goals #{:dragon}
                     :player/inventory {0 (item :cobblestone 8)}))
        e (get-in w [:world/entities 7])]
    (is (combat/void-retreat? w e) "away from it is off the rim")
    (is (combat/back-off-pointless? w e)
        "so a back-off is done at once, before anything is cornered")
    (let [i (plan/next-intent w (row :defend) plan/goals)]
      (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [11 64 8]} i)
          "a block between instead, never the back-off and never the fight"))))

(deftest the-danger-line-is-the-next-hits-and-a-margin
  ;; gym chain-w13 run 4 (job 4, plan run 40798605016): bitten 25 times in 23 s from 9.3 health by
  ;; silverfish 2194 while backing away, a back-off at a time, from mob 2193; dead at 14.13 min.
  ;; #577: never fight when the next hits can kill; the line is the hits near plus a margin of 3,
  ;; at most retreat-health
  (let [at (fn [entities health] (assoc (standing entities) :player/health health))
        fish (fn [x] (mob :silverfish [x 64.0 8.5] 8.0))
        five (into {} (map (fn [k] [(+ 20 k) (fish (+ 10.0 (* 0.2 k)))]) (range 5)))
        zombie (mob :zombie [9.5 64.0 8.5] 20.0)
        defend (row :defend)]
    (testing "the line"
      (is (== 10 (combat/danger-line (at {} 20.0))) "none near: retreat-health")
      (is (= 4.0 (combat/danger-line (at {7 (fish 10.5)} 20.0))) "one silverfish: one bite and 3")
      (is (= 8.0 (combat/danger-line (at five 20.0))) "five: five bites and 3")
      (is (= 7.0 (combat/danger-line (at {7 (fish 10.5) 8 zombie} 20.0))) "a zombie's 3 and a bite")
      (is (== 10
              (combat/danger-line
               (at {7 (assoc (mob :enderman [10.5 64.0 8.5] 40.0) :entity/creepy? true)} 20.0)))
          "an angry enderman: 7 and 3, retreat-health")
      (is (== 10 (combat/danger-line (at {7 (mob :creeper [10.5 64.0 8.5] 20.0)} 20.0)))
          "a creeper has no hit to add up: retreat-health"))
    (testing "the choice"
      (is (= {:intent/kind :fight :intent/target 7 :intent/hold? true}
             (plan/next-intent (at {7 (fish 10.5)} 9.3) defend plan/goals))
          "one silverfish at 9.3: fought holding ground, not backed away from")
      (is (= {:intent/kind :back-off :intent/target 7}
             (plan/next-intent (at {7 (fish 10.5)} 3.0) defend plan/goals))
          "under its line: backed away from")
      (is (= :back-off (:intent/kind (plan/next-intent (at five 6.0) defend plan/goals)))
          "five silverfish at 6: under 8, backed away from")
      (is (= :fight
             (:intent/kind (plan/next-intent (at {7 (fish 10.5) 8 zombie} 9.3) defend plan/goals)))
          "a zombie and a silverfish at 9.3: over their 7, fought")
      (is (= {:intent/kind :fight :intent/target 7 :intent/hold? true}
             (plan/next-intent (assoc (at {7 (fish 10.5) 8 zombie} 9.3) :player/attacker 7)
                               defend
                               plan/goals))
          "the silverfish that bit last is answered, not the nearer zombie"))))

(deftest a-block-is-never-laid-in-the-cell-the-target-stands-in
  ;; gym chain-w16 run 3 (plan run 40863839525): `intent :place` three times at 12:12:32-35, each
  ;; `failed: :rejected`, on the cell above the portal room's spawner where its silverfish stood
  (let [fish (mob :silverfish [9.5 64.0 8.5] 8.0)
        w (assoc (standing {7 fish}) :player/health 3.0 :player/food 20)]
    (is (combat/occupied? w [9 64 8]) "the silverfish is in the cell beside the feet")
    (is (not (combat/occupied? w [10 64 8])))
    (is (= [9 65 8] (combat/wall-cell w fish))
        "the feet's height is taken, so the cell over it is the wall")))

(deftest under-the-line-an-angry-enderman-is-covered-against-not-backed-off-from
  ;; gym main dragon-main-a run 1 (plan run 41092662823): fought enderman 105 at 15.7, 12.2, then
  ;; at 9.3 `:dragon-rest` and `:back-off 105` and 104, hits of 4.3 from two of them, 5.6, 1.5, 0
  ;; in 3 s: they teleport, a back-off gets nowhere. The kit held cobblestone, a pickaxe, steak
  (let [kit {0 (item :iron_pickaxe 1) 1 (item :cobblestone 64) 2 (item :cooked_beef 16)}
        angry (assoc (standing {105
                                (assoc (mob :enderman [10.5 64.0 8.5] 40.0) :entity/creepy? true)})
                     :plan/goals #{:dragon}
                     :player/dimension dimension/end
                     :player/health 9.3
                     :player/food 20
                     :player/inventory kit)
        i (plan/next-intent angry (row :defend) plan/goals)]
    (is (some? (combat/teleport-cover angry (get-in angry [:world/entities 105])))
        "it teleports and a cover is held")
    (is (not= :back-off (:intent/kind i)) "no back-off")
    (is (not= :fight (:intent/kind i)) "no fight under the line")
    (is (contains? #{:place :pillar :dig :eat} (:intent/kind i)) "a cover")))

(deftest a-pack-that-lands-retreat-health-in-a-round-is-not-fought
  ;; pearl-fight-a job 6 (run 41143668588): at 18.6 health, :fight 93 against piglins 87 and 93,
  ;; two attackers, 6.7 a hit, 18.6 to 5.2 in 2.4 s and dead at 2m56s. A piglin had no hit on
  ;; record, so the line was retreat-health whatever came with it
  (let [at (fn [entities health] (assoc (standing entities) :player/health health))
        piglin (fn [x] (mob :piglin [x 64.0 8.5] 16.0))
        defend (row :defend)]
    (is (= 10.0 (combat/danger-line (at {7 (piglin 10.5)} 17.6))) "one piglin: its 7 and 3")
    (is
     (= {:intent/kind :fight :intent/target 7}
        (plan/next-intent (assoc (at {7 (piglin 10.5)} 17.6) :player/attacker 7) defend plan/goals))
     "one piglin at 17.6: fought")
    (is (= combat/outmatched-line (combat/danger-line (at {7 (piglin 10.5) 8 (piglin 10.0)} 17.6)))
        "two piglins: over any health")
    (is (= :back-off
           (:intent/kind (plan/next-intent
                          (assoc (at {7 (piglin 10.5) 8 (piglin 10.0)} 20.0) :player/attacker 7)
                          defend
                          plan/goals)))
        "two piglins at full health: backed away from, never fought")
    (is (= 9.0
           (combat/danger-line
            (at {7 (mob :zombie [10.5 64.0 8.5] 20.0) 8 (mob :zombie [10.0 64.0 8.5] 20.0)} 20.0)))
        "two zombies, 6 a round and the margin 3, are not a pack to run from")
    (is (every? some? (map combat/hit-damage [:piglin :hoglin :wither_skeleton])))))

(deftest shooters-are-no-pack-and-the-blaze-row-answers-its-blazes
  ;; two blazes (5 each) add to retreat-health, but they shoot: the pack rule counts melee hits only,
  ;; so the line stays where it was (10), and with the blaze-rods row in play in the Nether :defend
  ;; leaves blazes to it (combat/answering), as on main
  (let [blazes {7 (mob :blaze [10.5 64.0 8.5] 20.0) 8 (mob :blaze [10.0 64.0 9.0] 20.0)}
        w (assoc (standing blazes)
                 :player/dimension dimension/nether
                 :plan/status :active
                 :plan/goals #{:blaze-rods})]
    (is (== 10 (combat/danger-line w)) "two blazes: the old line, not outmatched-line")
    (is (empty? (combat/threats w)) "the blaze row answers blazes")
    (is (nil? (plan/next-intent w (row :defend) plan/goals))
        "nothing chosen by :defend, as on main")
    (is (== 10
            (combat/danger-line (standing {7 (mob :blaze [10.5 64.0 8.5] 20.0)
                                           8 (mob :wither_skeleton [10.0 64.0 9.0] 20.0)})))
        "a blaze with a wither skeleton: one melee hit, no pack")))

(deftest a-cover-does-not-lay-the-wall-cell-that-just-failed
  ;; blaze-race-u job 4: :place failed :no-support three times in 0.15 s, the plan failed
  (let [w {:plan/failed {:intent/kind :place :intent/cell [8 64 8]}}]
    (is (true? (combat/wall-failed? w [8 64 8])))
    (is (false? (combat/wall-failed? w [9 64 8])) "another cell is tried")
    (is (false? (combat/wall-failed? {} [8 64 8])))
    (is (false? (combat/wall-failed? {:plan/failed {:intent/kind :walk :intent/cell [8 64 8]}}
                                     [8 64 8])))
    (is (false? (combat/wall-failed? w nil)))))

(deftest a-cover-does-not-raise-a-pillar-that-just-failed
  ;; blaze-race-w job 4: :pillar failed :boxed three times in 300 ms
  (is (true? (combat/pillar-failed? {:plan/failed {:intent/kind :pillar :intent/reason :boxed}})))
  (is (false? (combat/pillar-failed? {:plan/failed {:intent/kind :place}})))
  (is (false? (combat/pillar-failed? {}))))
