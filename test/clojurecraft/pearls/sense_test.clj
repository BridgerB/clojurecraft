(ns clojurecraft.pearls.sense-test
  "The pearl hunt's perception: endermen, anger, menace, shooters."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest the-stare-is-for-the-nearest-one-in-sight-not-only-the-nearest
  ;; gym batch pearls-263d (probe 38904622614): seventeen endermen round a warped forest landing,
  ;; the nearest behind a ledge, and the bot looked round for forty minutes
  (let [w (ps/standing {7 (ps/enderman [8.5 64.0 15.5]) 9 (ps/enderman [1.5 64.0 2.5])} ps/wall)]
    (is (= 9 (first (psense/quarry w))))
    (is (= {:intent/kind :provoke :intent/target 9 :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals)))
    (is (nil? (psense/quarry (assoc-in w [:world/entities 9 :entity/creepy?] true)))
        "one already coming is no quarry")))

(deftest a-roam-follows-a-caves-passage-to-where-it-can-look-from
  ;; gym batch pearls-263f (plan run 38918584959, probe 39130057525): a landing in a cave pocket
  ;; under the forest, netherrack sixteen blocks off in every heading at every height; the roam
  ;; asked for those cells and stood forty minutes. A route finds the passage
  (let [w (assoc (ps/standing {}) :world/chunks (ps/cave (ps/passage 20 26)))
        {:intent/keys [kind target for]} (plan/next-intent (ps/looked-out w) ps/row plan/goals)]
    (is (= [:walk :pearls] [kind for]))
    (is (= [64 20] [(second target) (nth target 2)]) "along the passage's east arm")
    (is (>= (first target) 18) "to where it is sixteen blocks off, as the way goes")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (ps/looked-out (assoc w :world/chunks (ps/cave (ps/passage 12 8))))
                             ps/row
                             plan/goals))
        "a pocket no route leaves: the lookout again")
    ;; gym batch pearls-warped2-a (plan run 39179234118) run 3: three flights down to the
    ;; endermen's level ended inside the rock, none in sight and no route, and the bot walked its
    ;; staircase up and down for thirty minutes
    (is (= {:intent/kind :tunnel :intent/dir [0 1] :intent/length 8 :intent/for :pearls}
           (plan/next-intent (-> (ps/standing {7 (ps/enderman [8.5 64.0 20.5])})
                                 (assoc :world/chunks (ps/cave (ps/passage 8 8)))
                                 (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
                             ps/row
                             plan/goals))
        "a pocket with an enderman twelve blocks off through the rock: a tunnel toward it")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 4: fifteen tunnels
    ;; east and west over one 5 blocks off and 4 below in a cave, about 100 digs in a minute
    (let [pocket (-> (ps/standing {7 (ps/enderman [8.5 60.0 13.5])})
                     (assoc :world/chunks (ps/cave (ps/passage 8 8)))
                     (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
      (is (nil? (pmove/bore pocket (psense/endermen pocket pparams/descend-radius)))
          "one five off through the rock, four below: a tunnel of eight would run past it")
      (let [eight (assoc-in pocket [:world/entities 7 :entity/pos] [8.5 60.0 16.5])]
        (is (= :tunnel
               (:intent/kind (pmove/bore eight (psense/endermen eight pparams/descend-radius))))
            "eight off: the tunnel ends beside it")))))

(deftest an-enderman-no-route-reaches-is-walked-toward-as-far-as-the-route-goes
  ;; gym batch pearls-263f (plan run 38918584959, probe 39130057525): fifteen endermen tracked
  ;; from the forest floor, every route to them :none, and thirty minutes of looking round
  (let [w (-> (ps/standing {7 (ps/enderman [8.5 70.0 30.5])})
              (assoc-in [:world/chunks [0 1]] (world/column ps/cliff)))]
    (is (nil? (psense/quarry w)) "on the ledge, out of sight behind its edge")
    (is (= {:intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "to the cliff's foot, the route's end, eleven blocks nearer it")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :player/pos [8.5 64.0 19.5]) ps/row plan/goals))
        "from the foot no route gains on it: the lookout")))

(deftest a-walk-toward-one-is-never-to-a-place-already-in-reach
  ;; gym batch pearls-warped-a (plan run 39131478416) run 3: the route's end three blocks off and
  ;; a step up, four nearer by the route's measure and within a walk's reach of the eye, so the
  ;; walk arrived where it began and was planned again every tick for minutes
  (let [w (-> (ps/standing {7 (ps/enderman [8.5 70.0 30.5])})
              (assoc :player/pos [8.5 64.0 16.5])
              (assoc-in [:world/chunks [0 1]] (world/column (assoc ps/cliff [8 64 3] ps/stone))))]
    (is (nil? (pmove/toward w (psense/endermen w pparams/seek-radius))))
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals)))
    (is (= {:intent/kind :walk :intent/target [8 65 19] :intent/for :pearls}
           (plan/next-intent (assoc w :player/pos [8.5 64.0 13.5]) ps/row plan/goals))
        "from farther back, the step at the cliff's foot is a walk")))

(deftest one-fought-again-and-again-without-a-kill-is-fought-no-more
  ;; gym batch pearls-w3 (plan run 40434266158) run 3: from its second minute to the
  ;; five-hour timeout it planned some sixty-four thousand fights on one enderman, each
  ;; ending done as the enderman teleported out of sight, nineteen failing :unreachable,
  ;; and held one pearl
  (let [angry (assoc (ps/enderman [5.5 61.0 8.5]) :entity/creepy? true)
        on-stairs (assoc (ps/burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= :fight (:intent/kind (plan/next-intent on-stairs ps/row plan/goals)))
        "at the end with it on the stairs: fought, blacklisted or not")
    (is (not (psense/fought-out? (ps/fought on-stairs (dec pparams/fight-tries)) 7))
        "seven fights done on it: not yet fought out")
    (is (psense/fought-out? (ps/fought on-stairs pparams/fight-tries) 7)
        "fight-tries fights done on it and it still stands: fought out")
    (is (not (psense/fought-out? (ps/fought on-stairs pparams/fight-tries) 8)) "another is not")
    ;; sim gate r3fr3b (probe 40470765785, world R4 seed 3): left for good, one fought
    ;; out teleported after the bot and killed it 188 times, never fought back
    (is (not (psense/fought-out? (assoc (ps/fought on-stairs pparams/fight-tries)
                                        :time/now
                                        (inc pparams/fight-rest-ms))
                                 7))
        "a rest on, it is fought again")
    (is (not= :fight
              (:intent/kind
               (plan/next-intent (ps/fought on-stairs pparams/fight-tries) ps/row plan/goals)))
        "fought out: no fight is held on it from the end again")
    (is (nil? (psense/angry-near (ps/fought on-stairs pparams/fight-tries)))
        "and it holds the burrow no longer, so the burrow can go stale"))
  ;; sim gate r3fr3 (probe 40465621253, world R4 seed 3): fought out, the one over the
  ;; burrow's roof still cut every walk away from it through interrupts?, 28,545 walks
  ;; planned in place, where main fought it in place instead (probe 40465674776)
  (let [angry (assoc (ps/enderman [8.5 64.0 10.5]) :entity/creepy? true)
        w (ps/standing {7 angry})
        away {:intent/kind :walk :intent/target [20 64 20] :intent/for :shelter}]
    (is (psense/beset? w) "angry two blocks off: beset")
    (is (plan/interrupts? w ps/row away) "and a walk away from it is cut to meet it")
    (is (not (psense/beset? (ps/fought w pparams/fight-tries))) "fought out: beset no longer")
    (is (not (plan/interrupts? (ps/fought w pparams/fight-tries) ps/row away))
        "so the walk away from it stands")))

(deftest an-angry-one-blacklisted-that-never-comes-to-the-lure-holds-the-burrow-no-longer
  ;; gym batch pearls-warped8-a (plan run 39731473452, probe 39776733945) run 1: from the 49th
  ;; minute to the end it walked from its burrow to the mouth and back every 34 s over one angry,
  ;; blacklisted after a held fight timed out :unreachable, 4.2 blocks from the mouth, that never
  ;; moved; the burrow never went stale
  (let [angry (assoc (ps/enderman [3.5 64.0 12.5]) :entity/creepy? true)
        given-up (assoc (ps/burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= 7 (first (psense/angry-near (ps/lured given-up 2)))) "two lures: still held")
    (is (not (psense/lured-out? (ps/lured given-up 2))))
    (is (psense/lured-out? (ps/lured given-up pparams/lure-tries)))
    (is (nil? (psense/angry-near (ps/lured given-up pparams/lure-tries)))
        "lure-tries lures it never came to: no longer held")
    (is (not= :stairs-up
              (:intent/kind
               (plan/next-intent (ps/lured given-up pparams/lure-tries) ps/row plan/goals)))
        "and no way out is cut for one merely near the mouth: the cut-out is for the stairs")
    (is (= 7
           (first (psense/angry-near (ps/lured (dissoc given-up :plan/blacklist)
                                               pparams/lure-tries))))
        "one not given up on still holds the bot in")))

(deftest an-unreachable-angry-enderman-that-never-comes-to-the-lure-holds-the-burrow-no-longer
  ;; gym batch pearl-fight-r (plan run 41306509324): runs 2, 3, 4 and 5 timed out after five
  ;; hours in endless shelter <-> mouth ping-pongs (452 to 944 each) over an angry enderman
  ;; within hold-radius and danger-rise (e.g. run 2: enderman 2511 at [2451.5 43.0 2182.5],
  ;; 4 blocks below in a cave), whose held fights were cut after 12 s by held-idle? (:abandoned).
  ;; Not blacklisted, it held angry-within? and angry-near forever even after lure-tries lures;
  ;; once lured-out, an unfightable angry enderman holds the burrow no longer, stale surrenders
  ;; the shelter, and the bot does not plan endless mouth walks or lookouts
  (let [angry (assoc (ps/enderman [3.5 59.0 12.5]) :entity/creepy? true)
        w (assoc (ps/burrowed [8.5 61.0 8.5] {7 angry}) :time/now 60000)
        fight {:intent/id 1 :intent/kind :fight :intent/target 7 :intent/since 0 :intent/hold? true}
        cut (reduce #(memory/remember-intention %1 (assoc fight :intent/id (+ 100 %2)) :abandoned)
                    (ps/lured w pparams/lure-tries)
                    (range pparams/fight-giveups))]
    (is (psense/unfightable? cut 7 angry) "cut fight-giveups times: unfightable")
    (is (psense/lured-out? cut) "lure-tries lures walked")
    (is (nil? (psense/angry-near cut)) "unfightable one near mouth holds angry-near no longer")
    (is (not (psense/angry-within? cut pparams/hold-radius)) "and holds angry-within? no longer")
    (let [stale-burrow
          (reduce
           #(memory/remember-intention %1 {:intent/id (+ 1000 %2) :intent/kind :lookout} :done)
           cut
           (range pparams/stale-lookouts))]
      (is (psense/stale? stale-burrow) "stale after stale-lookouts lookouts")
      (is (nil? (pplace/shelter stale-burrow)) "burrow is surrendered as stale")
      (is (not= :lookout (:intent/kind (plan/next-intent stale-burrow ps/row plan/goals)))
          "plans to move on, not sit in lookouts"))))

(deftest one-given-up-on-in-the-stairs-is-cut-out-past
  ;; sim probe 40102602895 (world R3, seed 4): in its burrow from minute 26 to 60 over an
  ;; angry one, blacklisted, standing in its stairs out of reach: blocking kept it in,
  ;; angry-near kept the burrow fresh, and no lure walk was ever taken to count
  (let [angry (assoc (ps/enderman [4.5 63.0 8.5]) :entity/creepy? true)
        dug (assoc (ps/burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= 7 (first (psense/blocking dug))) "in the stairs, below the mouth")
    (is (not (psense/blocked-out? (ps/looked dug 3))))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (ps/looked dug 3) ps/row plan/goals))
        "three lookouts: waited for where it stands, as before")
    (is (psense/blocked-out? (ps/looked dug 12)))
    (is (psense/stale? (ps/looked dug 12)) "a minute of them: the burrow is given up")
    (is (= {:intent/kind :stairs-up
            :intent/to-y 64
            :intent/dir [1 0]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent (ps/looked dug 12) ps/row plan/goals))
        "and the way out is cut up the other way from the mouth, to its height")
    (is (not (psense/blocked-out? (ps/looked (dissoc dug :plan/blacklist) 12)))
        "one not given up on is still waited for")))

(deftest a-fire-the-bot-stands-in-is-put-out-first
  ;; gym batch pearls-warped8-b (plan run 39775345028, probe 39849820209) run 1: its walk ended in
  ;; a fire lit on gravel in its path, and it stood there in :lookout until it died
  (let [fire (blocks/default-state :fire)]
    (is (nil? (psense/burning-at (ps/standing {}))))
    (is (= {:intent/kind :dig :intent/target [8 64 8]}
           (plan/next-intent (ps/standing {} {[8 64 8] fire}) ps/row plan/goals))
        "at the feet")
    (is (= [8 65 8] (psense/burning-at (ps/standing {} {[8 65 8] fire}))) "at the head")
    ;; gym batch pearls-w1 (plan run 40387599204, probe 40421394796) run 1 dug out its feet
    ;; cell's fire and burned to death anyway: at z 2165.14 its box still reached 0.16 into
    ;; the fire in the cell north, which the feet's one cell never showed
    (is (= [8 64 7]
           (psense/burning-at (assoc (ps/standing {} {[8 64 7] fire}) :player/pos [8.5 64.0 8.14])))
        "in a cell beside the feet the body overlaps")
    (is (nil? (psense/burning-at (ps/standing {} {[8 64 7] fire})))
        "the same fire, the body clear of its cell")
    (is (= [8 64 8]
           (psense/burning-at
            (assoc (ps/standing {} {[8 64 8] fire [8 64 7] fire}) :player/pos [8.5 64.0 8.14])))
        "both burning: the feet's own first")))

(deftest no-enderman-is-provoked-hurt
  ;; sim-endermen3's probe 39847287615 (worlds R1, R2, R4): the first death each time came of a
  ;; provoke 4 to 8 blocks off at 10.8 to 17.8 health just outside the burrow, three to five
  ;; blows landing in five seconds
  (let [sighted (ps/enderman [3.5 64.0 0.5])
        at-mouth (assoc (ps/burrowed [3.5 64.0 8.5] {7 sighted}) :player/food 20)]
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent at-mouth ps/row plan/goals))
        "whole: provoked from the mouth")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc at-mouth :player/health 15.0) ps/row plan/goals))
        "hurt at the mouth: home first")
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc at-mouth :player/health (psense/provoke-line at-mouth))
                             ps/row
                             plan/goals)))))

(deftest at-a-burrow-site-the-burrow-is-cut-with-the-quarry-hidden
  ;; sim probe 39861051222 (world R4): fifty-seven minutes between a site, from which the quarry
  ;; was hidden, and the walk toward it, from which it showed again
  (let [w (assoc-in (ps/standing {7 (ps/enderman [8.5 64.0 15.5])} ps/wall)
           [:player/inventory 1]
           (ps/item :iron_pickaxe 1))
        arrived (assoc w :plan/last {:intent/kind :walk :intent/for :shelter :intent/status :done})]
    (is (nil? (psense/quarry w)) "hidden by the wall")
    (is (not= :burrow (:intent/kind (plan/next-intent w ps/row plan/goals))))
    (is (= {:intent/kind :burrow :intent/dir [0 -1] :intent/for :pearls}
           (plan/next-intent arrived ps/row plan/goals))
        "walked to a site: the burrow, away from the nearest")))

(deftest one-hidden-is-walked-to-a-site-it-is-stared-at-from-never-up-to
  ;; sim probe 39885559223 (world R2, seed 4): the walk to a calm one ended beside it, and the
  ;; burrow begun there provoked it two blocks off; the bot was slain in the open in six seconds
  (let [e (ps/enderman [8.5 64.0 15.5])
        w (-> (ps/standing {7 e} ps/wall)
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (update :world/chunks
                      into
                      (for [cx [-1 0 1]
                            cz [-1 0 1]
                            :when (not= [0 0] [cx cz])]
                        [[cx cz] (world/column {})])))
        c (pplace/stare-site w e)
        at #(assoc w :player/pos (psense/feet-at c) :plan/last %)]
    (is (nil? (psense/quarry w)) "hidden by the wall")
    (is (some? c))
    (is (= {:intent/kind :walk :intent/target c :intent/for :shelter}
           (plan/next-intent w ps/row plan/goals))
        "a walk to a site, not to its cell")
    (is (>= (physics/distance (game/eye (at nil)) (psense/head e)) (+ pparams/stare-from 1.0)))
    (is (psense/provokable? (at nil) e) "from which it is stared at")
    (is (= :burrow
           (:intent/kind (plan/next-intent
                          (at {:intent/kind :walk :intent/for :shelter :intent/status :done})
                          ps/row
                          plan/goals)))
        "where the burrow is cut")
    (is (= {:intent/kind :walk :intent/target [8 64 15] :intent/for :pearls}
           (plan/next-intent (update w :player/inventory dissoc 1) ps/row plan/goals))
        "with no pickaxe to cut one, up to it as before")))

(deftest an-unreachable-enderman-under-16-blocks-is-no-quarry
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: provoked an enderman 6 blocks off down
  ;; a 2-block ledge with no path up; within 16 blocks it never teleports and could not reach
  (let [low (ps/enderman [3.5 62.0 15.5])
        w (-> (ps/burrowed [3.5 64.0 8.5] {7 low})
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (psense/provokable? w low) "clear sight line")
    (is (not (psense/reaches? w low (:pearls/mouth w))) "cannot climb 2 blocks up to the mouth")
    (is (nil? (psense/quarry w)) "skipped as quarry since it cannot reach")
    (let [far (ps/enderman [3.5 64.0 28.5])
          w-far (assoc-in w [:world/entities 8] far)]
      (is (psense/reaches? w-far far (:pearls/mouth w-far)) "past 16 blocks it teleports")
      (is (= 8 (first (psense/quarry w-far))) "so the distant one is quarry"))))

(deftest out-of-the-burrow-no-look-stares-at-a-calm-one-and-one-angry-at-hand-ends-the-intent
  ;; sim probes 39887735653 and 39887752302 (seeds 4 and 5): four bots stared at one two or three
  ;; blocks off on a walk or a tunnel and were slain in the open, the intent going on
  (let [e (ps/enderman [8.5 64.0 14.5])
        w (ps/standing {7 e})
        look (physics/look-at (game/eye w) (psense/head e))
        steered #(get-in (plan/steer (assoc-in % [:player/controls :control/look] look) ps/row)
                         [:player/controls :control/look])]
    (is (= [(first look) 90.0] (steered w)) "a look into a calm one's eyes: turned down")
    (is (= look (steered (assoc-in w [:world/entities 7 :entity/creepy?] true)))
        "an angry one's: as it was")
    (is (= look (steered (assoc w :plan/intent {:intent/kind :provoke :intent/target 7})))
        "a provoke stares")
    (is (= look (steered (assoc w :pearls/shelter [8 64 8])))
        "in the burrow's end a stare by chance brings it to the sword"))
  (let [angry (assoc (ps/enderman [10.5 64.0 8.5]) :entity/creepy? true)
        w (ps/standing {7 angry})]
    (is (psense/beset? w))
    (is (plan/interrupts? w ps/row {:intent/kind :tunnel}) "a tunnel with one at hand: ended")
    (is (plan/interrupts? (assoc w :plan/blacklist #{7})
                          ps/row
                          {:intent/kind :walk :intent/target [0 64 0]})
        "blacklisted or not")
    (is (not (plan/interrupts? w ps/row {:intent/kind :fight :intent/target 7}))
        "the fight goes on")
    (is (not (plan/interrupts? (assoc w :pearls/shelter [3 61 8])
                               ps/row
                               {:intent/kind :walk :intent/target [3 61 8]}))
        "and the walk home")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 7 :entity/pos] [20.5 64.0 8.5])
                               ps/row
                               {:intent/kind :tunnel}))
        "one past strike-range: the tunnel goes on")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 7 :entity/creepy?] false)
                               ps/row
                               {:intent/kind :tunnel}))
        "a calm one at hand: the tunnel goes on")
    ;; sim probe 39892490709 (worlds R1 and R4, seed 3): the lookout in the end cut every tick for
    ;; twenty minutes over one given up on by the mouth
    (is (not (plan/interrupts? (assoc w :pearls/shelter [8 64 8]) ps/row {:intent/kind :lookout}))
        "in the burrow's end, where none reaches: the lookout goes on")))

(deftest a-burrow-crowded-by-a-calm-one-for-a-minute-is-given-up-for-one-beside-it
  ;; gym batch pearls-warped4-a (plan run 39544677155): run 1 stood in its burrow from 308 s to
  ;; the end over a calm enderman 2.7 blocks from the mouth that never moved, run 5 twenty-one
  ;; minutes over one 3.9 off; run 5 then burrowed beside another by chance and killed it
  (let [calm (ps/enderman [0.5 64.0 8.5])
        dug (ps/burrowed [8.5 61.0 8.5] {7 calm})
        stale (ps/looked dug 12)
        {:intent/keys [kind target for]} (plan/next-intent stale ps/row plan/goals)]
    (is (= 11 (memory/done-since (ps/looked dug 11) :lookout :burrow)))
    (is (nil? (memory/done-since dug :lookout :burrow)) "no burrow ever cut")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (ps/looked dug 11) ps/row plan/goals))
        "eleven lookouts: no vantage out past the crowder, the bot stays in until the minute is up")
    (is (psense/stale? stale) "a minute of them: the burrow is given up")
    (is (nil? (pplace/shelter stale)))
    (is (and (= :walk kind) (= :shelter for) (not= [8 61 8] target) (not= [3 64 8] target))
        "the hunt walks out to a new burrow's site")
    ;; sim probe 39883402083 (world R1, seed 4): the walk toward it and the burrow cut beside it
    ;; were the old burrow again, every few minutes for fifty
    (let [at (assoc stale
                    :player/pos (psense/feet-at target)
                    :plan/last {:intent/kind :walk :intent/for :shelter :intent/status :done})]
      (is (= target (pplace/crowd-site stale)))
      (is (>= (physics/distance (game/eye at) (psense/head calm)) (+ pparams/stare-from 1.0))
          "past stare-from of it, so the stare from the new mouth runs its course")
      (is (psense/provokable? at calm) "with a clear line to its head")
      (is (= :burrow (:intent/kind (plan/next-intent at ps/row plan/goals)))
          "there, the new burrow"))
    (is (nil? (pplace/crowd-site (assoc stale :plan/blacklist #{7})))
        "one that would not provoke is no reason to move")
    (is (nil? (pplace/crowd-site (ps/looked (ps/burrowed [8.5 61.0 8.5] {}) 12)))
        "no crowd, no site")
    (is (not (psense/stale? (ps/looked (assoc-in dug [:world/entities 7 :entity/creepy?] true) 12)))
        "one coming is no calm crowd")
    (is
     (psense/stale? (assoc (ps/looked dug 12) :plan/blacklist #{7}))
     "a calm one given up on (:unprovoked) holds nothing: batch pearls-warped5-a's run 3
         stood 37 minutes beside its burrow over one")
    ;; batch pearls-warped5-a (plan run 39625347094, probe 39668407179) run 4: a held fight on
    ;; one timed out, the burrow went stale with it angry seven blocks off the mouth, and the
    ;; walk out was slain by it
    (let [angry (-> (ps/burrowed [8.5 61.0 8.5] {7 (assoc calm :entity/creepy? true)})
                    (assoc :plan/blacklist #{7}))
          at (fn [p] (assoc-in angry [:world/entities 7 :entity/pos] p))]
      (is (= 7 (first (psense/angry-near (at [3.5 64.0 15.5]))))
          "angry, seven off the mouth, nine from the end")
      (is (not (psense/stale? (ps/looked (at [3.5 64.0 15.5]) 12)))
          "an angry one given up on near the mouth keeps the burrow")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (ps/looked (at [3.5 64.0 15.5]) 5) ps/row plan/goals))
          "and the bot in it")
      ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 2 stood eight
      ;; minutes over one 5 off and 5 above the end, then twenty-four over one 10 off and 3 above
      ;; that never moved; it comes the moment the bot shows itself (batch pearls-warped6-a run 4)
      (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
             (plan/next-intent (ps/looked (at [3.5 64.0 15.5]) 6) ps/row plan/goals))
          "half a minute of it not coming: out to the mouth to bring it, and straight back in")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (memory/remember-intention
                                (ps/looked (at [3.5 64.0 15.5]) 12)
                                {:intent/id 30 :intent/kind :walk :intent/target [8 61 8]}
                                :done)
                               ps/row
                               plan/goals))
          "back in from the mouth: the half minute counts again from the walk")
      (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
             (plan/next-intent (assoc (at [3.5 64.0 15.5]) :player/pos [3.5 64.0 8.5])
                               ps/row
                               plan/goals))
          "at the mouth: back in")
      (is (nil? (psense/angry-near (at [3.5 44.0 8.5])))
          "one twenty below the mouth, in another cave, never comes: no danger")
      (is (= 7 (first (psense/angry-near (at [14.5 64.0 8.5]))))
          "eleven off: within its own stare range of 16, it rushes the bot that steps out")
      (is (nil? (psense/angry-near (at [21.5 64.0 8.5])))
          "eighteen off: past danger-radius, the mouth is walked to as before"))
    ;; batch pearls-warped4-b (plan run 39596287510, probe 39616249061): the arena's endermen
    ;; wander out of the mouth's line within the first minute, and the forest's stand hidden
    ;; behind its ledges; a burrow with nothing to do from it is given up the same way
    (is (not (psense/stale? (ps/looked (ps/burrowed [8.5 61.0 8.5] {}) 11)))
        "no crowd, no minute yet")
    (is (psense/stale? (ps/looked (ps/burrowed [8.5 61.0 8.5] {}) 12))
        "a minute of lookouts with none by the mouth: given up too")))

(deftest a-calm-one-given-up-on-at-hand-gets-no-burrow-cut-for-it
  ;; batch pearls-warped6-b (plan run 39700288080) run 4: a provoke of one by the mouth failed
  ;; :unprovoked at 408 s and it stood there, calm and blacklisted; the burrow went stale after
  ;; every minute of lookouts and, with it still at hand, the hunt cut the same burrow again from
  ;; the same cell, six times in six minutes (about sixty digs), and the pickaxe wore out at
  ;; thirty-two minutes with four pearls held
  (let [calm (ps/enderman [11.5 64.0 8.5])
        w (assoc-in (ps/standing {7 calm}) [:player/inventory 1] (ps/item :iron_pickaxe 1))
        ghost (assoc w :plan/blacklist #{7})]
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "a calm one three off, not given up on: the burrow it is met from, as before")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent ghost ps/row plan/goals))
        "the same one given up on (:unprovoked): no burrow for it, the hunt looks for another")
    (is (= :fight
           (:intent/kind (plan/next-intent (assoc-in ghost [:world/entities 7 :entity/creepy?] true)
                                           ps/row
                                           plan/goals)))
        "an angry one given up on at hand is fought, as before")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 4: three endermen in
    ;; one spot 3.8 off, two blacklisted and one not; the blacklisted one nearest by a hair got
    ;; no burrow, and the walk to the other, within reach, was planned 18,760 times in fifteen
    ;; minutes
    (let [two (assoc-in ghost [:world/entities 9] (ps/enderman [11.5 64.0 8.6]))]
      (is (= 9 (first (psense/at-hand two))) "the one not given up on, though a hair farther")
      (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
             (plan/next-intent two ps/row plan/goals))
          "is met from a burrow")
      (is (nil? (pmove/routable two {9 (ps/enderman [11.5 64.0 8.6])}))
          "and a walk to one within reach is never planned: it would arrive where it begins"))))

(deftest a-calm-one-at-hand-in-the-burrow-is-waited-out-never-held-a-fight
  ;; batch pearls-warped4-b's arena run 3 (plan run 39596287510, probe 39616249061): from its
  ;; burrow's end it held a fight on a calm enderman 3.8 blocks off from 21 s to 128 s, which
  ;; never came (nothing had provoked it), blacklisted it, and found the others gone from the
  ;; arena's lit square; the run passed at 213 s where the arena's bots pass in about 50
  (let [calm (ps/enderman [4.5 62.0 8.5])
        w (ps/burrowed [8.5 61.0 8.5] {7 calm})]
    (is (= 7 (first (psense/blocking w))) "a calm one two stairs up, 4.1 off: in the stairs")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals))
        "out of the sword's reach and in the only way out: waited for where the bot stands")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (assoc-in w [:world/entities 7 :entity/pos] [5.5 61.0 8.5])
                             ps/row
                             plan/goals))
        "on the last stair, three off: within the sword's reach, struck, and the blow angers it")
    (is
     (= {:intent/kind :fight
         :intent/target 7
         :intent/hold? true
         :intent/yield? true
         :intent/for :pearls}
        (plan/next-intent (assoc-in w [:world/entities 7 :entity/creepy?] true) ps/row plan/goals))
     "the same one coming: fought where the bot stands")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :player/health 6.0) ps/row plan/goals))
        "hurt with it there: it stays in, no back-off out of the burrow")
    (is (nil? (psense/blocking (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [0.5 64.0 8.5])})))
        "one on the ground by the mouth is no block: a vantage is walked to past it")))

(deftest one-hidden-from-the-mouth-is-no-reason-to-walk-out-of-the-burrows-close-reach
  ;; batch pearls-warped4-b (plan run 39596287510): the arena's run 2 stood at its mouth with all
  ;; three endermen hidden, walked out to the nearest and met the dark's mobs. Gym plan run
  ;; 40860669235 job 4 (issue #263): a vantage five blocks from home, with an angry one tracked,
  ;; was fought in the open at 11 health. A vantage is only a cell within provoke-home-reach of
  ;; the burrow's end, which a provoke from it can be walked home from before the enderman is there
  (let [far (ps/enderman [3.5 64.0 20.5])
        w (-> (ps/burrowed [3.5 64.0 8.5] {7 far} ps/north-wall)
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (nil? (psense/quarry w)) "hidden from the mouth")
    (is (nil? (pplace/vantage w (psense/endermen w pparams/seek-radius))))
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals))
        "a lookout at the mouth, not a walk out")
    (is (=
         {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
         (plan/next-intent (assoc-in w [:world/entities 7 :entity/creepy?] true) ps/row plan/goals))
        "coming: home, where it is met")
    (is (= {:intent/kind :walk
            :intent/target (pplace/stare-site (dissoc w :pearls/shelter) far)
            :intent/for :shelter}
           (plan/next-intent (dissoc w :pearls/shelter) ps/row plan/goals))
        "with no burrow, walked to a new burrow's site it is stared at from (stare-site)")))

(deftest a-vantage-farther-from-home-than-from-the-enderman-is-no-vantage
  ;; batch pearls-warped5-b (plan run 39671293287, probe 39683564524): run 1 provoked one 7.6
  ;; blocks off from a vantage 12 from its burrow's end and was slain on the walk home at 106 s;
  ;; run 5 lost eleven health on the walk home from one provoked 15 off. An enderman comes at the
  ;; bot's pace and more: the bot must be home first
  (let [far (ps/enderman [3.5 64.0 30.5])
        w (-> (ps/burrowed [3.5 64.0 8.5] {7 far} ps/north-wall)
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (nil? (psense/quarry w)) "hidden from the mouth")
    (is (nil? (pplace/vantage w (psense/endermen w pparams/seek-radius)))
        "every cell within ten of it is more than ten from home: none")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals))
        "a lookout at the mouth; a minute of them gives the burrow up for one cut beside it")))

(deftest another-hostile-near-the-burrow-is-met-from-it-not-fought-in-the-open
  ;; gym batch pearls-warped4-a (plan run 39544677155): runs 3 and 4 were slain by other mobs the
  ;; :defend row fought and backed away from at the forest's edge, each with a burrow cut, and
  ;; the arena's run 2 of batch pearls-warped4-b by the zombies, skeleton and creeper of the
  ;; dark round the arena (#454)
  (let [zombie {:entity/type (blocks/entity-types :zombie)
                :entity/pos [6.5 64.0 8.5]
                :entity/seen-at 0
                :entity/health 20.0}
        at-mouth (ps/burrowed [3.5 64.0 8.5] {9 zombie})
        at-end (ps/burrowed [8.5 61.0 8.5] {9 (assoc zombie :entity/pos [5.5 61.0 8.5])})]
    (is (= 9 (first (psense/threat at-mouth))) "a zombie three blocks off")
    (is (contains? (combat/answering at-mouth :pearls) :zombie)
        "with a burrow near, the hunt answers it, not the :defend row")
    (is (= #{:enderman} (combat/answering (dissoc at-mouth :pearls/shelter) :pearls))
        "with none, the :defend row does, as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent at-mouth ps/row plan/goals))
        "at the mouth: back in")
    (is (= {:intent/kind :fight
            :intent/target 9
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent at-end ps/row plan/goals))
        "at the end with it on the stairs: fought where the bot stands")
    (is (nil? (psense/threat
               (assoc-in at-mouth [:world/entities 9 :entity/type] (blocks/entity-types :creeper))))
        "a creeper is the :defend row's: backed away from, and a burrow is no place for that")
    (is (nil? (psense/threat (assoc at-mouth :plan/blacklist #{9}))) "one given up on is a ghost")))

(deftest a-kill-is-picked-up-where-it-fell-a-kill-with-no-drop-is-left
  (let [kill {:intent/kind :fight
              :intent/for :pearls
              :intent/target 7
              :intent/target-pos [11.7 64.0 8.2]
              :intent/status :done}
        drop {:entity/type blocks/item-entity-type :entity/pos [11.6 64.2 8.4] :entity/seen-at 0}
        block (assoc drop :entity/item (recipe/item-id :warped_nylium))
        body (assoc (ps/enderman [11.7 64.0 8.2]) :entity/died-at 0)]
    (is (= {:intent/kind :collect-drops
            :intent/item :ender_pearl
            :intent/target [11 64 8]
            :intent/for :pearls}
           (plan/next-intent (assoc (ps/standing {9 drop}) :plan/last kill) ps/row plan/goals)
           (plan/next-intent (assoc (ps/standing
                                     {9 (assoc drop :entity/item (recipe/item-id :ender_pearl))})
                                    :plan/last
                                    kill)
                             ps/row
                             plan/goals))
        "a drop that holds a pearl, or has not said what it holds, is picked up")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (ps/standing {9 block}) :plan/last kill) ps/row plan/goals))
        "the block an enderman carried is left (#262: a drop says what it holds)")
    (is (= {:plan/wait :drop}
           (plan/next-intent (assoc (ps/standing {7 body}) :plan/last kill) ps/row plan/goals))
        "the body still lies there: its drop may still come")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (ps/standing {}) :plan/last kill) ps/row plan/goals))
        "body gone and nothing dropped: no pearl this time")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent
            (assoc (ps/standing {7 (ps/enderman [11.7 64.0 8.2]) 9 drop}) :plan/last kill)
            ps/row
            plan/goals))
        "a fight broken off left its enderman alive: no kill, and the fight goes on")
    ;; sim probe 39885574495 (world R2, seed 5): two provoked at once, one killed, and the walk
    ;; to its pearl was slain beside it by the other
    (let [angry (assoc (ps/enderman [14.5 64.0 8.5]) :entity/creepy? true)
          w #(assoc (ps/standing {9 drop 8 %}) :plan/last kill)]
      (is (psense/menaced? (w angry)))
      (is (= {:intent/kind :fight :intent/target 8 :intent/for :pearls}
             (plan/next-intent (w angry) ps/row plan/goals))
          "another angry one six off: met first, the drop left")
      (is (psense/menaced? (assoc (w angry) :plan/blacklist #{8})) "blacklisted or not")
      (is (not (psense/menaced? (w (dissoc angry :entity/creepy?)))) "a calm one is no menace")
      (is (= :collect-drops
             (:intent/kind
              (plan/next-intent (w (assoc angry :entity/pos [40.5 64.0 8.5])) ps/row plan/goals)))
          "one thirty-two off, past danger-radius: the drop is picked up"))))

(deftest a-walk-out-ends-when-an-angry-one-is-near-the-mouth-not-only-at-hand
  ;; churn-a pearls-12 run 1 (plan run 41096292443): the hunt's choice had been the way home for
  ;; 8 s while a walk to a site 12 blocks up the stairs went on; the angry one (blacklisted after
  ;; a held fight timed out) arrived with the bot 11 blocks from the end, and killed it on them
  (let [angry (assoc (ps/enderman [3.5 64.0 14.5]) :entity/creepy? true)
        out {:intent/kind :walk :intent/target [3 64 4] :intent/for :shelter :intent/status :active}
        lure
        {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter :intent/status :active}
        home
        {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter :intent/status :active}
        w (-> (ps/burrowed [4.5 63.0 8.5] {9 angry})
              (assoc :plan/blacklist #{9}))]
    (is (psense/angry-near w) "the blacklisted angry one is still near the mouth")
    (is (not (psense/beset? w)) "and not at hand")
    (is (plan/interrupts? w ps/row out) "a walk out is cut")
    (is (not (plan/interrupts? w ps/row lure)) "the lure's walk to the mouth is what it is for")
    (is (not (plan/interrupts? w ps/row home)) "the way home is never cut")
    (is (not (plan/interrupts? (assoc w :player/pos [8.5 61.0 8.5]) ps/row out))
        "nor anything at the burrow's end")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 9 :entity/creepy?] false) ps/row out))
        "nor with none angry")))

(deftest an-angry-one-known-and-no-burrow-cuts-one-here-not-a-walk-to-a-site
  ;; pearl-fight-a job 3 (plan run 41143668588): the burrow stale, enderman 964 angry 6 to 19
  ;; blocks off the whole 30 s (fought out and blacklisted, so no ce, no quarry, no angry-near),
  ;; and the hunt walked and climbed through the open toward a crowd site and a new burrow's
  ;; stairs; 964 reached it on the stairs, five hits in 12 s, held four pearls
  (let [angry (assoc (ps/enderman [8.5 64.0 14.5]) :entity/creepy? true)
        w (-> (ps/standing {9 angry})
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :plan/blacklist #{9}))]
    (is (some? (psense/menace w)) "angry, six blocks off, blacklisted: still a menace")
    (is (= :burrow (:intent/kind (plan/next-intent w ps/row plan/goals)))
        "a burrow is cut where the bot stands")
    (is (nil? (psense/menace (assoc-in w [:world/entities 9 :entity/creepy?] false)))
        "a calm one is none")))

(deftest one-given-up-as-out-of-reach-is-not-fought-again
  ;; gym plan run 40747177526 run 4 (issue #263, pearls-12): an angry enderman eight blocks below
  ;; the bot in its stair shaft was chosen as :fight eight times in 10 ms, each given up as not
  ;; strikable and ended done at once
  (let [below (assoc (ps/enderman [11.5 56.0 8.5]) :entity/creepy? true)
        w (ps/standing {7 below})
        given (combat/given-up w 7)]
    (is (= :fight (:intent/kind (plan/next-intent w ps/row plan/goals)))
        "not given up: the hunt fights it")
    (is (psense/unfightable? given 7 below) "given up and out of reach: no fight's target")
    (is (not= :fight (:intent/kind (plan/next-intent given ps/row plan/goals)))
        "so the fight is not chosen again, the hunt does something else")
    (testing "once it is within reach it is fought at once"
      (let [near (assoc below :entity/pos [9.5 64.0 8.5])
            given-near (combat/given-up (ps/standing {7 near}) 7)]
        (is (not (psense/unfightable? given-near 7 near)))))))

(deftest one-that-has-struck-and-stands-in-reach-is-fought-back
  ;; run 4 again: three hits in five seconds, 20 to 0, with a walk going on and no attack sent,
  ;; the enderman counted as fought out
  (let [angry (assoc (ps/enderman [9.5 64.0 8.5]) :entity/creepy? true)
        base (assoc (ps/standing {7 angry}) :player/health 12.0)
        out (ps/fought base pparams/fight-tries)
        walking
        (assoc
         out
         :plan/intent
         {:intent/kind :walk :intent/target [20 64 20] :intent/status :active :intent/for :pearls})]
    (is (psense/fought-out? out 7))
    (is (= 7 (first (psense/struck-by out))) "hit once (under struck-health), in reach")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent out ps/row plan/goals))
        "fought out or not, it is fought back")
    (is (plan/interrupts? walking ps/row (:plan/intent walking)) "a walk is cut by it")
    (testing "at full health it is fought out as before"
      (let [full (assoc out :player/health 20.0)]
        (is (nil? (psense/struck-by full)))
        (is (not (plan/interrupts? full ps/row (:plan/intent walking))))))
    (testing "out of the sword's reach it is not struck-by"
      (is (nil? (psense/struck-by
                 (assoc-in out [:world/entities 7 :entity/pos] [9.5 70.0 8.5])))))))

(deftest a-fight-that-failed-is-not-planned-again-until-the-enderman-is-in-reach
  ;; runs 2 and 3: :fight :unreachable eight times on one target, thirteen minutes of a held fight
  (let [far (assoc (ps/enderman [8.5 64.0 20.5]) :entity/creepy? true)
        near (assoc far :entity/pos [10.5 64.0 8.5])
        failed (fn [w]
                 (memory/remember-intention (assoc w :time/now 1000)
                                            {:intent/id 5 :intent/kind :fight :intent/target 7}
                                            :failed))
        w (failed (ps/standing {7 far}))]
    (is (psense/unfightable? w 7 far) "out of reach, just failed")
    (is (not (psense/unfightable? w 7 near)) "within reach it is fought")
    (is (not (psense/unfightable? (assoc w :time/now (+ 1000 pparams/fight-retry-ms)) 7 far))
        "after fight-retry-ms it is tried again")
    (is (not (psense/unfightable? (ps/standing {7 far}) 7 far)) "never failed: fought")))

(deftest a-swarm-is-two-monsters-near-or-one-while-hurt
  (let [w (ps/standing ps/burst)]
    (is (= #{8 9} (set (keys (psense/swarm w)))) "the enderman is the quarry, not the swarm")
    (is (psense/swarmed? w))
    (is (not (psense/swarmed? (ps/standing {8 (ps/mob :zombified_piglin [11.5 64.0 9.5])})))
        "one, at full health")
    (is (psense/swarmed?
         (assoc (ps/standing {8 (ps/mob :zombified_piglin [11.5 64.0 9.5])}) :player/health 8.0))
        "one, under danger-health")
    (is (not (psense/swarmed? (ps/standing {8 (ps/mob :magma_cube [10.5 64.0 8.5])})))
        "run 4: a lone magma cube at full health is the fight rules'")
    (is (not (psense/swarmed? (ps/standing {8 (ps/mob :zombified_piglin [11.5 64.0 9.5])
                                            9 (ps/mob :zombified_piglin [40.5 64.0 9.5])})))
        "the second is past swarm-radius")
    (testing "gym plan run 40839050567 job 5: two piglins shooting from past eight blocks"
      (is (psense/swarmed? (ps/standing {8 (ps/mob :piglin [20.5 64.0 9.5])
                                         9 (ps/mob :piglin [8.5 64.0 22.5])})))
      (is (not (psense/swarmed? (ps/standing {8 (ps/mob :zombified_piglin [20.5 64.0 9.5])
                                              9 (ps/mob :zombified_piglin [8.5 64.0 22.5])})))
          "a zombified piglin has no bow"))))

(deftest a-ghast-in-sight-is-covered-from-never-dug-under
  (let [ghast (ps/mob :ghast [15.5 60.0 8.5])
        held {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1) 2 (ps/item :cobblestone 64)}
        at (fn [entities blocks]
             (-> (ps/standing entities blocks)
                 (assoc :player/pos [8.5 60.0 8.5] :player/inventory held)))
        down-the-cut (at {7 ghast} ps/tunnel)
        stairs {:intent/kind :stairs-down
                :intent/status :active
                :intent/goal :ender-pearls
                :intent/for :pearls}]
    (is (psense/shot-at? down-the-cut) "in sight down the tunnel")
    (is (psense/swarmed? down-the-cut))
    (is (not (psense/shot-at? (at {7 (ps/mob :ghast [15.5 60.0 8.5])} {})))
        "in solid stone: no line")
    (is (plan/interrupts? down-the-cut ps/row stairs) "a staircase is cut")
    (is (nil? (pcover/hide-cell down-the-cut (get-in down-the-cut [:world/entities 7])))
        "a tunnel two high with straight walls has no cell out of the line beside the feet")
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [9 61 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent down-the-cut ps/row plan/goals))
        "a block on the line, the first open cell with a wall beside it")
    (testing "with no filler held, a hole is dug on the side away from it"
      (let [no-filler (assoc down-the-cut :player/inventory {0 (ps/item :iron_pickaxe 1)})]
        (is (= {:intent/kind :dig :intent/target [7 60 8] :intent/for :pearls}
               (pcover/cover-from no-filler)))))))

(deftest a-provoke-keeps-two-hits-of-health-and-no-walk-out-over-an-angry-one
  ;; gym plan run 40860669235 job 4 (issue #263, pearls-12): provoked at about 11 health, then
  ;; the walk between the burrow's end and its mouth ten times in 17 s, then a fight in the open
  ;; and three hits of 5.7
  (let [mail (fn [w]
               (-> w
                   (assoc-in [:player/inventory (armour/worn-slot 0)] (ps/item :iron_helmet 1))
                   (assoc-in [:player/inventory (armour/worn-slot 1)]
                             (ps/item :iron_chestplate 1))))
        sighted (ps/enderman [3.5 64.0 0.5])
        at-mouth (-> (ps/burrowed [3.5 64.0 8.5] {7 sighted})
                     (assoc :player/food 20)
                     mail)
        calm-end (-> (ps/burrowed [8.5 61.0 8.5] {})
                     mail)
        angry-end (-> (ps/burrowed [8.5 61.0 8.5]
                                   {7 (assoc (ps/enderman [3.5 64.0 14.5]) :entity/creepy? true)})
                      mail)]
    (is (< 5.7 (psense/enderman-hit at-mouth) 5.8) "7 through iron helmet and chestplate")
    (is (< 14.4 (psense/provoke-line at-mouth) 14.6) "3 over two hits")
    (is (= 7.0 (psense/enderman-hit (ps/burrowed [3.5 64.0 8.5] {}))) "no armour: all of it")
    (is (= :provoke
           (:intent/kind
            (plan/next-intent (assoc at-mouth :player/health 15.0) ps/row plan/goals))))
    (is (not= :provoke
              (:intent/kind
               (plan/next-intent (assoc at-mouth :player/health 14.0) ps/row plan/goals)))
        "under the line: home, not a provoke")
    (is (= [:walk [3 64 8]]
           ((juxt :intent/kind :intent/target) (plan/next-intent calm-end ps/row plan/goals)))
        "nothing coming: out to look round from the mouth")
    (is (not= [:walk [3 64 8]]
              ((juxt :intent/kind :intent/target) (plan/next-intent angry-end ps/row plan/goals)))
        "an angry one tracked: the bot stays in, where the walk out sent it home again")))

(deftest a-calm-one-crowding-the-mouth-is-no-reason-to-walk-out-past-an-angry-one
  ;; gym plan run 40860669235 job 4 (issue #263, pearls-12), 6.30 min: at home with a calm
  ;; enderman three blocks from the mouth and an angry one thirteen away below, the hunt chose a
  ;; vantage five blocks out, was fought in the open at 11 health there and slain
  (let [calm (ps/enderman [5.5 64.0 8.5])
        angry (assoc (ps/enderman [8.5 50.0 18.5]) :entity/creepy? true)
        w (assoc (ps/burrowed [8.5 61.0 8.5] {7 calm 8 angry}) :player/food 20)
        i (plan/next-intent w ps/row plan/goals)]
    (is (psense/crowded? w) "the calm one crowds the mouth")
    (is (nil? (pplace/vantage w (psense/endermen w pparams/seek-radius))))
    (is (not= :walk (:intent/kind i)) (pr-str i))))

(deftest a-fight-cut-three-times-in-ten-minutes-is-not-planned-again
  ;; gym plan run 40860669235 job 3 (issue #263, pearls-12): :fight 65 held twelve seconds, cut,
  ;; and planned again after thirty, every 47 seconds for 286 minutes, 80 of them in a fight with
  ;; thirty-three attacks sent: an angry enderman six blocks off and three below that never came
  (let [far (assoc (ps/enderman [8.5 61.0 14.5]) :entity/creepy? true)
        near (assoc far :entity/pos [10.5 64.0 8.5])
        cut (fn [w at id]
              (memory/remember-intention (assoc w :time/now at)
                                         {:intent/id id :intent/kind :fight :intent/target 7}
                                         :abandoned))
        base (ps/standing {7 far})
        w (-> base
              (cut 0 1)
              (cut 50000 2)
              (cut 100000 3)
              (assoc :time/now 140000))]
    (is (psense/unfightable? w 7 far)
        "three cuts in ten minutes, the last long past fight-retry-ms")
    (is (not (psense/unfightable? w 7 near)) "in reach, it is fought")
    (is (not (psense/unfightable? (-> base
                                      (cut 0 1)
                                      (cut 50000 2)
                                      (assoc :time/now 140000))
                                  7
                                  far))
        "two cuts: tried again")
    (is (not (psense/unfightable? (assoc w :time/now (+ 100000 pparams/fight-giveup-ms 1)) 7 far))
        "ten minutes on, tried again")))

(deftest one-enderman-at-a-time-is-provoked
  ;; churn-a (plan 41096292443) pearls-12 run 3 at 59.47 to 59.69 min (issue #263): :provoke 1310,
  ;; :provoke 1561 half a second on, :provoke 1691 0.2 min later; at 59.73 the three stood within
  ;; 2.5 blocks of the bot, and the last of them struck it five times in five seconds
  (let [sighted (ps/enderman [3.5 64.0 0.5])
        coming (assoc (ps/enderman [3.5 64.0 20.5]) :entity/creepy? true)
        w (assoc (ps/burrowed [3.5 64.0 8.5] {7 sighted}) :player/food 20)
        busy (assoc-in w [:world/entities 8] coming)]
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "none angry: provoked from the mouth")
    (is (not= :provoke (:intent/kind (plan/next-intent busy ps/row plan/goals)))
        "another angry and coming: not a second")
    (let [given-up (-> busy
                       (assoc :plan/given-up {8 0})
                       (assoc-in [:world/entities 8 :entity/pos] [3.5 64.0 30.5]))]
      (is (psense/coming? busy))
      (is (not (psense/coming? given-up)) "given up on and out of reach: it is not coming")
      (is
       (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
          (plan/next-intent given-up ps/row plan/goals))
       "so it blocks nothing; at the mouth the bot goes home first (an angry one tracked sends
           it there), where the next plan provokes"))))

(deftest only-a-weak-mob-at-hand-is-fought-with-no-burrow
  ;; gym plan run 41109441111 (pearls-travel-a) job 5: `walk [2396 72 2184]`, `fight 744`, three
  ;; hits of a piglin in four seconds, dead at 39 minutes
  (let [zombie (ps/mob :zombie [11.5 64.0 8.5])
        piglin (ps/mob :piglin [11.5 64.0 8.5])]
    (is (psense/weak? zombie))
    (is (not (psense/weak? piglin)) "it shoots and its hit is not in mob/kinds")
    (is (= :fight (:intent/kind (plan/next-intent (ps/standing {7 zombie}) ps/row plan/goals))))
    (is (not= :fight
              (:intent/kind (plan/next-intent (ps/standing {7 piglin}) ps/row plan/goals))))))

(deftest an-angry-one-is-told-parked-from-stalking-by-its-motion
  ;; travel-b job 6 (plan 41134469333, issue #263): enderman 51 parked at 12 blocks for 41 minutes,
  ;; the wander held in; pearl-fight-a job 3: a blacklisted angry one stalked at 6 to 19 blocks for
  ;; 30 s and hit five times
  (let [at (fn [pos] (assoc (ps/enderman pos) :entity/creepy? true))
        w (-> (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 (at [8.5 61.0 20.5])}))
              (ps/walked (take pparams/loiter-walks (cycle [[8 61 8] [10 61 8]])))
              (assoc :time/now 0))
        tracked (psense/track-angry w)
        later (fn [w ms]
                (-> w
                    (assoc :time/now ms)
                    psense/track-angry))]
    (is (= {7 {:since 0 :best 12.0 :closed-at 0}} (:pearls/angry tracked)))
    (testing "parked 41 minutes at twelve blocks: not a menace, the wander goes"
      (let [parked (later tracked (* 41 60000))]
        (is (psense/parked? parked 7 [8.5 61.0 20.5]))
        (is (not (psense/live-menaced? parked)))))
    (testing "seen only half a minute: still a menace, the wander stays in"
      (let [fresh (later tracked 30000)]
        (is (not (psense/parked? fresh 7 [8.5 61.0 20.5])))
        (is (psense/live-menaced? fresh))))
    (testing "one that stalks: closing in resets the clock"
      (let [t1 (later tracked 50000)
            near (-> t1
                     (assoc-in [:world/entities 7 :entity/pos] [8.5 61.0 16.5])
                     (later 100000))
            parked-again (later near 130000)]
        (is (= 100000 (get-in near [:pearls/angry 7 :closed-at])) "four blocks nearer: closing")
        (is (not (psense/parked? parked-again 7 [8.5 61.0 16.5])) "only thirty seconds since")
        (is (psense/live-menaced? parked-again))))
    (testing "within park-near it is never parked"
      (let [on-us (-> tracked
                      (assoc-in [:world/entities 7 :entity/pos] [8.5 61.0 14.5])
                      (later (* 41 60000)))]
        (is (not (psense/parked? on-us 7 [8.5 61.0 14.5])) "6 blocks")))))

(deftest the-menace-is-the-angry-one-within-eight-blocks
  ;; the two logged states of an-angry-one-is-told-parked-from-stalking-by-its-motion, for the
  ;; burrow cut where the bot stands: travel-b job 6's enderman parked at twelve blocks for 41
  ;; minutes is no menace (live-menaced? alone holds the wander), pearl-fight-a job 3's stalker
  ;; at six blocks is one, blacklisted or not
  (let [angry (assoc (ps/enderman [8.5 64.0 20.5]) :entity/creepy? true)
        w (-> (ps/standing {9 angry})
              (assoc :plan/blacklist #{9}))]
    (is (nil? (psense/menace w)) "twelve blocks off")
    (is (some? (psense/menace (assoc-in w [:world/entities 9 :entity/pos] [8.5 64.0 14.5])))
        "six blocks off")))

(deftest a-walk-to-cover-and-a-walk-away-do-not-alternate
  ;; pearls-fight-c job 1 (plan run 41155212096, issue #263): walk [2474 100 2232] (a cell beside the
  ;; feet) and walk [2466 73 2292] (far) alternated every second, 23 swings in 5.6 s, a ghast shooting,
  ;; 13 hits, dead at 1m48
  (let [base (-> (ps/wide (ps/standing {}))
                 (assoc :player/dimension ps/nether
                        :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}
                        :time/now 8000))
        far? #(= [:walk :pearls]
                 ((juxt :intent/kind :intent/for) (plan/next-intent % ps/row plan/goals)))]
    (is (far? base) "nothing in sight: travels, as before")
    (is (not (far? (assoc base :pearls/shot-at-ms 5000)))
        "shot at three seconds ago: stays where it is")
    (is (far? (assoc base :pearls/shot-at-ms 5000 :time/now 16000)) "eleven seconds on: travels")
    (is (psense/recently-shot? (assoc base :pearls/shot-at-ms 5000)))
    (is (not (psense/recently-shot? base)))))

(deftest a-lone-piglin-is-avoided-not-fought-in-the-open
  ;; pearls-travel-d job 5 (plan run 41156746096, issue #263): 73 minutes, two pearls, then :defend
  ;; fought a piglin 2.8 blocks away with no burrow yet: two swings, hits of 6.7, dead
  (let [piglin (ps/mob :piglin [11.5 64.0 8.5])
        zombie (ps/mob :zombie [11.5 64.0 8.5])
        zpig (ps/mob :zombified_piglin [11.5 64.0 8.5])
        w (fn [m]
            (assoc (ps/standing {7 m})
                   :plan/goals #{:ender-pearls}
                   :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :cobblestone 64)}))]
    (is (psense/swarmed? (w piglin)) "one piglin at full health")
    (is (not (psense/swarmed? (w zombie))) "one zombie: the fight rules")
    (is (not (psense/swarmed? (w zpig))) "a zombified piglin is neutral until it is hit")
    (is (contains? (combat/answering (w piglin) :pearls) :piglin) ":defend leaves it to the hunt")
    (is (not (contains? (combat/answering (w zombie) :pearls) :zombie)))
    (testing "only when the pearl stage is in play: at an earlier stage a silverfish is :defend's"
      (let [early (assoc (w (ps/mob :silverfish [11.5 64.0 8.5])) :plan/goals #{:wood})]
        (is (not (fexit/pearls-stage? early)))
        (is (not (contains? (combat/answering early :pearls) :piglin)))
        (is (not (contains? (combat/answering early :pearls) :silverfish))))
      (is (fexit/pearls-stage?
           (assoc (w piglin) :plan/goals #{:wood} :plan/reached #{:blaze-rods}))))
    (is (not= :fight (:intent/kind (plan/next-intent (w piglin) ps/row plan/goals))))))

(deftest a-parked-enderman-jittering-a-block-or-two-stays-parked
  ;; pearl-fight-g job 3 (churn-guard's probe 41174965115): enderman 75 sat angry at 9 to 11 blocks for
  ;; 90 minutes and its best distance and :closed-at kept resetting at a block of jitter
  (let [at (fn [z] (assoc (ps/enderman [8.5 61.0 z]) :entity/creepy? true))
        w (-> (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 (at 20.5)}))
              (assoc :time/now 0)
              psense/track-angry)
        step (fn [w z ms]
               (-> w
                   (assoc-in [:world/entities 7] (at z))
                   (assoc :time/now ms)
                   psense/track-angry))
        jittered (reduce (fn [w [z ms]] (step w z ms))
                         w
                         [[19.5 10000] [20.5 20000] [19.0 30000] [20.0 40000] [19.5 50000]
                          [20.5 60000]])]
    (is (= 0 (get-in jittered [:pearls/angry 7 :closed-at])) "twelve blocks, a block of jitter")
    (is (psense/parked? (assoc jittered :time/now 70000) 7 [8.5 61.0 20.5]))))

(deftest a-blaze-behind-a-wall-is-no-swarm-and-one-in-sight-sends-the-bot-back-along-its-trail
  ;; race 9d15bd69b (lanes 41244936722 and 41245191737): Clj_r24, r35, r04, r02 and r11 held 7 to 9
  ;; rods and died on the pearl stage in the blaze room, \`swarmed?\` by a blaze 19 to 29 blocks off,
  ;; digging down and pillaring in its fireballs (hp 20 to 3.3 in 37 s, probe 41251668076); the
  ;; hunt's travel is gated on swarmed? and nothing walked them out
  (let [blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [14.5 64.0 8.5]
               :entity/seen-at 0
               :entity/health 20.0}
        stone (blocks/default-state :stone)
        wall (into {} (for [y (range 64 68) z (range 6 11)] [[5 y z] stone]))
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] stone]))
        w (-> (ps/standing {9 blaze})
              (assoc :world/chunks {[0 0] (world/column (merge floor wall))})
              (assoc :fortress/trail {:trail/target [20 64 8] :trail/cells [[1 64 8] [3 64 8]]}))
        walled (assoc-in w
                [:world/chunks [0 0]]
                (world/column
                 (merge floor (into {} (for [y (range 64 68) z (range 4 14)] [[11 y z] stone])))))]
    (is (psense/swarmed? w) "a blaze in the open sight of the bot is a swarm")
    (let [i (plan/next-intent w ps/row plan/goals)]
      (is (= :walk (:intent/kind i)) (pr-str i))
      (is (= [3 64 8] (:intent/target i)) "the newest trail cell behind the wall")
      (is (= :cover (:intent/for i))))
    (is (not (psense/swarmed? walled)) "a wall between the bot and the blaze: no swarm")
    (is (nil? (pcover/leave-sight walled)) "and nothing to leave")))

(deftest a-walk-that-sets-only-its-heading-is-turned-from-a-calm-endermans-eyes
  ;; pearl-fight-p jobs 2 and 3 (plan run 41256381552, probe 41268180994): 20 of 22 endermen turned
  ;; angry during a :walk whose look stared at their head for 8 to 13 ticks before, at 5 to 47 blocks
  ;; (job 2 tick 1908: look [61.3 -17.3] at 5 blocks); look-off only turned an explicit
  ;; :control/look, and a walk sets only :control/yaw
  (let [calm (ps/enderman [14.5 64.0 8.5])
        w0 (ps/standing {7 calm})
        eye (game/eye w0)
        [yaw pitch] (physics/look-at eye (mob/head :enderman [14.5 64.0 8.5]))
        walking (-> w0
                    (assoc :player/look [yaw pitch])
                    (assoc :player/controls {:control/yaw yaw :control/forward? true})
                    (assoc :plan/intent
                           {:intent/kind :walk
                            :intent/target [20 64 8]
                            :intent/for :pearls
                            :intent/status :active}))
        out (plan/steer walking ps/row)]
    (is (mob/stared? (psense/sight-fn w0) eye [yaw pitch] [14.5 64.0 8.5])
        "the heading stares at it")
    (is (= [yaw 90.0] (get-in out [:player/controls :control/look])) "turned straight down")
    (is (:control/forward? (:player/controls out)) "still walking")
    (testing "a heading away from it is left alone"
      (let [away (-> walking
                     (assoc :player/look [(+ yaw 180.0) 0.0])
                     (assoc-in [:player/controls :control/yaw] (+ yaw 180.0)))]
        (is (nil? (get-in (plan/steer away ps/row) [:player/controls :control/look])))))))

(deftest an-angry-enderman-near-keeps-the-bot-in-its-burrow-and-from-relocating
  ;; pearl-fight-p job 3 (plan run 41256381552): the burrow went stale with 3217 angry 7 to 8 blocks
  ;; off, the hunt walked out to a new site and was slain 3 blocks from it at 96 minutes
  (let [angry (assoc (ps/enderman [20.5 61.0 8.5]) :entity/creepy? true)
        w (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 angry}) 12)
        alone (ps/looked (ps/burrowed [8.5 61.0 8.5] {}) 12)]
    (is (psense/stale? alone) "no enderman: a minute of lookouts gives the burrow up")
    (is (nil? (pplace/shelter alone)))
    (is (psense/angry-within? w pparams/hold-radius))
    (is (some? (pplace/shelter w)) "an angry one 12 blocks off: the burrow is held")
    (let [far (assoc-in w [:world/entities 7 :entity/pos] [60.5 61.0 8.5])]
      (is (not (psense/angry-within? far pparams/hold-radius)) "52 blocks off: no hold"))))

(deftest corridor-blazes-are-claimed-from-defend-and-covered-from-not-charged-with-a-sword
  ;; Real race 8b3f2e1, Clj_r38 (run 41352110693 lane 5) and Clj_r13 (run 41354774759 lane 7):
  ;; struck by a blaze fireball in fortress corridors on the way out of blaze-rods, :player/attacker
  ;; was set, claims-attacker? :pearls returned false, and the priority-0 :defend row took over,
  ;; commanding an open melee fight with an iron sword against a flying blaze until incinerated.
  ;; In Clj_r14 (run 41352762409 lane 6), shot-at? was false for blazes and cover-from was not called,
  ;; while Clj_r44 never ate to heal before tunneling out.
  (let [blaze-id 42
        blaze {:entity/type (blocks/entity-types :blaze)
               :entity/pos [8.5 64.0 14.5]
               :entity/seen-at 0
               :entity/health 20.0}
        nether-brick (blocks/default-state :nether_bricks)
        floor (into {} (for [x (range 0 16) z (range 0 16)] [[x 63 z] nether-brick]))
        corridor-walls (into {}
                             (concat (for [y [64 65] z (range 0 16)] [[6 y z] nether-brick])
                                     (for [y [64 65] z (range 0 16)] [[10 y z] nether-brick])))
        blocks (merge floor corridor-walls)
        base (-> (ps/standing {blaze-id blaze} blocks)
                 (assoc :player/dimension "minecraft:the_nether"
                        :player/pos [8.5 64.0 8.5]
                        :player/health 14.0
                        :player/food 16
                        :plan/status :active
                        :plan/goals #{:ender-pearls}
                        :plan/reached #{:blaze-rods}
                        :plan/active [ps/row]
                        :player/attacker blaze-id)
                 (update :player/inventory assoc
                         1 (ps/item :cooked_beef 12)
                         2 (ps/item :cobblestone 64)
                         3 (ps/item :iron_pickaxe 1)))]
    (testing
      "struck by a blaze fireball, pearls claims the attacker so defend does not fight in the open"
      (is (combat/claims-attacker? base :pearls blaze))
      (is (empty? (combat/threats base)) "blaze is answered by pearls, not defend")
      (let [i (plan/next-intent base ps/row plan/goals)]
        (is (not= :fight (:intent/kind i)) "never commands an open melee fight against the blaze")))
    (testing "blaze in sight is recognized by shot-at? and covered from"
      (is (psense/shot-at? base) "blaze is recognized as a ranged shooter")
      (let [c (pcover/cover-from base)]
        (is (some? c) "cover is found in the corridor")
        (is (contains? #{:place :dig :walk} (:intent/kind c))
            "places a wall, digs, or walks to cover")))
    (testing "an injured bot out of shooter line of sight eats before venturing out"
      (let [wall-off (into {} (for [x (range 7 10) y [64 65]] [[x y 11] nether-brick]))
            safe-world (-> base
                           (assoc :world/chunks {[0 0] (world/column (merge blocks wall-off))})
                           (dissoc :player/attacker))
            i (plan/next-intent safe-world ps/row plan/goals)]
        (is (empty? (filter #(combat/seen? safe-world (val %)) (psense/shooters safe-world)))
            "shooters are blocked by the wall")
        (is (= :eat (:intent/kind i)) "eats meal to restore health before corridor travel")))))

(deftest a-brutes-ground-is-remembered-and-kept-clear-of
  (let [b (ps/standing {9 (ps/mob :piglin_brute [14.5 64.0 8.5])})
        noted (psense/track-brutes b)
        gone (assoc noted :world/entities {})]
    (is (= 9 (first (psense/brute-near b))) "within brute-radius")
    (is (= #{[1 1]} (avoided/all-of noted :brute-zones)) "its eight-block square")
    (is (psense/near-brute-zone? gone [30 64 8]) "remembered once out of tracking")
    (is (not (psense/near-brute-zone? gone [60 64 60])) "brute-clear past its square, free")
    (is (= gone (psense/track-brutes gone)) "nothing tracked, nothing noted")
    (is (nil? (psense/brute-near (ps/standing {9 (ps/mob :piglin [14.5 64.0 8.5])})))
        "a piglin is no brute")))
