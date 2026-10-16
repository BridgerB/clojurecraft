(ns clojurecraft.pearls-test
  "Ender pearls by hunting endermen (issue #263): the hunt's choices over canned worlds, the
   provoke tick by tick against vanilla's stare test, the kill's drop, and a hunt against the
   server model's enderman."
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
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def step (game/compose game/step plan/step))

(def stone 1)

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn enderman
  "A tracked enderman at pos with its full health."
  [pos]
  {:entity/type (blocks/entity-types :enderman)
   :entity/pos pos
   :entity/seen-at 0
   :entity/health 40.0})

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column (with blocks, {[x y z] state},
   over it), an iron sword in hotbar slot 0, with the given entities."
  ([entities] (standing entities {}))
  ([entities blocks]
   (-> (game/init fx/opts)
       (assoc :bot/phase :play
              :player/pos [8.5 64.0 8.5]
              :player/loaded? true
              :player/on-ground? true
              :player/health 20.0
              :world/chunks {[0 0] (world/column blocks)}
              :window/state-id 5
              :player/inventory {0 (item :iron_sword 1)}
              :world/entities entities))))

(def row (first (filter #(= :ender-pearls (:goal/id %)) plan/goals)))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(def wall
  "Stone four high across x 6 to 10 at z 12: it hides the head of an enderman standing at z 15
   from the eye at z 8.5, and a walk goes round it."
  (into {} (for [x (range 6 11) y (range 64 68)] [[x y 12] stone])))

(deftest one-in-reach-is-fought-one-in-sight-provoked-one-hidden-walked-to
  (let [near (standing {7 (enderman [11.5 64.0 8.5])})
        seen (standing {7 (enderman [8.5 64.0 15.5])})
        hidden (standing {7 (enderman [8.5 64.0 15.5])} wall)
        close (standing {7 (enderman [8.5 64.0 13.5])})]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent near row plan/goals)))
    (is (= {:intent/kind :back-off :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc near :player/health 6.0) row plan/goals))
        "under 10 health one at hand is backed away from")
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent seen row plan/goals))
        "seven blocks off with a clear line: a stare brings it")
    (is (= {:intent/kind :walk :intent/target [8 64 15] :intent/for :pearls}
           (plan/next-intent hidden row plan/goals))
        "behind the wall it cannot be stared at: walked to")
    (is
     (= {:intent/kind :walk :intent/target [8 64 13] :intent/for :pearls}
        (plan/next-intent close row plan/goals))
     "five blocks off is too near to stare (within 4 an angry one teleports away), too far to hit")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (standing {}) row plan/goals)))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc near :plan/blacklist #{7}) row plan/goals))
        "a ghost or one that would not come is left")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc-in near [:world/entities 7 :entity/died-at] 0) row plan/goals))
        "a dead one is no quarry")))

(deftest the-stare-is-for-the-nearest-one-in-sight-not-only-the-nearest
  ;; gym batch pearls-263d (probe 38904622614): seventeen endermen round a warped forest landing,
  ;; the nearest behind a ledge, and the bot looked round for forty minutes
  (let [w (standing {7 (enderman [8.5 64.0 15.5]) 9 (enderman [1.5 64.0 2.5])} wall)]
    (is (= 9 (first (pearls/quarry w))))
    (is (= {:intent/kind :provoke :intent/target 9 :intent/for :pearls}
           (plan/next-intent w row plan/goals)))
    (is (nil? (pearls/quarry (assoc-in w [:world/entities 9 :entity/creepy?] true)))
        "one already coming is no quarry")))

(deftest the-provoke-stares-at-the-head-until-the-server-says-it-has-a-target
  (let [pos [8.5 64.0 15.5]
        w (assoc (standing {7 (enderman pos)})
                 :plan/intent
                 {:intent/kind :provoke :intent/target 7 :intent/status :active})
        stared?
        (fn [w]
          (mob/stared? (constantly false) (game/eye w) (:control/look (:player/controls w)) pos))
        [staring _] (fx/fold intent-step w (map tick (range 50 600 50)))
        [turned _] (fx/fold intent-step
                            (assoc-in staring [:world/entities 7 :entity/creepy?] true)
                            [(tick 600)])
        [ignored _] (fx/fold intent-step staring (map tick (range 600 4000 50)))]
    (testing "the stare is vanilla's (EnderMan.isBeingStaredBy, mob/stared?)"
      (is (= :active (:intent/status (:plan/intent staring))))
      (is (stared? staring)))
    (testing "creepy, it is coming: done, the look off its face at once"
      (is (= :done (:intent/status (:plan/intent turned))))
      (is (= (physics/look-at (game/eye turned) (combat/aim (enderman pos)))
             (:control/look (:player/controls turned))))
      (is (not (stared? turned))))
    (is (= [:failed :unprovoked] ((juxt :intent/status :intent/reason) (:plan/intent ignored)))
        "one that never turns is given up")))

(def air 0)

(def burrow
  "A burrow cut east from [3 64 8], as :burrow cuts one: three stairs, each three cells high
   (x 4 to 6, down to feet 61), then two tunnel steps two high (x 7 and 8, the ground over them)."
  (into {}
        (for [c [[4 63 8] [5 63 8] [5 62 8] [6 63 8] [6 62 8] [6 61 8] [7 62 8] [7 61 8] [8 62 8]
                 [8 61 8]]]
          [c air])))

(defn burrowed
  "standing at feet with the burrow dug and remembered, holding a pickaxe too, with entities."
  [feet entities]
  (-> (standing entities burrow)
      (assoc :player/pos feet :pearls/shelter [8 61 8] :pearls/mouth [3 64 8])
      (assoc-in [:player/inventory 1] (item :iron_pickaxe 1))))

(deftest the-burrow-end-is-out-of-an-endermans-reach
  ;; a mob's melee is its box grown 0.83 sideways, never up or down (Mob.getAttackBoundingBox,
  ;; probe 38893918055): one cell off, an enderman reaches; two off, or roofed out, it cannot
  (let [w (standing {} burrow)]
    (is (pearls/shelter? w [8 61 8])
        "the tunnel's end: the last stair, where one can stand, is two off")
    (is (not (pearls/shelter? w [7 61 8])) "the tunnel's first step is beside the last stair")
    (is (not (pearls/shelter? w [6 61 8])) "a stair is three high: an enderman stands in it")
    (is (not (pearls/shelter? w [3 64 8])) "open ground")
    (is (not (pearls/shelter? w [9 61 8])) "inside the rock")))

(deftest the-hunt-digs-a-burrow-provokes-from-its-mouth-and-fights-from-its-end
  (let [far (enderman [0.5 64.0 8.5])
        coming (assoc far :entity/creepy? true)]
    (is (= {:intent/kind :burrow :intent/dir [0 -1] :intent/for :pearls}
           (plan/next-intent (assoc-in (standing {7 (enderman [8.5 64.0 15.5])})
                              [:player/inventory 1]
                              (item :iron_pickaxe 1))
                             row
                             plan/goals))
        "no burrow, a pickaxe held: one dug away from the enderman, its stairs opening toward it")
    (is (= {:intent/kind :fight :intent/target 7 :intent/hold? true :intent/for :pearls}
           (plan/next-intent (burrowed [8.5 61.0 8.5] {7 coming}) row plan/goals))
        "in the burrow, the one coming is fought where the bot stands")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (burrowed [8.5 61.0 8.5] {7 far}) row plan/goals))
        "none coming: out to the mouth to look round")
    (is (=
         {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
         (plan/next-intent (burrowed [3.5 64.0 8.5] {7 (enderman [3.5 64.0 0.5])}) row plan/goals))
        "from the mouth, one in sight is provoked")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (burrowed [3.5 64.0 8.5]
                                       {7 (assoc (enderman [3.5 64.0 0.5]) :entity/creepy? true)})
                             row
                             plan/goals))
        "provoked, it is coming: back to the burrow's end")
    (is
     (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
        (plan/next-intent
         (assoc (burrowed [3.5 64.0 8.5] {7 (enderman [5.5 64.0 8.5])}) :player/health 6.0)
         row
         plan/goals))
     "hurt with one at hand: into the burrow, not backing away in the open (gym batch pearls-263b)")))

(deftest a-kill-is-picked-up-where-it-fell-a-kill-with-no-drop-is-left
  (let [kill {:intent/kind :fight
              :intent/for :pearls
              :intent/target 7
              :intent/target-pos [11.7 64.0 8.2]
              :intent/status :done}
        drop {:entity/type blocks/item-entity-type :entity/pos [11.6 64.2 8.4] :entity/seen-at 0}
        block (assoc drop :entity/item (recipe/item-id :warped_nylium))
        body (assoc (enderman [11.7 64.0 8.2]) :entity/died-at 0)]
    (is (= {:intent/kind :collect-drops
            :intent/item :ender_pearl
            :intent/target [11 64 8]
            :intent/for :pearls}
           (plan/next-intent (assoc (standing {9 drop}) :plan/last kill) row plan/goals)
           (plan/next-intent (assoc (standing
                                     {9 (assoc drop :entity/item (recipe/item-id :ender_pearl))})
                                    :plan/last
                                    kill)
                             row
                             plan/goals))
        "a drop that holds a pearl, or has not said what it holds, is picked up")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (standing {9 block}) :plan/last kill) row plan/goals))
        "the block an enderman carried is left (#262: a drop says what it holds)")
    (is (= {:plan/wait :drop}
           (plan/next-intent (assoc (standing {7 body}) :plan/last kill) row plan/goals))
        "the body still lies there: its drop may still come")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (standing {}) :plan/last kill) row plan/goals))
        "body gone and nothing dropped: no pearl this time")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent
            (assoc (standing {7 (enderman [11.7 64.0 8.2]) 9 drop}) :plan/last kill)
            row
            plan/goals))
        "a fight broken off left its enderman alive: no kill, and the fight goes on")))

(deftest twelve-pearls-held-the-stage-is-done
  (is (plan/done-by (assoc (standing {}) :player/inventory {3 (item :ender_pearl 12)}) row))
  (is (not (plan/done-by (assoc (standing {}) :player/inventory {3 (item :ender_pearl 11)}) row))))

(def arena
  "The model's world for the hunt: two stone columns side by side, an enderman eleven blocks west
   of the spawn, the kit a chained run carries: an iron sword and pickaxe, the iron helmet and
   chestplate (#259's armour row), eleven pearls."
  {:columns {[0 0] (world/column-bytes {}) [1 0] (world/column-bytes {})}
   :spawn [12.5 64.0 8.5]
   :inventory {[:hotbar 0] (item :iron_sword 1)
               [:hotbar 1] (item :iron_pickaxe 1)
               [:armour 0] (item :iron_helmet 1)
               [:armour 1] (item :iron_chestplate 1)
               [:main 0] (item :ender_pearl 11)}
   :mobs [{:type :enderman :pos [1.5 64.0 8.5]}]})

(defn pearl-seed
  "The first seed whose model drops the enderman's pearl: the loot is the first roll of the
   mobs' own stream (sim/mob-roll) when nothing spawns or despawns, one in two."
  []
  (first (filter #(pos? (mob/loot-count [:ender_pearl 0 1]
                                        (second (sim/mob-roll (sim/init (assoc arena :seed %))))))
                 (range 64))))

(deftest an-enderman-is-provoked-and-killed-from-a-burrow-against-the-model
  ;; the model's enderman (mob.clj, issue #260) is neutral until stared at, then walks at the bot
  ;; and hits for 7 a second (5.74 through the iron): in the open it beat the bot (gym batch
  ;; pearls-263a: five deaths in five). The bot digs a burrow east, stairs then a tunnel two high,
  ;; provokes it from the mouth, goes back to the end, and kills it where it stops, at the last
  ;; stair (three cells tall, mob/tall), out of its reach; then picks the pearl up: the twelfth
  (let [[w sim] (sim/run step
                         (game/init fx/opts)
                         (sim/init (assoc arena :seed (pearl-seed)))
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         {:event/kind :go :go/goals [:ender-pearls]})
        story (mapv (juxt :intention/kind :intention/event :intention/reason)
                    (memory/intentions w))]
    (is (plan/done? w) (pr-str (plan/summary w) story))
    (is (not-any? (fn [[_ m]] (and (= :enderman (:type m)) (not (:dead-at m)))) (:sim/mobs sim))
        "the enderman was killed")
    (is (>= (get (recipe/counts (:player/inventory w)) :ender_pearl 0) 12))
    (is (some #(= [:burrow :done nil] %) story) "it dug a burrow")
    (is (some #(= [:provoke :done nil] %) story) "it came to a stare")
    (is (pearls/shelter? w (:pearls/shelter w)) (pr-str (:pearls/shelter w)))
    (is (pos? (:sim/health sim)) "and the bot lived")
    (is (= [] (:sim/violations sim)))))
