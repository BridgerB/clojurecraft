(ns clojurecraft.pearls-test
  "Ender pearls by hunting endermen (issue #263): the hunt's choices over canned worlds, the provoke tick by tick, the kill's drop, and a hunt against the server model's enderman."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sword :as sword]
            [clojurecraft.world :as world]
            [datascript.core :as d]))

(use-fixtures :once fx/instrumented)

(deftest one-in-reach-is-fought-one-in-sight-provoked-one-hidden-walked-to
  (let [near (ps/standing {7 (ps/enderman [11.5 64.0 8.5])})
        seen (ps/standing {7 (ps/enderman [8.5 64.0 15.5])})
        hidden (ps/standing {7 (ps/enderman [8.5 64.0 15.5])} ps/wall)
        close (ps/standing {7 (ps/enderman [8.5 64.0 13.5])})]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent near ps/row plan/goals)))
    (is (= {:intent/kind :back-off :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc near :player/health 6.0) ps/row plan/goals))
        "under 10 health one at hand is backed away from")
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent seen ps/row plan/goals))
        "seven blocks off with a clear line: a stare brings it")
    (is (= {:intent/kind :walk :intent/target [8 64 15] :intent/for :pearls}
           (plan/next-intent hidden ps/row plan/goals))
        "behind the wall it cannot be stared at: walked to")
    (is
     (= {:intent/kind :walk :intent/target [8 64 13] :intent/for :pearls}
        (plan/next-intent close ps/row plan/goals))
     "five blocks off is too near to stare (within 4 an angry one teleports away), too far to hit")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (ps/standing {}) ps/row plan/goals)))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc near :plan/blacklist #{7}) ps/row plan/goals))
        "a ghost or one that would not come is left")
    (is (=
         {:intent/kind :lookout :intent/for :pearls}
         (plan/next-intent (assoc-in near [:world/entities 7 :entity/died-at] 0) ps/row plan/goals))
        "a dead one is no quarry")))

(deftest a-lookout-that-found-nothing-roams-somewhere-else
  ;; gym batch pearls-263e (plan run 38905343997): forty minutes of looking round from one place
  (let [w (-> (ps/standing {})
              (assoc :time/now 0)
              (assoc-in [:world/chunks [1 0]] (world/column {})))
        {:intent/keys [kind target for]} (plan/next-intent (ps/looked-out w) ps/row plan/goals)]
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals)))
    (is (= [:walk :pearls] [kind for]))
    (is
     (and (>= (first target) 20) (= 64 (second target)))
     "east first at this hour, twelve blocks or more on the floor (sixteen from the point
         behind the feet by the route's own measure, octile and height)")))

(deftest a-four-point-roam-cycle-is-refused-and-engages-tracked-endermen
  ;; gym batch pearls-r3-w4 (plan run 40521602180 at 27cff26) run 1: walked a 4-point
  ;; cycle for 5 hours between [2415 71 2183], [2387 66 2151], [2387 65 2152], and
  ;; [2409 68 2181] ~200 times each with :lookout between them, 0 attacks, 0 hits,
  ;; while endermen were in :world/entities
  (let [p1 [2415 71 2183]
        p2 [2387 66 2151]
        p3 [2387 65 2152]
        p4 [2409 68 2181]
        cycle [p1 p2 p3 p4]
        col (world/column {})
        w0 (-> (ps/standing {})
               (assoc :time/now 0
                      :player/pos [2409.5 68.0 2181.5]
                      :world/chunks (zipmap [[150 136] [149 134] [151 136]] (repeat col))))
        w (reduce (fn [acc [idx pt]]
                    (memory/remember-intention
                     acc
                     {:intent/id (+ 100 idx) :intent/kind :walk :intent/target pt}
                     :done))
                  w0
                  (map-indexed vector cycle))
        e (ps/enderman [2395.5 68.0 2160.5])
        w-with-mob
        (assoc w :world/entities {7 e} :plan/last {:intent/kind :lookout :intent/for :pearls})
        next-intent (plan/next-intent w-with-mob ps/row plan/goals)]
    (is (not= :walk (:intent/kind next-intent))
        "with an enderman tracked, never walks back to a spent roam end")
    (is (contains? #{:stairs-up :stairs-down :tunnel :fight :burrow :lookout}
                   (:intent/kind next-intent))
        "engages tracked enderman via cut or fight")))

(deftest the-provoke-stares-at-the-head-until-the-server-says-it-has-a-target
  (let [pos [8.5 64.0 15.5]
        w (assoc (ps/standing {7 (ps/enderman pos)})
                 :plan/intent
                 {:intent/kind :provoke :intent/target 7 :intent/status :active})
        stared?
        (fn [w]
          (mob/stared? (constantly false) (game/eye w) (:control/look (:player/controls w)) pos))
        [staring _] (fx/fold ps/intent-step w (map ps/tick (range 50 600 50)))
        [turned _] (fx/fold ps/intent-step
                            (assoc-in staring [:world/entities 7 :entity/creepy?] true)
                            [(ps/tick 600)])
        [ignored _] (fx/fold ps/intent-step staring (map ps/tick (range 600 4000 50)))]
    (testing "the stare is vanilla's (EnderMan.isBeingStaredBy, mob/stared?)"
      (is (= :active (:intent/status (:plan/intent staring))))
      (is (stared? staring)))
    (testing "creepy, it is coming: done, the look off its face at once"
      (is (= :done (:intent/status (:plan/intent turned))))
      (is (= (physics/look-at (game/eye turned) (combat/aim (ps/enderman pos)))
             (:control/look (:player/controls turned))))
      (is (not (stared? turned))))
    (is (= [:failed :unprovoked] ((juxt :intent/status :intent/reason) (:plan/intent ignored)))
        "one that never turns is given up")))

(deftest the-hunt-digs-a-burrow-provokes-from-its-mouth-and-fights-from-its-end
  (let [far (ps/enderman [0.5 64.0 8.5])
        coming (assoc far :entity/creepy? true)]
    (is (= {:intent/kind :burrow :intent/dir [0 -1] :intent/for :pearls}
           (plan/next-intent (assoc-in (ps/standing {7 (ps/enderman [8.5 64.0 15.5])})
                              [:player/inventory 1]
                              (ps/item :iron_pickaxe 1))
                             ps/row
                             plan/goals))
        "no burrow, a pickaxe held: one dug away from the enderman, its stairs opening toward it")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (ps/burrowed [8.5 61.0 8.5] {7 coming}) ps/row plan/goals))
        "in the burrow, the one coming is fought where the bot stands")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [-8.5 64.0 8.5])})
                             ps/row
                             plan/goals))
        "none coming: out to the mouth to look round")
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent (ps/burrowed [3.5 64.0 8.5] {7 (ps/enderman [3.5 64.0 0.5])})
                             ps/row
                             plan/goals))
        "from the mouth, one in sight is provoked")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (ps/burrowed
                              [3.5 64.0 8.5]
                              {7 (assoc (ps/enderman [3.5 64.0 0.5]) :entity/creepy? true)})
                             ps/row
                             plan/goals))
        "provoked, it is coming: back to the burrow's end")
    (is
     (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
        (plan/next-intent
         (assoc (ps/burrowed [3.5 64.0 8.5] {7 (ps/enderman [5.5 64.0 8.5])}) :player/health 6.0)
         ps/row
         plan/goals))
     "hurt with one at hand: into the burrow, not backing away in the open (gym batch pearls-263b)")))

(deftest one-at-hand-not-yet-coming-is-met-from-a-burrow-and-a-hurt-bot-rests-in-it
  ;; gym batch pearls-warped-a (plan run 39131478416): two warped forest landings in three walked
  ;; up to a calm enderman, struck it in the open and died; the third, hurt, went from its
  ;; burrow's end to its mouth and back for twelve minutes, its food too low to heal
  (let [near (assoc-in (ps/standing {7 (ps/enderman [11.5 64.0 8.5])})
              [:player/inventory 1]
              (ps/item :iron_pickaxe 1))
        far (ps/enderman [0.5 64.0 8.5])]
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent near ps/row plan/goals))
        "a pickaxe held and one at hand not coming: a burrow away from it, not a blow in the open")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc-in near [:world/entities 7 :entity/creepy?] true)
                             ps/row
                             plan/goals))
        "one at hand already coming is fought: there is no time to dig")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5] {7 far}) :player/health 6.0)
                             ps/row
                             plan/goals))
        "hurt in the burrow with none coming: it stays, not out to the mouth")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39723064504) run 5: pearls from five
    ;; kills, then, its burrow given up and its health at 7, roamed the floor with no enderman
    ;; within 32 and was slain at 36 minutes
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (assoc (ps/standing {7 far}) :player/health 6.0) ps/row plan/goals))
     "hurt with no burrow and none coming: it stands where it is till its health is back, no
         walk or roam in the open")
    (is (= :eat
           (:intent/kind (plan/next-intent (-> (ps/burrowed [8.5 61.0 8.5] {7 far})
                                               (assoc :player/health 6.0 :player/food 15)
                                               (assoc-in [:player/inventory 2]
                                                         (ps/item :cooked_beef 8)))
                                           ps/row
                                           plan/goals)))
        "hurt, its food under 18 (where health stops coming back): a meal")))

(deftest a-bot-under-one-hit-plus-a-margin-never-fights-on
  ;; issue #563, probe 40022662598: after the replacement stone sword was crafted, the hunt
  ;; backed away from an angry enderman and then fought it again (the angry one within
  ;; stare-range was fought wherever it stood), again and again down to 1.3 health: the next
  ;; hit (7) would have killed. danger-health (10) is that hit plus a margin
  (let [angry-far (assoc (ps/enderman [8.5 64.0 20.5]) :entity/creepy? true)
        angry-near (assoc (ps/enderman [11.5 64.0 8.5]) :entity/creepy? true)]
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (assoc (ps/standing {7 angry-far}) :player/health 6.0) ps/row plan/goals))
     "an angry one twelve blocks off at 6 health: rested where it stands, not fought")
    (is (= {:intent/kind :back-off :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc (ps/standing {7 angry-near}) :player/health 6.0)
                             ps/row
                             plan/goals))
        "one at hand at 6 health: backed away from")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5]
                                                 {7 (assoc angry-far :entity/pos [5.5 61.0 8.5])})
                                    :player/health
                                    6.0)
                             ps/row
                             plan/goals))
        "in the burrow at 6 health: rest in cover, the held fight not started")
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (assoc (ps/standing {7 angry-far}) :player/health 9.9) ps/row plan/goals))
     "just under danger-health: still no fight")
    (is (= :fight
           (:intent/kind (plan/next-intent (assoc (ps/standing {7 angry-far}) :player/health 20.0)
                                           ps/row
                                           plan/goals)))
        "at full health the angry one is still met")
    (let [zombie {:entity/type (blocks/entity-types :zombie)
                  :entity/pos [11.5 64.0 8.5]
                  :entity/seen-at 0
                  :entity/health 20.0}]
      (is (=
           {:intent/kind :back-off :intent/target 8 :intent/for :pearls}
           (plan/next-intent (assoc (ps/standing {8 zombie}) :player/health 6.0) ps/row plan/goals))
          "a zombie at hand at 6 health: backed away from, not rested beside"))))

(deftest an-angry-one-given-up-on-is-met-from-the-burrow-when-it-comes
  ;; gym batch pearls-warped-b (plan run 39135767198): a held fight timed out :unreachable, its
  ;; enderman was blacklisted while still angry, and two bots in three walked out to the mouth
  ;; and were slain; the third's run 2 went from the end to the mouth and back for two minutes
  ;; while a calm one stood beside the mouth. Batch pearls-warped-d (plan run 39160760074) run 1:
  ;; three pearls in six minutes, then thirty-four in the burrow over such ones standing off
  (let [angry (assoc (ps/enderman [3.5 64.0 20.5]) :entity/creepy? true)
        given-up (assoc (ps/burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is
     (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent given-up ps/row plan/goals))
     "in the burrow, the one given up on angry twelve off the mouth, within its stare range of
         16 (danger-radius): kept in")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (ps/looked given-up 6) ps/row plan/goals))
        "half a minute of it not coming: out to the mouth to lure it")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (assoc-in given-up [:world/entities 7 :entity/pos] [3.5 64.0 26.5])
                             ps/row
                             plan/goals))
        "eighteen off, past its stare range: out to the mouth at once, as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc (ps/burrowed [3.5 64.0 8.5]
                                                 {7 (assoc angry :entity/pos [5.5 64.0 10.5])})
                                    :plan/blacklist
                                    #{7})
                             ps/row
                             plan/goals))
        "at the mouth with it at hand: back in")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5]
                                                 {7 (assoc angry :entity/pos [5.5 61.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             ps/row
                             plan/goals))
        "at the end with it on the stairs: fought")
    ;; sim probe 39890029962 (worlds R1 and R4, seed 3): three held fights in a row on one by the
    ;; mouth that never came, each :unreachable, and the plan failed
    (let [by-mouth (ps/burrowed [8.5 61.0 8.5] {7 (assoc angry :entity/pos [5.5 64.0 8.5])})]
      (is (= {:intent/kind :fight
              :intent/target 7
              :intent/hold? true
              :intent/yield? true
              :intent/for :pearls}
             (plan/next-intent by-mouth ps/row plan/goals))
          "at hand by the mouth, not given up on: the fight held")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (assoc by-mouth :plan/blacklist #{7}) ps/row plan/goals))
          "given up on, out of the sword's reach: no fight held again, the bot stays in"))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5]
                                                 {7 (assoc angry :entity/pos [1.5 64.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             ps/row
                             plan/goals))
        "at the end with it two off the mouth: a crowd, blacklisted or not")
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [0.5 64.0 8.5])})
                          ps/row
                          plan/goals))
     "a calm one three blocks from the mouth: no vantage out past it (a vantage is only within
         provoke-home-reach of the burrow's end, gym plan run 40860669235 job 4): the bot stays in")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [3.5 44.0 8.5])})
                             ps/row
                             plan/goals))
        "a calm one under the mouth, twenty below: no crowd")
    ;; batch pearls-warped-c (plan run 39152813557, probe 39157605071) run 1: forty minutes in the
    ;; burrow over one angry, blacklisted and seventeen blocks below the mouth, in another cave
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5]
                                                 {7 (assoc angry :entity/pos [8.5 47.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             ps/row
                             plan/goals))
        "an angry one far below the mouth cannot come to it: out to the mouth")
    ;; batch pearls-warped6-a (plan run 39687964414, probe 39699149771) run 4: one a held fight
    ;; gave up on stood 3 to 9 blocks off the mouth and 5 or 6 below it on the slope beside, and
    ;; came the moment the bot stepped out, three times, the third its death at 597 s
    (let [below (assoc angry :entity/pos [0.5 58.0 8.5])]
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (assoc (ps/burrowed [8.5 61.0 8.5] {7 below}) :plan/blacklist #{7})
                               ps/row
                               plan/goals))
          "an angry one six below the mouth and three off it, blacklisted: the bot stays in")
      (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
             (plan/next-intent (assoc (ps/burrowed [3.5 64.0 8.5] {7 below}) :plan/blacklist #{7})
                               ps/row
                               plan/goals))
          "at the mouth with it there: home"))))

(deftest one-coming-with-no-way-home-is-fought-not-stood-under
  ;; gym batch pearls-warped8-b (plan run 39775345028, probe 39848848462) run 3: out at its
  ;; burrow's first stair, the walk home failed :no-path, and the bot stood in :lookout while the
  ;; angry enderman beside it took its health from 19.4 to nothing in three seconds
  (let [angry (assoc (ps/enderman [5.5 64.0 10.5]) :entity/creepy? true)
        cut-off {[7 62 8] ps/stone [7 61 8] ps/stone}
        w (assoc (ps/burrowed [3.5 64.0 8.5] {7 angry} cut-off) :plan/blacklist #{7})]
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc (ps/burrowed [3.5 64.0 8.5] {7 angry}) :plan/blacklist #{7})
                             ps/row
                             plan/goals))
        "a way home: home")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "none: fought where it stands")))

(deftest a-spent-pickaxe-is-made-again-from-a-warped-stem
  ;; gym batch pearls-warped9-a (plan run 39898400239) run 2: 239 digs spent on five pearls,
  ;; eleven left, then four hours of lookouts with no burrow to cut: netherrack makes no
  ;; pickaxe, but the warped forest's own stems craft to planks as a log does
  (let [stem (first (blocks/states-of :warped_stem))
        trunk {[12 64 8] stem
               [12 65 8] stem
               [12 66 8] stem
               [12 67 8] (blocks/default-state :warped_wart_block)}
        col (world/column trunk)
        spent (memory/remember-intention (assoc (ps/standing {}) :time/now 0)
                                         {:intent/id 1 :intent/kind :burrow}
                                         :done)
        forest (-> spent
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        worn
        (assoc-in forest [:player/inventory 1] (assoc (ps/item :iron_pickaxe 1) :durability 11))
        stems (assoc worn :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :warped_stem 2)})
        fresh (assoc-in forest [:player/inventory 1] (ps/item :iron_pickaxe 1))
        walked (plan/next-intent forest ps/row plan/goals)]
    (is (= {:plan/wait :no-pickaxe} (plan/next-intent spent ps/row plan/goals))
        "no trunk remembered and nothing held: the wait, as before")
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "a remembered stem is the trunk the new pickaxe takes")
    (is (= :log (:intent/for (plan/next-intent worn ps/row plan/goals)))
        "eleven digs left: the next pickaxe is made before this one breaks")
    (is (= :craft (:intent/kind (plan/next-intent stems ps/row plan/goals)))
        "stems held: crafted to planks, not another trunk walked to")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent fresh ps/row plan/goals))
        "a fresh pickaxe held: the hunt, no remake")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc forest :player/health 6.0) ps/row plan/goals))
        "under danger-health the craft waits too: rest first")))

(deftest empty-tunnel-that-makes-no-progress-fails-boxed
  ;; reviewer finding 1: :tunnel that makes no progress must not repeat forever
  (let [tunnel {:intent/kind :tunnel
                :intent/dir [1 0]
                :intent/length 1
                :intent/level-steps 1
                :intent/from-feet [8 64 8]
                :intent/status :active}
        w (assoc (ps/standing {}) :plan/intent tunnel)
        stepped (intent/run w tunnel (ps/tick 100))
        failed (plan/fail-intent stepped (:plan/intent stepped))]
    (is (= :boxed (:intent/reason (:plan/intent stepped)))
        "tunnel that makes no progress fails :boxed when finished")
    (is (contains? (:plan/boxed-at failed) [8 64 8])
        "feet are recorded in boxed-at so the empty cut is never repeated")))

(deftest start-intent-clears-stale-waiting-reason
  ;; reviewer finding 2: logged :lookout 2,377 times with :waiting :no-sword
  ;; while holding wooden_sword; start-intent must clear :plan/waiting
  (let [w (assoc (ps/standing {})
                 :plan/waiting :no-sword
                 :plan/waiting-ticks 5
                 :plan/waiting-since 1000)
        started (plan/start-intent w {:intent/kind :lookout :intent/for :pearls})]
    (is (nil? (:plan/waiting started)) "start-intent clears :plan/waiting")
    (is (nil? (:plan/waiting-ticks started)))
    (is (nil? (:plan/waiting-since started)))))

(deftest wooden-sword-counts-for-hunt-and-overworld-does-not-descend-into-caves
  ;; reviewer finding 2: after death in overworld, holding wooden_sword and wooden_pickaxe,
  ;; wooden sword counts for hunt, and overworld hunt roams surface instead of digging down
  ;; into dark caves toward underground endermen
  (let [underground-enderman (ps/enderman [8.5 20.0 8.5])
        w (-> (ps/standing {7 underground-enderman})
              (assoc :player/dimension "minecraft:overworld"
                     :player/inventory
                     {0 (assoc (ps/item :wooden_sword 1) :durability 20)
                      1 (ps/item :wooden_pickaxe 1)}
                     :stats/deaths 1
                     :plan/last {:intent/kind :lookout :intent/for :pearls}))]
    (is (= :wooden_sword (sword/serviceable w)) "wooden sword is serviceable")
    (let [next (plan/next-intent w ps/row plan/goals)]
      (is (not= :stairs-down (:intent/kind next)) "does not descend into underground cave")
      (is (not= :tunnel (:intent/kind next)) "does not bore underground")
      (is (not= {:plan/wait :no-sword} next) "never waits :no-sword after death"))))

(deftest never-fight-under-danger-health
  ;; prompt requirement & #577: never fight under danger-health
  (let [creepy (assoc (ps/enderman [12.5 64.0 8.5]) :entity/creepy? true)
        w (assoc (ps/standing {7 creepy})
                 :player/health 5.0
                 :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :diamond_pickaxe 1)})
        next (plan/next-intent w ps/row plan/goals)]
    (is (not= :fight (:intent/kind next)) "never starts a fight under danger-health")
    (is (= :back-off (:intent/kind next)) "backs off from angry enderman when hurt")))

(deftest after-the-sword-is-remade-the-pickaxe-fetches-its-own-log
  ;; gym batch pearls-r3-w2 (plan run 40475390313) run 3: slain an hour and a half in
  ;; with pearls held, it respawned at the overworld spawn, chopped the spruce beside
  ;; it, remade its wooden sword, dug its table up and collected it, and then failed
  ;; :no-pickaxe with the sword, the table and three sticks held and the trunk's upper
  ;; logs standing: no intent of the pickaxe's log chain was ever taken after the collect
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        respawned
        (-> (ps/standing {})
            (assoc :world/chunks {[0 0] col}
                   :player/inventory
                   {0 (ps/item :wooden_sword 1) 1 (ps/item :stick 3) 2 (ps/item :crafting_table 1)}
                   :time/now 0
                   :stats/deaths 1
                   :plan/last
                   {:intent/kind :collect
                    :intent/target [12 64 8]
                    :intent/for :take-table
                    :intent/status :done})
            (memory/remember-column [0 0] col)
            (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        next (plan/next-intent respawned ps/row plan/goals)]
    (is
     (= [:walk :log] ((juxt :intent/kind :intent/for) next))
     "sword and table held, no plank for the pickaxe: out to the trunk again, never
            the :no-pickaxe wait")))

(deftest empty-hands-at-a-treeless-spawn-look-for-wood-never-fail-no-sword
  ;; gym batch pearls-r3-w2 (plan run 40475390313) run 2: slain in the forest at three
  ;; minutes, it respawned at an overworld spawn with no log in the chunks first loaded
  ;; and failed :no-sword at five, where batches pearls-w1 to w3's respawns had spruces
  ;; in sight and remade theirs
  (let [bare (assoc (ps/standing {}) :player/inventory {})
        respawned (assoc bare :stats/deaths 1)]
    (is (= {:plan/wait :no-sword} (plan/next-intent bare ps/row plan/goals))
        "never died, no sword and no trunk: the wait stands, no bare-hand hunt")
    (is
     (= {:intent/kind :walk :intent/target [14 64 14] :intent/for :pearls}
        (plan/next-intent respawned ps/row plan/goals))
     "a death behind it and no trunk sighted yet: it roams for wood (a :lookout with
            nowhere to roam), and the remake is asked again, never the :no-sword fail")
    (is (= {:plan/wait :no-sword}
           (plan/next-intent (assoc respawned :player/dimension "minecraft:the_nether")
                             ps/row
                             plan/goals))
        "in the Nether there is no wood to look for: the wait stands")))

(deftest no-sword-held-no-hunt
  ;; gym batch pearls-warped8-b (plan run 39775345028): runs 1 and 3 respawned with nothing held
  ;; after their first deaths and hunted on bare-handed, 39 and 26 deaths in five hours
  (let [w (ps/standing {7 (ps/enderman [8.5 64.0 20.5])})]
    (is (not= {:plan/wait :no-sword} (plan/next-intent w ps/row plan/goals)))
    (is (= {:plan/wait :no-sword}
           (plan/next-intent (update w :player/inventory dissoc 0) ps/row plan/goals)))))

(deftest a-broken-sword-is-made-when-the-craft-can-start
  ;; sim race 39909643851 (issue #563) world R3/13 seed 2: the stone sword broke with cobblestone
  ;; and a stick held and a trunk two blocks off, and the plan failed :no-sword
  (let [held {1 (ps/item :cobblestone 5) 2 (ps/item :stick 1)}
        bare (assoc (ps/standing {}) :player/inventory held)
        table (assoc bare :player/inventory (assoc held 3 (ps/item :crafting_table 1)))
        trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        forest (-> bare
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        placed (plan/next-intent table ps/row plan/goals)
        walked (plan/next-intent forest ps/row plan/goals)]
    (is (= {:plan/wait :no-sword} (plan/next-intent bare ps/row plan/goals))
        "cobblestone and a stick, no table and no trunk: still no bare-hand hunt")
    (is (= :place (:intent/kind placed)))
    (is (= :crafting_table (:intent/item placed)) "a held table goes down for the stone sword")
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "a remembered trunk is the log the table takes")))

(deftest empty-hands-make-a-wooden-sword-from-a-remembered-trunk
  ;; gym batches pearls-m10-a and pearls-m10-c (plan runs 40106206590, 40133706786): every
  ;; death respawned with nothing held and the plan failed :no-sword at once, though the
  ;; wood of a wooden sword stood remembered round the spawn
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        bare (-> (ps/standing {})
                 (assoc :player/inventory {} :world/chunks {[0 0] col})
                 (memory/remember-column [0 0] col))
        walked (plan/next-intent bare ps/row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "nothing held: the trunk first, never the :no-sword wait")))

(deftest a-broken-sword-is-made-of-warped-stems
  ;; gym batch pearls-m10-a (plan run 40106206590) run 2: six pearls and twenty-five warped
  ;; stems held when its iron sword broke, and the plan failed :no-sword: sword/payable
  ;; counts planks, and none were held
  (let [bare (assoc (ps/standing {}) :player/inventory {1 (ps/item :warped_stem 2)})]
    (is (= :craft (:intent/kind (plan/next-intent bare ps/row plan/goals)))
        "stems held and no sword: a wooden one is crafted from them")))

(deftest a-worn-sword-is-replaced-before-the-hunt
  ;; the same race held the stone sword with nine swings left and fought until it broke
  (let [worn (assoc (ps/item :stone_sword 1) :durability 9)
        held {0 worn 1 (ps/item :cobblestone 5) 2 (ps/item :stick 1)}
        bare (assoc (ps/standing {}) :player/inventory held)
        trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        forest (-> bare
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        laid (-> forest
                 (assoc :window/grid {1 (ps/item :oak_log 1)})
                 (update :player/inventory dissoc 0))
        walked (plan/next-intent forest ps/row plan/goals)
        crafted (plan/next-intent laid ps/row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "nine swings left: the trunk, before the fight")
    (is (= :craft (:intent/kind crafted)) "a log in the grid is crafted, not another trunk")
    (is (not= :log (:intent/for crafted)))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc forest :player/health 6.0) ps/row plan/goals))
        "under danger-health the craft waits too: rest first, no fight on one hit")))

(deftest twelve-pearls-held-the-stage-is-done
  (is (plan/done-by (assoc (ps/standing {}) :player/inventory {3 (ps/item :ender_pearl 12)})
                    ps/row))
  (is (not (plan/done-by (assoc (ps/standing {}) :player/inventory {3 (ps/item :ender_pearl 11)})
                         ps/row))))

(deftest an-enderman-that-hit-the-bot-near-its-burrow-is-the-hunts-not-defends
  ;; churn-a pearls-12 run 3, enderman-end-b runs 1 and 2 (plan runs 41096292443 and 41103285823):
  ;; provoked from the mouth, hit once on the way in (20 to 14.3), and 0.05 s later the :defend row
  ;; began a fight to the end where the bot stood, 2 to 4 blocks from the burrow's end: four hits
  ;; in 3 to 5 s. The hit enderman is the hunt's, whose first branch walks home.
  (let [angry (assoc (ps/enderman [4.5 64.0 8.5]) :entity/creepy? true)
        hit (fn [w] (assoc w :player/attacker 9 :player/health 14.26))
        at-mouth (hit (ps/burrowed [3.5 64.0 8.5] {9 angry}))
        no-burrow (dissoc at-mouth :pearls/shelter :pearls/mouth)]
    (is (empty? (combat/threats at-mouth)) "the attacker is not :defend's with a burrow to walk to")
    (is (not= :defend
              (:goal/id (plan/choose (assoc at-mouth :plan/goals #{:ender-pearls}) plan/goals)))
        "the :defend row is done, so the hunt's walk home is not cut")
    (is (= :defend
           (:goal/id (plan/choose (assoc no-burrow :plan/goals #{:ender-pearls}) plan/goals)))
        "and with no burrow it is :defend's turn as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent at-mouth ps/row plan/goals))
        "and it walks home")
    (is (= [9] (keys (combat/threats no-burrow)))
        "with no burrow near the attacker is :defend's as before")
    (is (empty? (combat/threats (hit (ps/burrowed [8.5 61.0 8.5]
                                                  {9 (assoc angry :entity/pos [5.5 61.0 8.5])}))))
        "in the burrow's end it stays the hunt's: a held fight")))

(deftest a-cornered-bot-under-danger-health-eats-or-walls-off
  ;; gym plan run 40747177526 run 1 (issue #263, pearls-12): 7.6 health, an angry enderman a block
  ;; or two off, back-off ten seconds at a time for ninety seconds with the bot backing into a
  ;; wall, forty-eight cooked beef and many blocks held; slain by the next two hits
  (let [angry (assoc (ps/enderman [10.5 64.0 8.5]) :entity/creepy? true)
        held {0 (ps/item :iron_sword 1) 1 (ps/item :cobblestone 64)}
        w (-> (ps/standing {7 angry})
              (assoc :player/health 7.6 :player/inventory held :plan/cornered #{7}))]
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [9 64 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent w ps/row plan/goals))
        "a block at the feet's level on the side it stands on")
    (is (= [9 65 8]
           (:intent/cell
            (plan/next-intent
             (assoc w :world/chunks (:world/chunks (ps/standing {7 angry} {[9 64 8] ps/stone})))
             ps/row
             plan/goals)))
        "then one over it")
    (is (= :eat
           (:intent/kind
            (plan/next-intent
             (assoc w :player/food 12 :player/inventory (assoc held 2 (ps/item :cooked_beef 8)))
             ps/row
             plan/goals)))
        "hungry with food held: the meal first")
    (is (= :back-off
           (:intent/kind (plan/next-intent (assoc w :plan/cornered #{}) ps/row plan/goals)))
        "not cornered: backed off from as before")
    (is (plan/interrupts? w ps/row {:intent/kind :walk :intent/target [1 64 1] :intent/for :pearls})
        "an angry one at hand cuts a walk")
    (is (not (plan/interrupts? w ps/row (plan/next-intent w ps/row plan/goals)))
        "but not the wall")))

(deftest a-lookout-is-cut-only-when-the-hunt-would-do-something-else
  ;; run 2 of the same plan run ended in 50,301 lookouts 75 ms apart: an angry enderman at hand
  ;; cut each at once and the hunt chose another lookout
  (let [angry (assoc (ps/enderman [10.5 64.0 8.5]) :entity/creepy? true)
        w (ps/standing {7 angry})
        lookout {:intent/kind :lookout :intent/for :pearls :intent/goal :ender-pearls}]
    (is (psense/beset? w))
    (is (plan/interrupts? w ps/row lookout) "the hunt fights it: cut")
    (is (pearls/same-choice? lookout {:intent/kind :lookout :intent/for :pearls})
        "the hunt would lookout again: left to stand")
    (is (not (pearls/same-choice? lookout {:intent/kind :fight :intent/target 7})))))

(deftest a-held-fight-with-nothing-coming-is-cut-after-twelve-seconds
  ;; gym plan run 40825468783 runs 4 and 5 (issue #263): :fight 148 held from 06:51:41 to
  ;; 06:52:41, failing :unreachable after combat/hold-timeout-ms, the enderman never in reach
  (let [far (assoc (ps/enderman [8.5 64.0 30.5]) :entity/creepy? true)
        fight {:intent/kind :fight
               :intent/target 7
               :intent/hold? true
               :intent/since 1000
               :intent/status :active
               :intent/goal :ender-pearls
               :intent/for :pearls}
        at (fn [w ms] (assoc w :time/now ms))
        w (ps/standing {7 far})]
    (is (not (pearls/held-idle? (at w (+ 1000 pparams/hold-patience-ms)) fight)) "not yet")
    (is (pearls/held-idle? (at w (+ 1001 pparams/hold-patience-ms)) fight) "then cut")
    (is (not (intent/still-wanted? (at w (+ 1001 pparams/hold-patience-ms))
                                   (intent/ranked :hunt/fight-from-burrow fight)))
        "a held fight gone idle is no longer wanted")
    (is (intent/still-wanted? (at w 1500) (intent/ranked :hunt/fight-from-burrow fight)))
    (is (not (pearls/held-idle? (at w 99999) (dissoc fight :intent/hold?))) "a fight that walks up")
    (is (not (pearls/held-idle? (at (ps/standing {7 (assoc far :entity/pos [10.5 64.0 8.5])}) 99999)
                                fight))
        "its target within strike-range: the fight goes on")
    (testing "and it is not planned again for a while"
      (let [cut (memory/remember-intention (at w 20000) (assoc fight :intent/id 5) :abandoned)]
        (is (psense/unfightable? cut 7 far))))))

(deftest a-swarmed-hunt-goes-home-seals-the-mouth-and-opens-it-when-they-are-gone
  (let [held {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1) 2 (ps/item :cobblestone 64)}
        outside (-> (ps/burrowed [3.5 64.0 2.5] ps/burst)
                    (assoc :player/inventory held))
        inside (-> (ps/burrowed [8.5 61.0 8.5] ps/burst)
                   (assoc :player/inventory held))]
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent outside ps/row plan/goals))
        "out in the open: home")
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [7 61 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent inside ps/row plan/goals))
        "in the burrow's end: one block between the bot and the mouth")
    (testing "the feet sealed, the head's level is next: a crossbow shoots over a block"
      (let [feet-sealed (-> (ps/burrowed [8.5 61.0 8.5] ps/burst {[7 61 8] ps/stone})
                            (assoc :player/inventory held))]
        (is (= [7 62 8] (:intent/cell (plan/next-intent feet-sealed ps/row plan/goals))))))
    (testing "sealed at both, it waits and does not fight"
      (let [sealed (-> (ps/burrowed [8.5 61.0 8.5] ps/burst {[7 61 8] ps/stone [7 62 8] ps/stone})
                       (assoc :player/inventory held))]
        (is (= {:intent/kind :lookout :intent/for :pearls}
               (plan/next-intent sealed ps/row plan/goals)))))
    (testing "they gone, the seal is dug"
      (let [gone (-> (ps/burrowed [8.5 61.0 8.5] {} {[7 61 8] ps/stone})
                     (assoc :player/inventory held))]
        (is (= {:intent/kind :dig :intent/target [7 61 8] :intent/for :pearls}
               (plan/next-intent gone ps/row plan/goals)))))))

(deftest the-hunt-does-not-walk-out-to-a-mouth-a-monster-stands-at
  ;; gym plan run 40825468783 run 4 (issue #263, pearls-12): the walk between the burrow's end and
  ;; its mouth every ~2 s, forty-eight times in 3m40, a magma cube at the mouth landing seven hits
  ;; of three each, 20 health to 3.7: home when it was near, out to the mouth when it was not
  (let [held {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}
        end [8.5 61.0 8.5]
        clear (assoc (ps/burrowed end {}) :player/inventory held)
        cube
        (assoc (ps/burrowed end {7 (ps/mob :magma_cube [3.5 64.0 8.5])}) :player/inventory held)]
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent clear ps/row plan/goals))
        "nothing at the mouth: out to look round from it")
    (is (not= [:walk [3 64 8]]
              ((juxt :intent/kind :intent/target) (plan/next-intent cube ps/row plan/goals)))
        "a magma cube at the mouth: the walk is not planned")))

(deftest a-zombie-at-hand-is-fought-at-full-health-and-never-looked-out-from
  ;; the same plan run, after the death (overworld, zombie 4548): :fight, then :lookout and
  ;; :back-off alternating every 0.4 to 2 s for 16 s, 86 back-off ticks, then death
  (let [zombie (ps/mob :zombie [11.5 64.0 8.5])
        w (ps/standing {7 zombie})
        lookout {:intent/kind :lookout :intent/for :pearls :intent/goal :ender-pearls}]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "at full health with no burrow: fought")
    (is (= :back-off
           (:intent/kind (plan/next-intent (assoc w :player/health 8.0) ps/row plan/goals)))
        "hurt: backed away from")
    (is (plan/interrupts? w ps/row lookout) "a lookout does not run with it at hand")))

(deftest an-uncovered-ghast-is-logged-once-in-five-seconds
  ;; the open-ground case: nothing to lay a block against, nothing to dig, no cell out of the line
  (let [held {0 (ps/item :iron_sword 1)}
        w (-> (ps/wide (ps/standing {7 (ps/mob :ghast [8.5 75.0 50.5])}))
              (assoc :player/inventory held :time/tick 0))
        logged (fn [w] (filter #(= :log (:effect/kind %)) (:bot/effects (pearls/uncovered-log w))))]
    (is (psense/shot-at? w))
    (is (nil? (pcover/cover-from w)))
    (is (= 1 (count (logged w))))
    (is (re-find #"ghast 7 at \[8 75 50\] shoots the bot at \[8 64 8\]: no cover"
                 (:effect/message (first (logged w)))))
    (is (empty? (logged (assoc w :time/tick 1))) "not every tick")))

(deftest a-ghast-in-sight-is-covered-from-before-the-walk-home-and-a-walk-is-not-cut-for-itself
  ;; gym plan run 40860669235 job 5 (issue #263, pearls-12): five fireballs took 20 health to 0
  ;; while the same :walk was planned every 50 ms, each plan cut by the one before it, and no
  ;; cover was ever chosen (a burrow was cut, so the walk home came first)
  (let [held {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1) 2 (ps/item :cobblestone 64)}
        w (-> (ps/burrowed [3.5 64.0 8.5] {7 (ps/mob :ghast [30.5 75.0 8.5])} {[4 64 8] ps/stone})
              ps/wide
              (assoc :player/inventory held))
        walk {:intent/kind :walk
              :intent/target [3 64 8]
              :intent/status :active
              :intent/goal :ender-pearls
              :intent/for :shelter}]
    (is (psense/shot-at? w))
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [4 65 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent w ps/row plan/goals))
        "cover first, not the walk home")
    (testing "a walk the hunt would plan again is not cut"
      (is (pearls/same-choice? walk (assoc walk :intent/status :new :intent/id 9)))
      (is (not (pearls/same-choice? walk {:intent/kind :lookout})))
      (is (not (pearls/same-choice? walk (assoc walk :intent/target [4 64 8])))))))

(deftest a-chunk-of-warped-nylium-is-a-forest-fact
  (let [nylium (blocks/default-state :warped_nylium)
        col (fn [n] (world/column (into {} (for [x (range n)] [[x 64 3] nylium]))))
        fact (fn [n dim] (memory/forest-fact [2 -1] (col n) 77 dim))]
    (is (= {:forest/chunk [2 -1]
            :forest/kind :warped
            :forest/count 12
            :forest/pos [40 64 -13]
            :forest/at 77
            :forest/dimension ps/nether}
           (select-keys (fact memory/forest-min ps/nether)
                        [:forest/chunk :forest/kind :forest/count :forest/pos :forest/at
                         :forest/dimension]))
        "the block nearest the chunk's centre (40, -8), twelve of them")
    (is (nil? (fact (dec memory/forest-min) ps/nether)) "eleven: no forest")
    (is (nil? (fact memory/forest-min "minecraft:overworld")) "only the Nether has them")
    (testing "remembered on load, known after, and the newest per chunk"
      (let [w (-> (game/init fx/opts)
                  (assoc :player/dimension ps/nether :time/now 5)
                  (memory/remember-column [2 -1] (col 12))
                  (memory/remember-column [2 -1] (col 12))
                  (memory/remember-column [2 -1] (col 14)))]
        (is (= [[[2 -1] 14]] (map (juxt :forest/chunk :forest/count) (memory/warped-chunks w))))
        (is (= 2 (count (d/q '[:find [?e ...] :where [?e :forest/chunk]] (:world/facts w))))
            "an unchanged reload adds no fact")))))

(deftest no-pearl-for-eight-minutes-relocates-the-hunt-and-leaves-the-burrow-behind
  ;; pearl-fight-e job 4 (plan run 41157320074): one pearl in 102 minutes, 943 lookouts and 26
  ;; burrows beside an angry enderman parked at 8 blocks and calm ones at 7 to 9, others at 52, 53
  ;; and 63. Production as data: no pearl gained in relocate-ms starts a relocation, however the
  ;; lookouts and walks count (wandering? was false throughout)
  (let [w (-> (ps/standing {})
              (assoc :time/now 0)
              pearls/track-gain)
        at (fn [ms]
             (-> w
                 (assoc :time/now ms)
                 pearls/track-gain
                 pearls/relocation))]
    (is (= {:held 0 :at 0} (:pearls/gain w)) "the clock starts with the first tick")
    (is (nil? (:pearls/relocate (at (dec pparams/relocate-ms))))
        "seven minutes fifty-nine: not yet")
    (is (= {:from [8 64 8] :at pparams/relocate-ms :near #{}}
           (:pearls/relocate (at pparams/relocate-ms)))
        "eight minutes without a pearl")
    (testing "a pearl gained moves the clock on"
      (let [got (-> (at 400000)
                    (assoc-in [:player/inventory 5] (ps/item :ender_pearl 1))
                    (assoc :time/now 500000)
                    pearls/track-gain
                    pearls/relocation)]
        (is (= {:held 1 :at 500000} (:pearls/gain got)))
        (is (nil? (:pearls/relocate got)))))
    (testing "thirty-two blocks on, it is over: the burrow and the blacklist are left behind"
      (let [begun (-> (at pparams/relocate-ms)
                      (assoc :pearls/shelter [8 61 8] :pearls/mouth [3 64 8] :plan/blacklist #{9}))
            there (-> begun
                      (assoc :player/pos [48.5 64.0 8.5] :time/now 600000)
                      pearls/relocation)]
        (is (nil? (:pearls/relocate there)))
        (is (nil? (:pearls/shelter there)))
        (is (nil? (:plan/blacklist there)))
        (is (= 600000 (get-in there [:pearls/gain :at])) "and the clock starts again")))))

(deftest a-relocation-ends-short-of-the-endermen-it-walks-toward
  ;; pearl-fight-g job 4 (plan run 41166403154): the walk went the whole way to the cluster and was
  ;; slain in the open fifteen blocks from a calm one. It ends half the distance on, within
  ;; cluster-radius of an enderman that was not near the start, and the running walk is cut
  (let [calm (ps/enderman [40.5 64.0 8.5])
        base (-> (ps/standing {1 calm})
                 (assoc :time/now 600000
                        :pearls/shelter [8 61 8]
                        :pearls/relocate {:from [8 64 8] :at 500000 :near #{}}))
        arrived (assoc base :player/pos [24.5 64.0 8.5])
        walk
        {:intent/kind :walk :intent/target [40 64 8] :intent/for :relocate :intent/status :active}]
    (is (some? (:pearls/relocate (pearls/relocation (assoc base :player/pos [14.5 64.0 8.5]))))
        "6 blocks on: still going")
    (is (nil? (:pearls/relocate (pearls/relocation arrived))) "16 blocks on and 16 from it: over")
    (is (nil? (:pearls/shelter (pearls/relocation arrived))) "the old burrow left behind")
    (is (some? (:pearls/relocate (pearls/relocation
                                  (assoc-in arrived [:pearls/relocate :near] #{1}))))
        "an enderman that was near the start is not a reason to stop")
    (is (not (intent/still-wanted? (pearls/relocation arrived) (intent/ranked :hunt/relocate walk)))
        "the walk is cut once it is over")
    (is (intent/still-wanted? base (intent/ranked :hunt/relocate walk))
        "and not while it goes on")))

(deftest a-relocation-waits-for-an-angry-one-near-then-walks-past-a-parked-one
  ;; pearl-fight-h job 3 (plan run 41173013374): at 9m43s with no pearl, the relocation walked out
  ;; of the burrow at 8 minutes past enderman 279, angry and fought all minute, and was slain in
  ;; four hits. For relocate-patience-ms any angry one within sixteen holds the walk; after that
  ;; only a live one does (a parked one held the g job 3 bot 90 minutes)
  (let [angry (assoc (ps/enderman [17.0 64.0 8.5]) :entity/creepy? true)
        group [(ps/enderman [50.5 64.0 8.5]) (ps/enderman [52.5 64.0 12.5])
               (ps/enderman [48.5 64.0 4.5])]
        w (-> (ps/wide (ps/standing {1 angry 3 (nth group 0) 4 (nth group 1) 5 (nth group 2)}))
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :pearls/angry {1 {:since 0 :best 8.5 :closed-at 0}}
                     :pearls/gain {:held 1 :at 0}
                     :pearls/relocate {:from [8 64 8] :at 0 :near #{1}}))
        kind (fn [now] (:intent/for (plan/next-intent (assoc w :time/now now) ps/row plan/goals)))]
    (is (not= :relocate (kind 100000)) "in the patience: it waits")
    (is (= :relocate (kind 200000)) "past it, with the angry one parked: it walks")))

(deftest ^:eftest/synchronized a-remembered-table-no-route-reaches-is-replaced-not-walked-at
  ;; pearl-fight-i job 1 (plan run 41178660163): 28 digs left in a rock pocket, six warped stems,
  ;; four sticks and 43 cobblestone held, a table remembered ten blocks over it with no route; the
  ;; pickaxe decide answered the walk to that table, make-pickaxe refused it, and the bot stood
  ;; still for two hours. The table is replaced: planks and a new table come from the stems
  (let [table-col (world/column {[12 64 8] (blocks/default-state :crafting_table)})
        w (-> (ps/standing {})
              (assoc :world/chunks {[0 0] table-col})
              (memory/remember-column [0 0] table-col)
              (assoc :player/inventory
                     {0 (ps/item :iron_sword 1)
                      1 (assoc (ps/item :iron_pickaxe 1) :durability 11)
                      2 (ps/item :warped_stem 6)
                      3 (ps/item :stick 4)
                      4 (ps/item :cobblestone 43)})
              (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (with-redefs [intent/routable? (ps/routable-here (constantly false))]
      (is (contains? #{:craft :place :open-container}
                     (:intent/kind (plan/next-intent w ps/row plan/goals)))
          "not the walk to a table nothing reaches"))))

(deftest a-pickaxe-reserve-cuts-stems-before-the-last-digs
  ;; pearl-fight-i job 2 (plan run 41178660163) stood still in a rock pocket from minute 36 with
  ;; six digs left and no wood: its iron pickaxe wore out on burrows, 549 netherrack held. At an
  ;; earlier moment, about sixty digs left and no stems held, the hunt cuts the forest's stems
  (let [stem (first (blocks/states-of :warped_stem))
        trunk {[12 64 8] stem
               [12 65 8] stem
               [12 66 8] stem
               [12 67 8] (blocks/default-state :warped_wart_block)}
        col (world/column trunk)
        base (-> (ps/standing {})
                 (assoc :world/chunks {[0 0] col})
                 (memory/remember-column [0 0] col)
                 (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        with (fn [digs stems]
               (assoc base
                      :player/inventory
                      (cond-> {0 (ps/item :iron_sword 1)
                               1 (assoc (ps/item :iron_pickaxe 1) :durability digs)}
                        (pos? stems) (assoc 2 (ps/item :warped_stem stems)))))]
    (is (= :log (:intent/for (plan/next-intent (with 60 0) ps/row plan/goals)))
        "sixty digs left and no wood: a stem is cut")
    (is (= :lookout (:intent/kind (plan/next-intent (with 60 6) ps/row plan/goals)))
        "six stems held: the hunt goes on, the pickaxe made when it falls under spare-digs")
    (is (= :lookout (:intent/kind (plan/next-intent (with 200 0) ps/row plan/goals)))
        "digs to spare: no reserve is cut")))

(deftest hunt-thresholds-table-is-loaded-and-complete
  ;; the hunt thresholds are loaded from resources/clojurecraft/pearls.edn
  (let [t pparams/thresholds]
    (is (map? t) "the thresholds are an EDN map")
    (is (= 64.0 (:seek-radius t) pparams/seek-radius))
    (is (= 64.0 (:stare-range t) pparams/stare-range))
    (is (= 6.0 (:stare-from t) pparams/stare-from))
    (is (= 3.0 (:provoke-home-reach t) pparams/provoke-home-reach))
    (is (= 18.0 (:provoke-health t) pparams/provoke-health))
    (is (= 3.0 (:provoke-margin t) pparams/provoke-margin))
    (is (= 480000 (:relocate-ms t) pparams/relocate-ms))
    (is (= 32.0 (:relocate-dist t) pparams/relocate-dist))
    (is (= 420000 (:relocate-timeout-ms t) pparams/relocate-timeout-ms))
    (is (= 180000 (:relocate-patience-ms t) pparams/relocate-patience-ms))
    (is (= 8.0 (:swarm-radius t) pparams/swarm-radius))
    (is (= 16.0 (:hold-radius t) pparams/hold-radius))
    (is (= 10.0 (:danger-health t) pparams/danger-health))
    (is (= 15.0 (:break-hp t) pparams/break-hp))
    (is (= 19.0 (:resume-hp t) pparams/resume-hp))))

(deftest with-an-angry-enderman-near-the-burrow-no-out-walk-relocates-or-wanders
  ;; pearl-fight-q (plan run 41275336100) jobs 1, 2, 4 and 5 ping-ponged between burrow shelter
  ;; and an out-walk (relocate/wander/stare) when an angry enderman stood 8 to 16 blocks off; job 2
  ;; took 145 steps out until stuck explore drove the bot into the open where enderman 1236 slew it
  (let [angry (assoc (ps/enderman [20.5 61.0 8.5]) :entity/creepy? true)
        w (-> (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 angry}) 12)
              (assoc :pearls/relocate {:from [8 61 8] :at 0 :near #{}} :time/now 200000))
        inside-intent (plan/next-intent w ps/row plan/goals)
        out-walk {:intent/kind :walk :intent/target [30 61 8] :intent/for :relocate}
        stepped-out (assoc w :player/pos [7.5 61.0 8.5])]
    (testing
      "inside the burrow with an angry enderman, the hunt holds the fight and never relocates"
      (is (= :fight (:intent/kind inside-intent)))
      (is (:intent/hold? inside-intent))
      (is (= :pearls (:intent/for inside-intent))))
    (testing
      "when the held fight gave up or blacklisted, it stays in lookout, never starting an out-walk"
      (let [w-bl (assoc w :plan/blacklist #{7})
            i (plan/next-intent w-bl ps/row plan/goals)]
        (is (= :lookout (:intent/kind i)))
        (is (= :pearls (:intent/for i)))))
    (testing "stepping out of the burrow with an angry enderman within hold-radius is interrupted"
      (is (plan/interrupts? stepped-out ps/row out-walk)))
    (testing "outside the burrow with an angry enderman within hold-radius routes way-home"
      (let [i (plan/next-intent stepped-out ps/row plan/goals)]
        (is (= :walk (:intent/kind i)))
        (is (= [8 61 8] (:intent/target i)))
        (is (= :shelter (:intent/for i)))))))

(deftest while-an-angry-enderman-is-within-the-hold-radius-the-burrow-is-held-not-stuck
  ;; the stuck watchdog's forced explore (stuck 0 :ender-pearls explore) walked job 2 of round q
  ;; out into the open at 63 min, where enderman 1236 slew it. While an angry enderman is within
  ;; the hold radius, the stuck watchdog must count the row as held, not stuck: no unstick, no explore walk.
  (let [angry (assoc (ps/enderman [20.5 61.0 8.5]) :entity/creepy? true)
        w (-> (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 angry}) 12)
              (assoc :plan/status :active
                     :plan/goals #{:ender-pearls}
                     :plan/watch
                     {:key [0 0 0 0] :lo [8.5 61.0 8.5] :hi [8.5 61.0 8.5] :at 0 :checked 0}
                     :time/now (+ plan/stuck-ms 100000)))
        watched (plan/watch w plan/goals {:event/rand 0.5})]
    (testing
      "the watchdog counts the burrow as held, not stuck: watch is restamped, unstuck not incremented"
      (is (plan/held-goal? w ps/row))
      (is (= 0 (:plan/unstuck watched 0)))
      (is (nil? (:plan/explore watched)))
      (is (= (:time/now w) (:at (:plan/watch watched))))
      "watch timestamp restamped to now")
    (testing
      "even with :plan/explore in world, plan-tick does not start an explore walk out of the burrow"
      (let [with-explore (assoc w :plan/explore [40 61 8])
            stepped
            (plan/plan-tick with-explore {:event/kind :tick :event/now (:time/now w)} plan/goals)]
        (is (not= :explore (get-in stepped [:plan/intent :intent/for])) "no explore walk started")
        (is (not= [40 61 8] (get-in stepped [:plan/intent :intent/target])))))))

(deftest the-nine-second-dig-is-a-hand-dig-on-nether-brick
  ;; Logged state, run 41852247420 s1 Clj_r36: `tunnel stage [:dig :open] 9157ms` while
  ;; boring at the spawner, hp 20 to 8, death-blaze 4 blocks from the rods. Nether brick
  ;; (hardness 2.0) takes a stone pickaxe (speed 4) 750 ms; by hand (speed 1, no harvest)
  ;; it takes 10000 ms. The 9 s stage is a hand dig: the pickaxe was not the held tool for
  ;; that dig (not in the hotbar the dig chooses from, intent/choose-tool), not a hard
  ;; block and not the stage timing, which adds only settle and confirm ticks.
  (let [brick (blocks/default-state :nether_bricks)
        pick (recipe/item-id :stone_pickaxe)]
    (is (= 750 (intent/dig-time brick pick {})) "stone pickaxe on nether brick: far under 1 s")
    (is (>= (intent/dig-time brick nil {}) 9000) "by hand on nether brick: the 9 s stage")))

(deftest the-pearl-fallback-never-waits-with-nothing-pending
  ;; race-read 1105b Q3: 42 of 141 real rod bots ended [:ender-pearls :nothing-to-do]
  ;; with time and health left, because every hunt row was nil and there was no fallback.
  (let [distant (ps/distant-world [64.5 64.0 8.5])]
    (is (= (pmove/go-toward-sighting distant) (pearls/pearl-fallback distant))
        "the fallback first goes toward the remembered sighting")
    (let [empty (ps/standing {})
          f (pearls/pearl-fallback empty)]
      (is (some? f) "with no sighting, the forest search or a lookout, never nil")
      (is (contains? #{:walk :lookout} (:intent/kind f)))
      (is (nil? (:plan/wait f)) "never a wait with nothing pending"))
    (is (some? (plan/next-intent (ps/standing {}) ps/row plan/goals))
        "the pearl stage always has a next intent")))

(deftest a-full-bag-makes-room-for-the-pearls-first
  ;; sim R1-2-1 on the stage-18 rods start (replay probe 42225282730): 3 pearls held and no slot
  ;; free from 4145 s on, every pickup after failing :not-picked-up; the race kit fills all 36
  (let [full (into {} (for [p (range 1 36)] [p (ps/item :sand 64)]))
        w (-> (ps/standing {})
              (assoc :player/dimension "minecraft:the_nether")
              (update :player/inventory merge full)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
    (is (= {:intent/kind :toss :intent/free 4} (plan/next-intent w ps/row plan/goals))
        "sand thrown out until four slots are free")
    (is (not= :toss
              (:intent/kind
               (plan/next-intent
                (assoc w :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)})
                ps/row
                plan/goals)))
        "with room, the hunt")))

(deftest an-angry-one-given-up-on-still-keeps-the-bot-from-walking-out
  ;; sim R1-2-1 on the stage-18 rods start (lane 42212990209, replay probe 42217021861): its
  ;; first pearl held, enderman 955 fought four times from the burrow with no hit landed, given
  ;; up on, angry ten blocks off; the hunt walked 34 blocks out to a new stare site and 955
  ;; struck it from 15.9 to 0 in four seconds
  (let [angry (assoc (ps/enderman [8.5 64.0 18.5]) :entity/creepy? true)
        calm (ps/enderman [8.5 64.0 40.5])
        w (-> (ps/standing {7 angry 8 calm})
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :plan/blacklist #{7}))]
    (is (psense/angry-tracked? w pparams/hold-radius) "blacklisted, it is still tracked angry")
    (is (not (psense/angry-tracked? (assoc-in w [:world/entities 7 :entity/creepy?] false)
                                    pparams/hold-radius))
        "a calm one is not")
    (is (= {:intent/kind :lookout :intent/for :pearls} (pearls/hunt w))
        "a lookout where it stands, no walk out")))

(deftest a-bot-on-its-own-pillar-digs-back-down-first
  ;; real-18 Clj_r11 (3af52ae lane 2 b2, probe 42268355364): on a lone pillar at y 98 for its last
  ;; fifteen minutes, every walk :no-path and every chase tunnel :blocked, the run ended :blocked
  (let [cobble (blocks/default-state :cobblestone)
        w (-> (ps/standing {} {[8 64 8] cobble [8 65 8] cobble})
              (assoc :player/dimension "minecraft:the_nether" :player/pos [8.5 66.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
    (is (pplace/perched? w))
    (is (= {:intent/kind :dig :intent/target [8 65 8] :intent/drop-ok? true :intent/for :pearls}
           (pearls/hunt w))
        "the block under the feet dug first")
    (is (not (pplace/perched? (ps/standing {}))) "on the ground: not perched")))

(deftest the-hunt-says-which-branch-chose-its-intent
  ;; the observability plan, part B: every intent the stuck stage's planners choose carries the
  ;; branch that chose it (intent/why-of), and its intention fact keeps it (:intention/why)
  (let [angry (assoc (ps/enderman [8.5 64.0 18.5]) :entity/creepy? true)
        w (-> (ps/standing {7 angry})
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :plan/blacklist #{7}))
        i (pearls/hunt w)]
    (is (= {:intent/kind :lookout :intent/for :pearls} i) "the intent itself is as before")
    (is (keyword? (intent/why-of i)))
    (is (= "hunt" (namespace (intent/why-of i))))
    (is (= (intent/why-of i)
           (:intention/why (memory/intention (assoc i :intent/id 3) :started 0))))))
