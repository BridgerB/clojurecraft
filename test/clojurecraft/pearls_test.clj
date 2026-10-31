(ns clojurecraft.pearls-test
  "Ender pearls by hunting endermen (issue #263): the hunt's choices over canned worlds, the
   provoke tick by tick against vanilla's stare test, the kill's drop, and a hunt against the
   server model's enderman."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.sword :as sword]
            [clojurecraft.world :as world]
            [datascript.core :as d]))

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

(defn looked-out
  "w after a lookout that found nothing: the hunt's next choice is a roam."
  [w]
  (assoc w :time/now 0 :plan/last {:intent/kind :lookout :intent/status :done}))

(deftest a-lookout-that-found-nothing-roams-somewhere-else
  ;; gym batch pearls-263e (plan run 38905343997): forty minutes of looking round from one place
  (let [w (-> (standing {})
              (assoc :time/now 0)
              (assoc-in [:world/chunks [1 0]] (world/column {})))
        {:intent/keys [kind target for]} (plan/next-intent (looked-out w) row plan/goals)]
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals)))
    (is (= [:walk :pearls] [kind for]))
    (is
     (and (>= (first target) 20) (= 64 (second target)))
     "east first at this hour, twelve blocks or more on the floor (sixteen from the point
         behind the feet by the route's own measure, octile and height)")))

(def rock-y (range 64 67))

(defn cave
  "Four chunk columns (x 0 to 31, z 0 to 31) of stone three high over the floor (y 64 to 66),
   with the cells of open (world coordinates) left as air: a cave inside the rock."
  [open]
  (into {}
        (for [cx [0 1]
              cz [0 1]]
          [[cx cz]
           (world/column (into {}
                               (for [lx (range 16)
                                     lz (range 16)
                                     y rock-y
                                     :let [x (+ (* 16 cx) lx)
                                           z (+ (* 16 cz) lz)]
                                     :when (not (contains? open [x y z]))]
                                 [[lx y lz] stone])))])))

(defn passage
  "The cells of a passage two high from the feet at [8 64 8]: north along x 8 to z, then east
   along that z to x."
  [z x]
  (set (concat (for [pz (range 8 (inc z)) y [64 65]] [8 y pz])
               (for [px (range 8 (inc x)) y [64 65]] [px y z]))))

(deftest a-roam-with-every-end-spent-walks-to-the-farthest-again
  ;; gym batch pearls-warped9-a (plan run 39898400239) run 1: three pearls by about minute
  ;; forty, then four hours of lookouts in one place with no enderman tracked and nothing for
  ;; the roam to go after. Never one of the last two ends walked to, though: gym batch
  ;; pearls-w1 (plan run 40387599204) run 2 walked between [2474 63 2168] and [2487 63 2168]
  ;; 1,443 times to the five-hour timeout, each the farthest spent end from the other
  (let [col (world/column {})
        w (-> (standing {})
              (assoc :time/now 0 :world/chunks (zipmap [[0 0] [1 0] [0 1] [1 1]] (repeat col))))
        spent (loop [w w
                     n 0
                     seen []]
                (let [to (:intent/target (pearls/roam w))]
                  (if (and to (not (contains? (set seen) to)) (< n 40))
                    (recur (memory/remember-intention
                            w
                            {:intent/id (+ 100 n) :intent/kind :walk :intent/target to}
                            :done)
                           (inc n)
                           (conj seen to))
                    [w to seen])))
        [w* to seen] spent
        recent (set (take-last 2 seen))
        allowed (remove recent seen)
        back
        (memory/remember-intention w* {:intent/id 200 :intent/kind :walk :intent/target to} :done)
        again (:intent/target (pearls/roam back))]
    (is (< 2 (count seen)) "ends to spend, and more than the last two")
    (is (contains? (set seen) to) "every end a route reaches spent: a far one is walked to again")
    (is (not (contains? recent to)) "never where it stands or where it just came from")
    (is (>= (path/octile [8 64 8] to) (- (apply max (map #(path/octile [8 64 8] %) allowed)) 2))
        "the farthest of the rest a route reaches (ends the search only came to may lie farther)")
    (is (= {:intent/kind :walk :intent/target to :intent/for :pearls} (pearls/roam w*)))
    (is (not (contains? #{to (last seen)} again))
        "arrived there, the next revisit is neither it nor the end before it: no ping-pong")))

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
        w0 (-> (standing {})
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
        e (enderman [2395.5 68.0 2160.5])
        w-with-mob
        (assoc w :world/entities {7 e} :plan/last {:intent/kind :lookout :intent/for :pearls})
        next-intent (plan/next-intent w-with-mob row plan/goals)]
    (is (not= :walk (:intent/kind next-intent))
        "with an enderman tracked, never walks back to a spent roam end")
    (is (contains? #{:stairs-up :stairs-down :tunnel :fight :burrow :lookout}
                   (:intent/kind next-intent))
        "engages tracked enderman via cut or fight")))

(deftest a-roam-follows-a-caves-passage-to-where-it-can-look-from
  ;; gym batch pearls-263f (plan run 38918584959, probe 39130057525): a landing in a cave pocket
  ;; under the forest, netherrack sixteen blocks off in every heading at every height; the roam
  ;; asked for those cells and stood forty minutes. A route finds the passage
  (let [w (assoc (standing {}) :world/chunks (cave (passage 20 26)))
        {:intent/keys [kind target for]} (plan/next-intent (looked-out w) row plan/goals)]
    (is (= [:walk :pearls] [kind for]))
    (is (= [64 20] [(second target) (nth target 2)]) "along the passage's east arm")
    (is (>= (first target) 18) "to where it is sixteen blocks off, as the way goes")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (looked-out (assoc w :world/chunks (cave (passage 12 8))))
                             row
                             plan/goals))
        "a pocket no route leaves: the lookout again")
    ;; gym batch pearls-warped2-a (plan run 39179234118) run 3: three flights down to the
    ;; endermen's level ended inside the rock, none in sight and no route, and the bot walked its
    ;; staircase up and down for thirty minutes
    (is (= {:intent/kind :tunnel :intent/dir [0 1] :intent/length 8 :intent/for :pearls}
           (plan/next-intent (-> (standing {7 (enderman [8.5 64.0 20.5])})
                                 (assoc :world/chunks (cave (passage 8 8)))
                                 (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))
                             row
                             plan/goals))
        "a pocket with an enderman twelve blocks off through the rock: a tunnel toward it")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 4: fifteen tunnels
    ;; east and west over one 5 blocks off and 4 below in a cave, about 100 digs in a minute
    (let [pocket (-> (standing {7 (enderman [8.5 60.0 13.5])})
                     (assoc :world/chunks (cave (passage 8 8)))
                     (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))]
      (is (nil? (pearls/bore pocket (pearls/endermen pocket pearls/descend-radius)))
          "one five off through the rock, four below: a tunnel of eight would run past it")
      (let [eight (assoc-in pocket [:world/entities 7 :entity/pos] [8.5 60.0 16.5])]
        (is (= :tunnel
               (:intent/kind (pearls/bore eight (pearls/endermen eight pearls/descend-radius))))
            "eight off: the tunnel ends beside it")))))

(def cliff
  "Stone six high (y 64 to 69) across z 20 to 31 of the chunk column [0 1]: a ledge no route
   climbs, from the floor at y 64 south of it."
  (into {} (for [lx (range 16) lz (range 4 16) y (range 64 70)] [[lx y lz] stone])))

(deftest an-enderman-no-route-reaches-is-walked-toward-as-far-as-the-route-goes
  ;; gym batch pearls-263f (plan run 38918584959, probe 39130057525): fifteen endermen tracked
  ;; from the forest floor, every route to them :none, and thirty minutes of looking round
  (let [w (-> (standing {7 (enderman [8.5 70.0 30.5])})
              (assoc-in [:world/chunks [0 1]] (world/column cliff)))]
    (is (nil? (pearls/quarry w)) "on the ledge, out of sight behind its edge")
    (is (= {:intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "to the cliff's foot, the route's end, eleven blocks nearer it")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :player/pos [8.5 64.0 19.5]) row plan/goals))
        "from the foot no route gains on it: the lookout")))

(deftest a-walk-toward-one-is-never-to-a-place-already-in-reach
  ;; gym batch pearls-warped-a (plan run 39131478416) run 3: the route's end three blocks off and
  ;; a step up, four nearer by the route's measure and within a walk's reach of the eye, so the
  ;; walk arrived where it began and was planned again every tick for minutes
  (let [w (-> (standing {7 (enderman [8.5 70.0 30.5])})
              (assoc :player/pos [8.5 64.0 16.5])
              (assoc-in [:world/chunks [0 1]] (world/column (assoc cliff [8 64 3] stone))))]
    (is (nil? (pearls/toward w (pearls/endermen w pearls/seek-radius))))
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals)))
    (is (= {:intent/kind :walk :intent/target [8 65 19] :intent/for :pearls}
           (plan/next-intent (assoc w :player/pos [8.5 64.0 13.5]) row plan/goals))
        "from farther back, the step at the cliff's foot is a walk")))

(deftest a-walk-toward-one-never-ends-where-a-walk-already-arrived
  ;; gym batch pearls-warped3-a (plan run 39529384355, probe 39543118281) run 3: for twenty-five
  ;; minutes the bot walked between the ends of the routes toward two endermen, each end four
  ;; nearer one of them than the other end was; run 1 walked between its burrow's mouth and one
  ;; such end, ninety-eight times
  (let [w (-> (standing {7 (enderman [8.5 70.0 30.5])})
              (assoc :time/now 0)
              (assoc-in [:world/chunks [0 1]] (world/column cliff)))
        been (memory/remember-intention
              w
              {:intent/id 1 :intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
              :done)]
    (is (= #{[8 64 19]} (memory/arrived been)) "where a walk arrived is a fact")
    (is (= {:intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "the cliff's foot, not yet walked to")
    (is (nil? (pearls/toward been (pearls/endermen been pearls/seek-radius)))
        "walked to once: not again")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent been row plan/goals))
        "the lookout instead")
    (let [open (-> (standing {})
                   (assoc :time/now 0)
                   (assoc-in [:world/chunks [1 0]] (world/column {})))
          end (:intent/target (pearls/roam open))
          roamed (memory/remember-intention
                  open
                  {:intent/id 2 :intent/kind :walk :intent/target end :intent/for :pearls}
                  :done)]
      (is (some? end) "a roam east on open ground")
      (is (not= end (:intent/target (pearls/roam roamed)))
          "roamed to once: the next heading, somewhere else"))))

(deftest a-roam-with-every-near-end-walked-to-goes-farther
  ;; batch pearls-warped6-b (plan run 39700288080, probe 39711523945) run 5: six pearls in
  ;; twenty-two minutes from one stretch of the floor, then no enderman within 64 for most of
  ;; the last sixteen, every end the roam found within thirty blocks already walked to (86 of
  ;; them), and lookouts from the twenty-seventh minute to the end
  (let [open (reduce #(assoc-in %1 [:world/chunks [%2 0]] (world/column {}))
                     (assoc (standing {}) :time/now 0)
                     [1 2 3])
        far? (fn [c] (>= (path/octile [8 64 8] c) (* 2 pearls/roam-reach)))
        roamed (fn [[w ends] n]
                 (if-let [end (:intent/target (pearls/roam w))]
                   [(memory/remember-intention w
                                               {:intent/id n :intent/kind :walk :intent/target end}
                                               :done) (conj ends end)]
                   (reduced [w ends])))
        [_ ends] (reduce roamed [open []] (range 12))]
    (is (= (count ends) (count (set ends))) "each end somewhere else")
    (is (not (far? (first ends))) "the first sixteen off")
    (is (some far? ends) "the near ends spent, one thirty-two off or more")
    (is (= 64 (second (first (filter far? ends)))) "on the floor, by the route's own measure")))

(deftest a-worn-pickaxe-is-kept-for-the-burrow
  ;; gym batch pearls-warped3-a (plan run 39529384355) run 1: three kills from a burrow, then the
  ;; stairs, tunnels and bores toward endermen wore the iron pickaxe out at fourteen minutes, and
  ;; with no burrow to be cut it stood in :lookout for the last twenty-five
  (let [worn (assoc (item :iron_pickaxe 1) :components {:damage 220})
        below (enderman [8.5 40.0 8.5])
        w (assoc-in (standing {7 below}) [:player/inventory 1] worn)
        pocket (-> (standing {7 (enderman [8.5 64.0 20.5])})
                   (assoc :world/chunks (cave (passage 8 8)))
                   (assoc-in [:player/inventory 1] worn))]
    (is (= 30 (pearls/digs-left w)) "an iron pickaxe has 250 digs, 220 spent")
    (is (not (pearls/spare? w)) "fewer than a burrow's twice over")
    (is (pearls/spare? (assoc-in w [:player/inventory 1 :components :damage] 218)))
    (is (nil? (pearls/descend w {7 below})) "thirty digs left: no stairs down toward one")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals)))
    (is (nil? (pearls/bore pocket (pearls/endermen pocket pearls/descend-radius)))
        "nor a tunnel toward one")
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent
            (assoc-in (standing {7 (enderman [11.5 64.0 8.5])}) [:player/inventory 1] worn)
            row
            plan/goals))
        "the burrow is what the digs are kept for")))

(defn rock
  "Four chunk columns (x 0 to 31, z 0 to 31) of stone from the floor up to y 79, with the cells of
   open (world coordinates) left as air, under the floor too: a pocket deep in the rock, open to
   the sky from y 80."
  [open]
  (into {}
        (for [cx [0 1]
              cz [0 1]]
          [[cx cz]
           (world/column (into (into {}
                                     (for [[x y z] open
                                           :when (and (< y 64)
                                                      (= [cx cz] [(quot x 16) (quot z 16)]))]
                                       [[(mod x 16) y (mod z 16)] 0]))
                               (for [lx (range 16)
                                     lz (range 16)
                                     y (range 64 80)
                                     :let [x (+ (* 16 cx) lx)
                                           z (+ (* 16 cz) lz)]
                                     :when (not (contains? open [x y z]))]
                                 [[lx y lz] stone])))])))

(defn walled-in
  "A bot in a pocket of the rock (rock open) with an iron pickaxe 40 digs from worn out (spare
   digs and more), and an enderman at pos."
  [open pos]
  (-> (standing {7 (enderman pos)})
      (assoc :world/chunks (rock open))
      (assoc-in [:player/inventory 1] (assoc (item :iron_pickaxe 1) :components {:damage 210}))))

(def feet-cells #{[8 64 8] [8 65 8]})

(deftest a-hunt-walled-in-with-no-end-to-roam-to-cuts-its-way-out
  ;; gym batch pearls-warped8-a (plan run 39731473452, probes 39776762205, 39776787768,
  ;; 39778589970 and 39780802996): runs 2 and 3 held 3 and 4 pearls by their 21st and 31st
  ;; minutes, then stood in lookouts for four and a half hours, every route from the feet ending
  ;; within a few blocks, the roam finding no end and every enderman past cut-reach; run 2 at y 45
  ;; in its own stairs and tunnels with 21 digs left, its second flight down having taken a stair
  ;; of its first
  (let [above [8.5 80.0 48.5]
        w (walled-in feet-cells above)]
    (is (= 40 (pearls/digs-left w)))
    (is (nil? (pearls/roam w)) "no end for a roam in the pocket")
    (is (nil? (pearls/ascend w (pearls/endermen w pearls/descend-radius)))
        "and none within cut-reach for the stairs up toward one")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals)))
    (is (= {:intent/kind :stairs-up
            :intent/to-y 72
            :intent/dir [0 1]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent (looked-out w) row plan/goals))
        "after a lookout that found nothing, stairs up toward it, descend-step up")
    (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [0 1] :intent/for :pearls}
           (pearls/dig-out (walled-in feet-cells [8.5 50.0 20.5])))
        "down toward one below")
    (is
     (=
      {:intent/kind :tunnel :intent/dir [0 1] :intent/length pearls/bore-length :intent/for :pearls}
      (pearls/dig-out (walled-in feet-cells [8.5 65.0 20.5])))
     "a tunnel toward one on this level")
    (is (= {:intent/kind :walk :intent/target [8 64 14] :intent/for :pearls}
           (pearls/dig-out (walled-in (passage 14 8) [8.5 80.0 24.5])))
        "from the end of the pocket nearest it first")
    (is (nil? (pearls/dig-out (assoc w :plan/boxed-at #{[8 64 8]}))) "boxed here: not again")
    (is (nil? (pearls/dig-out (update w :player/inventory dissoc 1))) "no pickaxe: no cut")
    ;; sim probes 39889965974 and 39889946639 (world R3, seeds 4 and 5): cuts out of pockets wore
    ;; the pickaxe out, and with none to dig a burrow the hunt fought calm ones in the open and
    ;; was slain
    (is (nil? (pearls/dig-out (assoc-in w [:player/inventory 1 :components :damage] 229)))
        "21 digs left: kept for a burrow")
    (is (nil? (pearls/dig-out (assoc w :world/entities {}))) "no enderman: nowhere to cut to")))

(defn stuck-in
  "w with a walk ended and then n lookouts, the last of them the plan's last intent, as
   memory keeps them: a hunt that has stood looking since it last walked anywhere."
  [w n]
  (looked-out
   (reduce #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :lookout} :done)
           (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :walk} :done)
           (range n))))

(deftest a-pocket-with-no-enderman-to-cut-toward-is-climbed-out-of
  ;; gym batch pearls-w3 (plan run 40434266158) run 2: five pearls by its forty-fourth
  ;; minute, then :lookout to the five-hour timeout in the caves under the forest floor,
  ;; every roam end spent or short and no enderman tracked for dig-out to cut toward
  (let [w (assoc (walled-in feet-cells [8.5 80.0 48.5]) :world/entities {})]
    (is (nil? (pearls/roam w)) "no end for a roam in the pocket")
    (is (nil? (pearls/dig-out w)) "no enderman: nowhere to cut to")
    (is (not (pearls/stuck? (stuck-in w (dec pearls/stale-lookouts)))))
    (is (pearls/stuck? (stuck-in w pearls/stale-lookouts))
        "a minute of lookouts since the last walk")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (stuck-in w (dec pearls/stale-lookouts)) row plan/goals))
        "before the minute is out it still looks")
    (is (= [:stairs-up 72 true :pearls]
           ((juxt :intent/kind :intent/to-y :intent/to-height? :intent/for)
            (plan/next-intent (stuck-in w pearls/stale-lookouts) row plan/goals)))
        "stuck: stairs up toward the floor, descend-step up")
    (is (nil? (pearls/climb-out (assoc w :plan/boxed-at #{[8 64 8]}))) "boxed here: not again")
    (is (nil? (pearls/climb-out (assoc-in w [:player/inventory 1 :components :damage] 229)))
        "21 digs left: kept for a burrow")))

(deftest no-way-out-is-cut-from-the-brink-of-a-drop
  ;; gym batch pearls-warped8-c (plan run 39845223845, probe 39854206523) run 2: dig-out's stairs
  ;; up from a ledge over a lava lake, the stair's lay backing the bot off the edge into the lava
  (let [shaft (into feet-cells (for [y (range 61 66)] [9 y 8]))
        w (walled-in shaft [8.5 80.0 20.5])]
    (is (pearls/brink? w [8 64 8]) "open beside the feet down to y 61")
    (is (not (pearls/brink? (walled-in (into feet-cells [[9 64 8] [9 65 8] [9 63 8]])
                                       [8.5 80.0 20.5])
                            [8 64 8]))
        "a step down beside the feet is no brink")
    (is (nil? (pearls/dig-out w)) "no cut from it")))

(deftest a-way-out-is-cut-where-the-rock-is-already-cut
  ;; the same run 2: its second flight down took the floor of its first flight's third stair, and
  ;; the way back up lay two stairs north, open, one block to lay and no dig
  (let [open (into feet-cells [[8 66 8] [7 66 8] [7 65 8]])
        w (walled-in open [8.5 80.0 20.5])]
    (is (=
         [0 1]
         (pearls/cheapest-heading (walled-in feet-cells [8.5 80.0 20.5]) :stairs-up [8 64 8] [0 1]))
        "every heading as dear: toward it")
    (is (= [-1 0] (pearls/cheapest-heading w :stairs-up [8 64 8] [0 1]))
        "the heading whose stair is open")
    (is (= [-1 0] (:intent/dir (pearls/dig-out w))))))

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
  "standing at feet with the burrow dug and remembered, holding a pickaxe too, with entities,
   and more blocks in the column when given."
  ([feet entities] (burrowed feet entities {}))
  ([feet entities blocks]
   (-> (standing entities (merge burrow blocks))
       (assoc :player/pos feet :pearls/shelter [8 61 8] :pearls/mouth [3 64 8])
       (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))))

(defn looked
  "w with its burrow cut at time 0 and n lookouts stood since, as memory keeps them (a :burrow
   intention done, then n :lookout intentions done)."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :lookout} :done)
   (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)
   (range n)))

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
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (burrowed [8.5 61.0 8.5] {7 coming}) row plan/goals))
        "in the burrow, the one coming is fought where the bot stands")
    (is (=
         {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
         (plan/next-intent (burrowed [8.5 61.0 8.5] {7 (enderman [-8.5 64.0 8.5])}) row plan/goals))
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

(deftest one-at-hand-not-yet-coming-is-met-from-a-burrow-and-a-hurt-bot-rests-in-it
  ;; gym batch pearls-warped-a (plan run 39131478416): two warped forest landings in three walked
  ;; up to a calm enderman, struck it in the open and died; the third, hurt, went from its
  ;; burrow's end to its mouth and back for twelve minutes, its food too low to heal
  (let [near (assoc-in (standing {7 (enderman [11.5 64.0 8.5])})
              [:player/inventory 1]
              (item :iron_pickaxe 1))
        far (enderman [0.5 64.0 8.5])]
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent near row plan/goals))
        "a pickaxe held and one at hand not coming: a burrow away from it, not a blow in the open")
    (is (=
         {:intent/kind :fight :intent/target 7 :intent/for :pearls}
         (plan/next-intent (assoc-in near [:world/entities 7 :entity/creepy?] true) row plan/goals))
        "one at hand already coming is fought: there is no time to dig")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (burrowed [8.5 61.0 8.5] {7 far}) :player/health 6.0)
                             row
                             plan/goals))
        "hurt in the burrow with none coming: it stays, not out to the mouth")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39723064504) run 5: pearls from five
    ;; kills, then, its burrow given up and its health at 7, roamed the floor with no enderman
    ;; within 32 and was slain at 36 minutes
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (assoc (standing {7 far}) :player/health 6.0) row plan/goals))
     "hurt with no burrow and none coming: it stands where it is till its health is back, no
         walk or roam in the open")
    (is (= :eat
           (:intent/kind (plan/next-intent (-> (burrowed [8.5 61.0 8.5] {7 far})
                                               (assoc :player/health 6.0 :player/food 15)
                                               (assoc-in [:player/inventory 2]
                                                         (item :cooked_beef 8)))
                                           row
                                           plan/goals)))
        "hurt, its food under 18 (where health stops coming back): a meal")))

(deftest a-bot-under-one-hit-plus-a-margin-never-fights-on
  ;; issue #563, probe 40022662598: after the replacement stone sword was crafted, the hunt
  ;; backed away from an angry enderman and then fought it again (the angry one within
  ;; stare-range was fought wherever it stood), again and again down to 1.3 health: the next
  ;; hit (7) would have killed. danger-health (10) is that hit plus a margin
  (let [angry-far (assoc (enderman [8.5 64.0 20.5]) :entity/creepy? true)
        angry-near (assoc (enderman [11.5 64.0 8.5]) :entity/creepy? true)]
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (standing {7 angry-far}) :player/health 6.0) row plan/goals))
        "an angry one twelve blocks off at 6 health: rested where it stands, not fought")
    (is (= {:intent/kind :back-off :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc (standing {7 angry-near}) :player/health 6.0) row plan/goals))
        "one at hand at 6 health: backed away from")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (burrowed [8.5 61.0 8.5]
                                              {7 (assoc angry-far :entity/pos [5.5 61.0 8.5])})
                                    :player/health
                                    6.0)
                             row
                             plan/goals))
        "in the burrow at 6 health: rest in cover, the held fight not started")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (standing {7 angry-far}) :player/health 9.9) row plan/goals))
        "just under danger-health: still no fight")
    (is (= :fight
           (:intent/kind
            (plan/next-intent (assoc (standing {7 angry-far}) :player/health 20.0) row plan/goals)))
        "at full health the angry one is still met")
    (let [zombie {:entity/type (blocks/entity-types :zombie)
                  :entity/pos [11.5 64.0 8.5]
                  :entity/seen-at 0
                  :entity/health 20.0}]
      (is (= {:intent/kind :back-off :intent/target 8 :intent/for :pearls}
             (plan/next-intent (assoc (standing {8 zombie}) :player/health 6.0) row plan/goals))
          "a zombie at hand at 6 health: backed away from, not rested beside"))))

(deftest an-angry-one-given-up-on-is-met-from-the-burrow-when-it-comes
  ;; gym batch pearls-warped-b (plan run 39135767198): a held fight timed out :unreachable, its
  ;; enderman was blacklisted while still angry, and two bots in three walked out to the mouth
  ;; and were slain; the third's run 2 went from the end to the mouth and back for two minutes
  ;; while a calm one stood beside the mouth. Batch pearls-warped-d (plan run 39160760074) run 1:
  ;; three pearls in six minutes, then thirty-four in the burrow over such ones standing off
  (let [angry (assoc (enderman [3.5 64.0 20.5]) :entity/creepy? true)
        given-up (assoc (burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is
     (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent given-up row plan/goals))
     "in the burrow, the one given up on angry twelve off the mouth, within its stare range of
         16 (danger-radius): kept in")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (looked given-up 6) row plan/goals))
        "half a minute of it not coming: out to the mouth to lure it")
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (assoc-in given-up [:world/entities 7 :entity/pos] [3.5 64.0 26.5])
                             row
                             plan/goals))
        "eighteen off, past its stare range: out to the mouth at once, as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc (burrowed [3.5 64.0 8.5]
                                              {7 (assoc angry :entity/pos [5.5 64.0 10.5])})
                                    :plan/blacklist
                                    #{7})
                             row
                             plan/goals))
        "at the mouth with it at hand: back in")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (assoc (burrowed [8.5 61.0 8.5]
                                              {7 (assoc angry :entity/pos [5.5 61.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             row
                             plan/goals))
        "at the end with it on the stairs: fought")
    ;; sim probe 39890029962 (worlds R1 and R4, seed 3): three held fights in a row on one by the
    ;; mouth that never came, each :unreachable, and the plan failed
    (let [by-mouth (burrowed [8.5 61.0 8.5] {7 (assoc angry :entity/pos [5.5 64.0 8.5])})]
      (is (= {:intent/kind :fight
              :intent/target 7
              :intent/hold? true
              :intent/yield? true
              :intent/for :pearls}
             (plan/next-intent by-mouth row plan/goals))
          "at hand by the mouth, not given up on: the fight held")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (assoc by-mouth :plan/blacklist #{7}) row plan/goals))
          "given up on, out of the sword's reach: no fight held again, the bot stays in"))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc (burrowed [8.5 61.0 8.5]
                                              {7 (assoc angry :entity/pos [1.5 64.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             row
                             plan/goals))
        "at the end with it two off the mouth: a crowd, blacklisted or not")
    (is
     (= {:intent/kind :lookout :intent/for :pearls}
        (plan/next-intent (burrowed [8.5 61.0 8.5] {7 (enderman [0.5 64.0 8.5])}) row plan/goals))
     "a calm one three blocks from the mouth: no vantage out past it (a vantage is only within
         provoke-home-reach of the burrow's end, gym plan run 40860669235 job 4): the bot stays in")
    (is (=
         {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
         (plan/next-intent (burrowed [8.5 61.0 8.5] {7 (enderman [3.5 44.0 8.5])}) row plan/goals))
        "a calm one under the mouth, twenty below: no crowd")
    ;; batch pearls-warped-c (plan run 39152813557, probe 39157605071) run 1: forty minutes in the
    ;; burrow over one angry, blacklisted and seventeen blocks below the mouth, in another cave
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent (assoc (burrowed [8.5 61.0 8.5]
                                              {7 (assoc angry :entity/pos [8.5 47.0 8.5])})
                                    :plan/blacklist
                                    #{7})
                             row
                             plan/goals))
        "an angry one far below the mouth cannot come to it: out to the mouth")
    ;; batch pearls-warped6-a (plan run 39687964414, probe 39699149771) run 4: one a held fight
    ;; gave up on stood 3 to 9 blocks off the mouth and 5 or 6 below it on the slope beside, and
    ;; came the moment the bot stepped out, three times, the third its death at 597 s
    (let [below (assoc angry :entity/pos [0.5 58.0 8.5])]
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (assoc (burrowed [8.5 61.0 8.5] {7 below}) :plan/blacklist #{7})
                               row
                               plan/goals))
          "an angry one six below the mouth and three off it, blacklisted: the bot stays in")
      (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
             (plan/next-intent (assoc (burrowed [3.5 64.0 8.5] {7 below}) :plan/blacklist #{7})
                               row
                               plan/goals))
          "at the mouth with it there: home"))))

(defn lured
  "w with its burrow cut at time 0 and n lures walked since (a walk out to the mouth and one back
   in, two :walk intentions done each)."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :walk} :done)
   (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)
   (range (* 2 n))))

(defn fought
  "w with n :fight intentions on entity 7 ended :done, as memory keeps them."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :fight :intent/target 7} :done)
   (assoc w :time/now 0)
   (range n)))

(deftest one-fought-again-and-again-without-a-kill-is-fought-no-more
  ;; gym batch pearls-w3 (plan run 40434266158) run 3: from its second minute to the
  ;; five-hour timeout it planned some sixty-four thousand fights on one enderman, each
  ;; ending done as the enderman teleported out of sight, nineteen failing :unreachable,
  ;; and held one pearl
  (let [angry (assoc (enderman [5.5 61.0 8.5]) :entity/creepy? true)
        on-stairs (assoc (burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= :fight (:intent/kind (plan/next-intent on-stairs row plan/goals)))
        "at the end with it on the stairs: fought, blacklisted or not")
    (is (not (pearls/fought-out? (fought on-stairs (dec pearls/fight-tries)) 7))
        "seven fights done on it: not yet fought out")
    (is (pearls/fought-out? (fought on-stairs pearls/fight-tries) 7)
        "fight-tries fights done on it and it still stands: fought out")
    (is (not (pearls/fought-out? (fought on-stairs pearls/fight-tries) 8)) "another is not")
    ;; sim gate r3fr3b (probe 40470765785, world R4 seed 3): left for good, one fought
    ;; out teleported after the bot and killed it 188 times, never fought back
    (is (not (pearls/fought-out?
              (assoc (fought on-stairs pearls/fight-tries) :time/now (inc pearls/fight-rest-ms))
              7))
        "a rest on, it is fought again")
    (is (not= :fight
              (:intent/kind
               (plan/next-intent (fought on-stairs pearls/fight-tries) row plan/goals)))
        "fought out: no fight is held on it from the end again")
    (is (nil? (pearls/angry-near (fought on-stairs pearls/fight-tries)))
        "and it holds the burrow no longer, so the burrow can go stale"))
  ;; sim gate r3fr3 (probe 40465621253, world R4 seed 3): fought out, the one over the
  ;; burrow's roof still cut every walk away from it through interrupts?, 28,545 walks
  ;; planned in place, where main fought it in place instead (probe 40465674776)
  (let [angry (assoc (enderman [8.5 64.0 10.5]) :entity/creepy? true)
        w (standing {7 angry})
        away {:intent/kind :walk :intent/target [20 64 20] :intent/for :shelter}]
    (is (pearls/beset? w) "angry two blocks off: beset")
    (is (plan/interrupts? w row away) "and a walk away from it is cut to meet it")
    (is (not (pearls/beset? (fought w pearls/fight-tries))) "fought out: beset no longer")
    (is (not (plan/interrupts? (fought w pearls/fight-tries) row away))
        "so the walk away from it stands")))

(deftest an-angry-one-blacklisted-that-never-comes-to-the-lure-holds-the-burrow-no-longer
  ;; gym batch pearls-warped8-a (plan run 39731473452, probe 39776733945) run 1: from the 49th
  ;; minute to the end it walked from its burrow to the mouth and back every 34 s over one angry,
  ;; blacklisted after a held fight timed out :unreachable, 4.2 blocks from the mouth, that never
  ;; moved; the burrow never went stale
  (let [angry (assoc (enderman [3.5 64.0 12.5]) :entity/creepy? true)
        given-up (assoc (burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= 7 (first (pearls/angry-near (lured given-up 2)))) "two lures: still held")
    (is (not (pearls/lured-out? (lured given-up 2))))
    (is (pearls/lured-out? (lured given-up pearls/lure-tries)))
    (is (nil? (pearls/angry-near (lured given-up pearls/lure-tries)))
        "lure-tries lures it never came to: no longer held")
    (is (not= :stairs-up
              (:intent/kind (plan/next-intent (lured given-up pearls/lure-tries) row plan/goals)))
        "and no way out is cut for one merely near the mouth: the cut-out is for the stairs")
    (is (= 7
           (first (pearls/angry-near (lured (dissoc given-up :plan/blacklist) pearls/lure-tries))))
        "one not given up on still holds the bot in")))

(deftest one-given-up-on-in-the-stairs-is-cut-out-past
  ;; sim probe 40102602895 (world R3, seed 4): in its burrow from minute 26 to 60 over an
  ;; angry one, blacklisted, standing in its stairs out of reach: blocking kept it in,
  ;; angry-near kept the burrow fresh, and no lure walk was ever taken to count
  (let [angry (assoc (enderman [4.5 63.0 8.5]) :entity/creepy? true)
        dug (assoc (burrowed [8.5 61.0 8.5] {7 angry}) :plan/blacklist #{7})]
    (is (= 7 (first (pearls/blocking dug))) "in the stairs, below the mouth")
    (is (not (pearls/blocked-out? (looked dug 3))))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (looked dug 3) row plan/goals))
        "three lookouts: waited for where it stands, as before")
    (is (pearls/blocked-out? (looked dug 12)))
    (is (pearls/stale? (looked dug 12)) "a minute of them: the burrow is given up")
    (is (= {:intent/kind :stairs-up
            :intent/to-y 64
            :intent/dir [1 0]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent (looked dug 12) row plan/goals))
        "and the way out is cut up the other way from the mouth, to its height")
    (is (not (pearls/blocked-out? (looked (dissoc dug :plan/blacklist) 12)))
        "one not given up on is still waited for")))

(deftest a-tunnel-step-out-over-a-drop-is-refused
  ;; gym batch pearls-warped9-a (plan run 39898400239) run 3: its tunnel toward an enderman
  ;; broke into the cave it stood in, and the bot came down thirteen blocks into the open
  ;; fight there, where it died
  (is (= 6 (pearls/floored-length (standing {}) [8 64 8] [1 0] 6))
      "stone all the way: every step floored")
  (is (= 2 (pearls/floored-length (standing {} {[11 63 8] air}) [8 64 8] [1 0] 8))
      "the third step's floor is the pit: two")
  (is (= 0 (pearls/floored-length (standing {} {[9 63 8] air}) [8 64 8] [1 0] 8))
      "the first step out over it: none"))

(deftest one-coming-with-no-way-home-is-fought-not-stood-under
  ;; gym batch pearls-warped8-b (plan run 39775345028, probe 39848848462) run 3: out at its
  ;; burrow's first stair, the walk home failed :no-path, and the bot stood in :lookout while the
  ;; angry enderman beside it took its health from 19.4 to nothing in three seconds
  (let [angry (assoc (enderman [5.5 64.0 10.5]) :entity/creepy? true)
        cut-off {[7 62 8] stone [7 61 8] stone}
        w (assoc (burrowed [3.5 64.0 8.5] {7 angry} cut-off) :plan/blacklist #{7})]
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc (burrowed [3.5 64.0 8.5] {7 angry}) :plan/blacklist #{7})
                             row
                             plan/goals))
        "a way home: home")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "none: fought where it stands")))

(deftest a-fire-the-bot-stands-in-is-put-out-first
  ;; gym batch pearls-warped8-b (plan run 39775345028, probe 39849820209) run 1: its walk ended in
  ;; a fire lit on gravel in its path, and it stood there in :lookout until it died
  (let [fire (blocks/default-state :fire)]
    (is (nil? (pearls/burning-at (standing {}))))
    (is (= {:intent/kind :dig :intent/target [8 64 8]}
           (plan/next-intent (standing {} {[8 64 8] fire}) row plan/goals))
        "at the feet")
    (is (= [8 65 8] (pearls/burning-at (standing {} {[8 65 8] fire}))) "at the head")
    ;; gym batch pearls-w1 (plan run 40387599204, probe 40421394796) run 1 dug out its feet
    ;; cell's fire and burned to death anyway: at z 2165.14 its box still reached 0.16 into
    ;; the fire in the cell north, which the feet's one cell never showed
    (is (= [8 64 7]
           (pearls/burning-at (assoc (standing {} {[8 64 7] fire}) :player/pos [8.5 64.0 8.14])))
        "in a cell beside the feet the body overlaps")
    (is (nil? (pearls/burning-at (standing {} {[8 64 7] fire})))
        "the same fire, the body clear of its cell")
    (is (= [8 64 8]
           (pearls/burning-at
            (assoc (standing {} {[8 64 8] fire [8 64 7] fire}) :player/pos [8.5 64.0 8.14])))
        "both burning: the feet's own first")))

(deftest no-enderman-is-provoked-hurt
  ;; sim-endermen3's probe 39847287615 (worlds R1, R2, R4): the first death each time came of a
  ;; provoke 4 to 8 blocks off at 10.8 to 17.8 health just outside the burrow, three to five
  ;; blows landing in five seconds
  (let [sighted (enderman [3.5 64.0 0.5])
        at-mouth (assoc (burrowed [3.5 64.0 8.5] {7 sighted}) :player/food 20)]
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent at-mouth row plan/goals))
        "whole: provoked from the mouth")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc at-mouth :player/health 15.0) row plan/goals))
        "hurt at the mouth: home first")
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent (assoc at-mouth :player/health (pearls/provoke-line at-mouth))
                             row
                             plan/goals)))))

(deftest at-a-burrow-site-the-burrow-is-cut-with-the-quarry-hidden
  ;; sim probe 39861051222 (world R4): fifty-seven minutes between a site, from which the quarry
  ;; was hidden, and the walk toward it, from which it showed again
  (let [w (assoc-in (standing {7 (enderman [8.5 64.0 15.5])} wall)
           [:player/inventory 1]
           (item :iron_pickaxe 1))
        arrived (assoc w :plan/last {:intent/kind :walk :intent/for :shelter :intent/status :done})]
    (is (nil? (pearls/quarry w)) "hidden by the wall")
    (is (not= :burrow (:intent/kind (plan/next-intent w row plan/goals))))
    (is (= {:intent/kind :burrow :intent/dir [0 -1] :intent/for :pearls}
           (plan/next-intent arrived row plan/goals))
        "walked to a site: the burrow, away from the nearest")))

(deftest one-hidden-is-walked-to-a-site-it-is-stared-at-from-never-up-to
  ;; sim probe 39885559223 (world R2, seed 4): the walk to a calm one ended beside it, and the
  ;; burrow begun there provoked it two blocks off; the bot was slain in the open in six seconds
  (let [e (enderman [8.5 64.0 15.5])
        w (-> (standing {7 e} wall)
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1))
              (update :world/chunks
                      into
                      (for [cx [-1 0 1]
                            cz [-1 0 1]
                            :when (not= [0 0] [cx cz])]
                        [[cx cz] (world/column {})])))
        c (pearls/stare-site w e)
        at #(assoc w :player/pos (pearls/feet-at c) :plan/last %)]
    (is (nil? (pearls/quarry w)) "hidden by the wall")
    (is (some? c))
    (is (= {:intent/kind :walk :intent/target c :intent/for :shelter}
           (plan/next-intent w row plan/goals))
        "a walk to a site, not to its cell")
    (is (>= (physics/distance (game/eye (at nil)) (pearls/head e)) (+ pearls/stare-from 1.0)))
    (is (pearls/provokable? (at nil) e) "from which it is stared at")
    (is (= :burrow
           (:intent/kind (plan/next-intent
                          (at {:intent/kind :walk :intent/for :shelter :intent/status :done})
                          row
                          plan/goals)))
        "where the burrow is cut")
    (is (= {:intent/kind :walk :intent/target [8 64 15] :intent/for :pearls}
           (plan/next-intent (update w :player/inventory dissoc 1) row plan/goals))
        "with no pickaxe to cut one, up to it as before")))

(deftest ^:eftest/synchronized
         with-a-pickaxe-held-no-walk-goes-to-an-endermans-feet-without-a-stare-site
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: with stare-site nil, the bot walked straight
  ;; to an enderman's feet with a pickaxe held and died in the open
  (let [e (enderman [8.5 64.0 15.5])
        w (-> (standing {7 e} wall)
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1))
              (update :world/chunks
                      into
                      (for [cx [-1 0 1]
                            cz [-1 0 1]
                            :when (not= [0 0] [cx cz])]
                        [[cx cz] (world/column {})])))]
    (with-redefs [pearls/stare-site (constantly nil)]
      (is (nil? (pearls/stare-site w e)))
      (is (not= [8 64 15] (:intent/target (plan/next-intent w row plan/goals)))
          "never walks to the feet when holding a pickaxe"))))

(deftest an-unreachable-enderman-under-16-blocks-is-no-quarry
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: provoked an enderman 6 blocks off down
  ;; a 2-block ledge with no path up; within 16 blocks it never teleports and could not reach
  (let [low (enderman [3.5 62.0 15.5])
        w (-> (burrowed [3.5 64.0 8.5] {7 low})
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (pearls/provokable? w low) "clear sight line")
    (is (not (pearls/reaches? w low (:pearls/mouth w))) "cannot climb 2 blocks up to the mouth")
    (is (nil? (pearls/quarry w)) "skipped as quarry since it cannot reach")
    (let [far (enderman [3.5 64.0 28.5])
          w-far (assoc-in w [:world/entities 8] far)]
      (is (pearls/reaches? w-far far (:pearls/mouth w-far)) "past 16 blocks it teleports")
      (is (= 8 (first (pearls/quarry w-far))) "so the distant one is quarry"))))

(deftest ^:eftest/synchronized
         a-distant-angry-enderman-is-met-standing-not-charged-with-a-pickaxe-held
  ;; gym batch pearls-m10-a (plan run 40106206590) run 3: charged across the open to fight an
  ;; angry enderman 15 blocks off with no burrow shelter and fell into lava on the way; a
  ;; lookout there instead stood the hunt still while any angry one was tracked, never
  ;; given up on. A held fight stands on ground already stood on and times out instead.
  (let [angry (assoc (enderman [8.5 64.0 20.5]) :entity/creepy? true)
        w (-> (standing {7 angry})
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))]
    (with-redefs [pearls/burrowable? (constantly false)
                  pearls/burrow-site (constantly nil)]
      (let [held (plan/next-intent w row plan/goals)
            open (plan/next-intent (update w :player/inventory dissoc 1) row plan/goals)]
        (is (= :fight (:intent/kind held)))
        (is (:intent/hold? held) "with a pickaxe held, met standing, never charged")
        (is (:intent/yield? held) "and broken off under danger-health, as in the burrow")
        (is (= :fight (:intent/kind open)))
        (is (not (:intent/hold? open)) "without one, fought in the open as the arena row does")))))

(deftest out-of-the-burrow-no-look-stares-at-a-calm-one-and-one-angry-at-hand-ends-the-intent
  ;; sim probes 39887735653 and 39887752302 (seeds 4 and 5): four bots stared at one two or three
  ;; blocks off on a walk or a tunnel and were slain in the open, the intent going on
  (let [e (enderman [8.5 64.0 14.5])
        w (standing {7 e})
        look (physics/look-at (game/eye w) (pearls/head e))
        steered #(get-in (plan/steer (assoc-in % [:player/controls :control/look] look) row)
                         [:player/controls :control/look])]
    (is (= [(first look) 90.0] (steered w)) "a look into a calm one's eyes: turned down")
    (is (= look (steered (assoc-in w [:world/entities 7 :entity/creepy?] true)))
        "an angry one's: as it was")
    (is (= look (steered (assoc w :plan/intent {:intent/kind :provoke :intent/target 7})))
        "a provoke stares")
    (is (= look (steered (assoc w :pearls/shelter [8 64 8])))
        "in the burrow's end a stare by chance brings it to the sword"))
  (let [angry (assoc (enderman [10.5 64.0 8.5]) :entity/creepy? true)
        w (standing {7 angry})]
    (is (pearls/beset? w))
    (is (plan/interrupts? w row {:intent/kind :tunnel}) "a tunnel with one at hand: ended")
    (is (plan/interrupts? (assoc w :plan/blacklist #{7})
                          row
                          {:intent/kind :walk :intent/target [0 64 0]})
        "blacklisted or not")
    (is (not (plan/interrupts? w row {:intent/kind :fight :intent/target 7})) "the fight goes on")
    (is (not (plan/interrupts? (assoc w :pearls/shelter [3 61 8])
                               row
                               {:intent/kind :walk :intent/target [3 61 8]}))
        "and the walk home")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 7 :entity/pos] [20.5 64.0 8.5])
                               row
                               {:intent/kind :tunnel}))
        "one past strike-range: the tunnel goes on")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 7 :entity/creepy?] false)
                               row
                               {:intent/kind :tunnel}))
        "a calm one at hand: the tunnel goes on")
    ;; sim probe 39892490709 (worlds R1 and R4, seed 3): the lookout in the end cut every tick for
    ;; twenty minutes over one given up on by the mouth
    (is (not (plan/interrupts? (assoc w :pearls/shelter [8 64 8]) row {:intent/kind :lookout}))
        "in the burrow's end, where none reaches: the lookout goes on")))

(deftest a-pickaxe-worn-out-on-burrows-ends-the-hunt-in-the-open
  ;; sim probe 39890964793 (seed 5): its pickaxe broke on burrows, and it struck calm endermen in
  ;; the open in worlds R2 and R3 and was slain, R2 nine times
  (let [w (standing {7 (enderman [11.5 64.0 8.5])})
        spent
        (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "never held one: the hunt in the open, as before")
    (is (pearls/worn-out? spent))
    (is (= {:plan/wait :no-pickaxe} (plan/next-intent spent row plan/goals))
        "spent on burrows: no calm one struck in the open")
    (is (= :fight
           (:intent/kind (plan/next-intent (assoc-in spent [:world/entities 7 :entity/creepy?] true)
                                           row
                                           plan/goals)))
        "one coming is still met")
    (is (not (pearls/worn-out? (assoc-in spent [:player/inventory 1] (item :iron_pickaxe 1))))
        "a pickaxe held")))

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
        spent (memory/remember-intention (assoc (standing {}) :time/now 0)
                                         {:intent/id 1 :intent/kind :burrow}
                                         :done)
        forest (-> spent
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        worn (assoc-in forest [:player/inventory 1] (assoc (item :iron_pickaxe 1) :durability 11))
        stems (assoc worn :player/inventory {0 (item :iron_sword 1) 1 (item :warped_stem 2)})
        fresh (assoc-in forest [:player/inventory 1] (item :iron_pickaxe 1))
        walked (plan/next-intent forest row plan/goals)]
    (is (= {:plan/wait :no-pickaxe} (plan/next-intent spent row plan/goals))
        "no trunk remembered and nothing held: the wait, as before")
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "a remembered stem is the trunk the new pickaxe takes")
    (is (= :log (:intent/for (plan/next-intent worn row plan/goals)))
        "eleven digs left: the next pickaxe is made before this one breaks")
    (is (= :craft (:intent/kind (plan/next-intent stems row plan/goals)))
        "stems held: crafted to planks, not another trunk walked to")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent fresh row plan/goals))
        "a fresh pickaxe held: the hunt, no remake")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc forest :player/health 6.0) row plan/goals))
        "under danger-health the craft waits too: rest first")))

(deftest empty-hands-hunt-the-wood-of-a-pickaxe
  ;; gym batch pearls-w1 (plan run 40387599204) run 1: slain in the forest, it respawned at
  ;; the overworld spawn, remade a wooden sword from the spruces there, and twenty seconds
  ;; later failed :no-pickaxe with sticks and its table held: the log hunt that may start a
  ;; bare-handed sword was refused for the pickaxe, so the death still ended the plan
  (let [bare-floor (into {} (for [cx (range -3 4) cz (range -3 4)] [[cx cz] (world/column {})]))
        respawned (-> (standing {})
                      (assoc :world/chunks bare-floor
                             :player/inventory
                             {0 (item :wooden_sword 1) 1 (item :stick 3) 2 (item :crafting_table 1)}
                             :time/now 0)
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        walked (plan/next-intent respawned row plan/goals)
        worn
        (assoc-in respawned [:player/inventory 3] (assoc (item :iron_pickaxe 1) :durability 11))]
    (is (pearls/worn-out? respawned) "a burrow cut this run and no pickaxe held")
    (is (= :walk (:intent/kind walked)))
    (is (= :log-hunt (:intent/for walked))
        "no pickaxe at all: out for the wood of one, never the :no-pickaxe wait")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent worn row plan/goals))
        "a worn pickaxe still held: no hunt for wood, the hunt goes on with it"))
  ;; gym batch pearls-w2 (plan run 40428200343) run 3: the same respawn with spruces
  ;; remembered round it, so the planner asked for the remembered trunk (:log), not the
  ;; hunt; the table taken up after the sword craft counted as the table's pieces
  ;; (sword/pieces?) and the walk was refused, :no-pickaxe again in 184 s
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        respawned (-> (standing {})
                      (assoc :world/chunks {[0 0] col}
                             :player/inventory
                             {0 (item :wooden_sword 1) 1 (item :stick 3) 2 (item :crafting_table 1)}
                             :time/now 0)
                      (memory/remember-column [0 0] col)
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        walked (plan/next-intent respawned row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked))
        "a table held is no wood: the remembered trunk is walked to for the pickaxe"))
  ;; gym batch pearls-w2 (plan run 40428200343) run 2: respawned, remade its wooden sword,
  ;; dug up the placed table (:take-table), and failed :no-pickaxe on the next tick because
  ;; pick-acts had no :collect, leaving the dropped table uncollected
  (let [respawned (-> (standing {})
                      (assoc :player/inventory {0 (item :wooden_sword 1) 1 (item :stick 3)}
                             :time/now 0
                             :plan/last
                             {:intent/kind :dig
                              :intent/target [3 94 49]
                              :intent/for :take-table
                              :intent/status :done})
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        collected (plan/next-intent respawned row plan/goals)]
    (is (= :collect (:intent/kind collected)))
    (is (= :take-table (:intent/for collected))
        "a dug table is collected toward the pickaxe remake, never the :no-pickaxe wait")))

(deftest a-table-held-hides-no-log-chain-from-the-pickaxe-decide
  ;; gym batch pearls-w3 (plan run 40434266158) run 1: slain ninety seconds in, it
  ;; respawned at the overworld spawn, remade its wooden sword, took its table up, and
  ;; walked to the spruce trunk beside it 356,882 times to the five-hour timeout: the
  ;; table held counted as the table's pieces (sword/pieces?), so each done walk's chain
  ;; was hidden from the decide and the walk was asked for again, never the dig
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        walked {:intent/kind :walk :intent/target [12 64 8] :intent/for :log :intent/status :done}
        respawned (-> (standing {})
                      (assoc :world/chunks {[0 0] col}
                             :player/inventory
                             {0 (item :wooden_sword 1) 1 (item :stick 3) 2 (item :crafting_table 1)}
                             :time/now 0
                             :plan/last walked)
                      (memory/remember-column [0 0] col)
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pearls/for-pick-decide respawned)))
        "sticks and a table held, no log and no plank: the done walk's chain stays")
    (is (nil? (:plan/last (pearls/for-pick-decide
                           (assoc-in respawned [:player/inventory 3] (item :spruce_log 1)))))
        "a log counted: the chain is finished and hidden, as before")
    (is (= [:dig :log]
           ((juxt :intent/kind :intent/for) (plan/next-intent respawned row plan/goals)))
        "so the decide continues to the dig, never the same walk again")))

(deftest table-held-hides-no-log-chain-from-sword-decide
  ;; remaking a sword when a table is held: sword/for-decide dropped :plan/last
  ;; whenever pieces? held, so table held without planks or logs lost the done walk
  ;; and re-planned it every tick. for-sword-decide hides the chain only once a log
  ;; is counted (log-counted?), as for-pick-decide does for a pickaxe
  (let [trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        walked {:intent/kind :walk :intent/target [12 64 8] :intent/for :log :intent/status :done}
        w (-> (standing {})
              (assoc :world/chunks {[0 0] col}
                     :player/inventory {0 (item :crafting_table 1)}
                     :time/now 0
                     :plan/last walked)
              (memory/remember-column [0 0] col)
              (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pearls/for-sword-decide w)))
        "table held, no log and no plank: sword decide keeps the done walk's chain")
    (is (nil? (:plan/last (pearls/for-sword-decide
                           (assoc-in w [:player/inventory 1] (item :spruce_log 1)))))
        "a log counted: sword decide hides the finished chain")
    (is (= [:dig :log] ((juxt :intent/kind :intent/for) (plan/next-intent w row plan/goals)))
        "so the decide continues to the dig, never the walk again")))

(deftest nether-warped-stem-walk-advances-to-dig-not-loop
  ;; gym batch pearls-w3 (plan run 40464154227) run 2: in the Nether at pos
  ;; [2374.4 38 2125.3], with four warped planks and a worn pickaxe held, it
  ;; re-issued intent :walk [2374 39 2124] :for :log 186,397 times because planks
  ;; counted as pieces (sword/pieces?) and dropped :plan/last after each arrival.
  ;; With log-counted?, :plan/last stays and the chain continues to the dig
  (let [stem (first (blocks/states-of :warped_stem))
        trunk {[6 39 12] stem [6 40 12] stem [6 41 12] stem}
        col (world/column trunk)
        chunk [148 132]
        walked {:intent/kind :walk
                :intent/target [2374 39 2124]
                :intent/for :log
                :intent/trunk [2374 39 2124]
                :intent/status :done}
        w (-> (standing {})
              (assoc :player/pos [2374.4 38.0 2125.3]
                     :player/dimension "minecraft:the_nether"
                     :world/chunks {chunk col}
                     :player/inventory
                     {0 (assoc (item :iron_pickaxe 1) :durability 11)
                      1 (item :iron_sword 1)
                      2 (item :warped_planks 4)}
                     :time/now 0
                     :plan/last walked)
              (memory/remember-column chunk col)
              (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pearls/for-pick-decide w)))
        "four planks held but no log: the done walk's chain stays")
    (is (= [:dig :log] ((juxt :intent/kind :intent/for) (plan/next-intent w row plan/goals)))
        "standing beside the warped stem after the walk: advances to the dig, never the walk again")
    (is (= [2374 39 2124] (:intent/target (plan/next-intent w row plan/goals)))
        "digs the warped stem target")))

(deftest overworld-respawn-beside-trunk-advances-to-dig-not-loop
  ;; gym batch pearls-w3 (plan run 40464154227) run 3: died and respawned in the
  ;; overworld at pos [2.5 93 49.3], holding crafting_table, stick 3, wooden_sword 1;
  ;; re-issued intent :walk [3 94 50] :for :log 256,743 times because crafting_table
  ;; counted as pieces and dropped :plan/last
  (let [trunk {[3 94 2] 136 [3 95 2] 136 [3 96 2] 136}
        col (world/column trunk)
        chunk [0 3]
        walked {:intent/kind :walk
                :intent/target [3 94 50]
                :intent/for :log
                :intent/trunk [3 94 50]
                :intent/status :done}
        respawned (-> (standing {})
                      (assoc :player/pos [2.5 93.0 49.3]
                             :player/dimension "minecraft:overworld"
                             :world/chunks {chunk col}
                             :player/inventory
                             {0 (item :wooden_sword 1) 1 (item :stick 3) 2 (item :crafting_table 1)}
                             :time/now 0
                             :stats/deaths 1
                             :plan/last walked)
                      (memory/remember-column chunk col)
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))]
    (is (= walked (:plan/last (pearls/for-pick-decide respawned)))
        "table and sticks held, no log: the done walk's chain stays")
    (is (= [:dig :log]
           ((juxt :intent/kind :intent/for) (plan/next-intent respawned row plan/goals)))
        "standing beside the trunk after the walk: advances to the dig, never the walk again")
    (is (= [3 94 50] (:intent/target (plan/next-intent respawned row plan/goals)))
        "digs the spruce log target")))

(deftest stuck-lookout-in-hole-climbs-out-to-surface
  ;; reviewer finding 1 (gym batch pearls-r3-w1 run 1, plan run 40469919220):
  ;; stuck at y 47 inside rock with a brink behind it from its descent,
  ;; logged :lookout 3,369 times and 15 tunnels that dug nothing.
  ;; When stuck? is true, climb-out cuts stairs up into solid rock rather than
  ;; refusing on brink?, and dig-out refuses tunnels with 0 solid cuts.
  (let [blocks (into {[8 46 8] stone
                      [8 46 9] stone
                      [8 47 9] stone
                      [8 48 9] stone
                      [8 49 9] stone
                      [8 50 9] stone
                      [7 46 8] stone
                      [9 46 8] stone}
                     (for [y (range 43 48)] [[8 y 7] 0]))
        w (-> (standing {} blocks)
              (assoc :player/pos [8.5 47.0 8.5]
                     :plan/last {:intent/kind :lookout :intent/for :pearls}
                     :player/inventory {0 (item :diamond_pickaxe 1) 1 (item :iron_sword 1)})
              (memory/remember-intention {:intent/id 0 :intent/kind :walk} :done))
        w (reduce (fn [acc n]
                    (memory/remember-intention acc {:intent/id n :intent/kind :lookout} :done))
                  w
                  (range 1 13))]
    (is (pearls/stuck? w) "stood 12 lookouts: stuck is true")
    (is (pearls/brink? w [8 47 8]) "drop behind makes feet a brink")
    (let [next (plan/next-intent w row plan/goals)]
      (is (= :stairs-up (:intent/kind next))
          "climbs out toward surface rather than repeating lookout")
      (is (= [1 0] (:intent/dir next)) "climbs into the solid rock wall"))))

(deftest empty-tunnel-that-makes-no-progress-fails-boxed
  ;; reviewer finding 1: :tunnel that makes no progress must not repeat forever
  (let [tunnel {:intent/kind :tunnel
                :intent/dir [1 0]
                :intent/length 1
                :intent/level-steps 1
                :intent/from-feet [8 64 8]
                :intent/status :active}
        w (assoc (standing {}) :plan/intent tunnel)
        stepped (intent/run w tunnel (tick 100))
        failed (plan/fail-intent stepped (:plan/intent stepped))]
    (is (= :boxed (:intent/reason (:plan/intent stepped)))
        "tunnel that makes no progress fails :boxed when finished")
    (is (contains? (:plan/boxed-at failed) [8 64 8])
        "feet are recorded in boxed-at so the empty cut is never repeated")))

(deftest start-intent-clears-stale-waiting-reason
  ;; reviewer finding 2: logged :lookout 2,377 times with :waiting :no-sword
  ;; while holding wooden_sword; start-intent must clear :plan/waiting
  (let [w
        (assoc (standing {}) :plan/waiting :no-sword :plan/waiting-ticks 5 :plan/waiting-since 1000)
        started (plan/start-intent w {:intent/kind :lookout :intent/for :pearls})]
    (is (nil? (:plan/waiting started)) "start-intent clears :plan/waiting")
    (is (nil? (:plan/waiting-ticks started)))
    (is (nil? (:plan/waiting-since started)))))

(deftest wooden-sword-counts-for-hunt-and-overworld-does-not-descend-into-caves
  ;; reviewer finding 2: after death in overworld, holding wooden_sword and wooden_pickaxe,
  ;; wooden sword counts for hunt, and overworld hunt roams surface instead of digging down
  ;; into dark caves toward underground endermen
  (let [underground-enderman (enderman [8.5 20.0 8.5])
        w (-> (standing {7 underground-enderman})
              (assoc :player/dimension "minecraft:overworld"
                     :player/inventory
                     {0 (assoc (item :wooden_sword 1) :durability 20) 1 (item :wooden_pickaxe 1)}
                     :stats/deaths 1
                     :plan/last {:intent/kind :lookout :intent/for :pearls}))]
    (is (= :wooden_sword (sword/serviceable w)) "wooden sword is serviceable")
    (let [next (plan/next-intent w row plan/goals)]
      (is (not= :stairs-down (:intent/kind next)) "does not descend into underground cave")
      (is (not= :tunnel (:intent/kind next)) "does not bore underground")
      (is (not= {:plan/wait :no-sword} next) "never waits :no-sword after death"))))

(deftest never-fight-under-danger-health
  ;; prompt requirement & #577: never fight under danger-health
  (let [creepy (assoc (enderman [12.5 64.0 8.5]) :entity/creepy? true)
        w (assoc (standing {7 creepy})
                 :player/health 5.0
                 :player/inventory {0 (item :iron_sword 1) 1 (item :diamond_pickaxe 1)})
        next (plan/next-intent w row plan/goals)]
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
        respawned (-> (standing {})
                      (assoc :world/chunks {[0 0] col}
                             :player/inventory
                             {0 (item :wooden_sword 1) 1 (item :stick 3) 2 (item :crafting_table 1)}
                             :time/now 0
                             :stats/deaths 1
                             :plan/last
                             {:intent/kind :collect
                              :intent/target [12 64 8]
                              :intent/for :take-table
                              :intent/status :done})
                      (memory/remember-column [0 0] col)
                      (memory/remember-intention {:intent/id 1 :intent/kind :burrow} :done))
        next (plan/next-intent respawned row plan/goals)]
    (is
     (= [:walk :log] ((juxt :intent/kind :intent/for) next))
     "sword and table held, no plank for the pickaxe: out to the trunk again, never
            the :no-pickaxe wait")))

(deftest empty-hands-at-a-treeless-spawn-look-for-wood-never-fail-no-sword
  ;; gym batch pearls-r3-w2 (plan run 40475390313) run 2: slain in the forest at three
  ;; minutes, it respawned at an overworld spawn with no log in the chunks first loaded
  ;; and failed :no-sword at five, where batches pearls-w1 to w3's respawns had spruces
  ;; in sight and remade theirs
  (let [bare (assoc (standing {}) :player/inventory {})
        respawned (assoc bare :stats/deaths 1)]
    (is (= {:plan/wait :no-sword} (plan/next-intent bare row plan/goals))
        "never died, no sword and no trunk: the wait stands, no bare-hand hunt")
    (is
     (= {:intent/kind :walk :intent/target [14 64 14] :intent/for :pearls}
        (plan/next-intent respawned row plan/goals))
     "a death behind it and no trunk sighted yet: it roams for wood (a :lookout with
            nowhere to roam), and the remake is asked again, never the :no-sword fail")
    (is (= {:plan/wait :no-sword}
           (plan/next-intent (assoc respawned :player/dimension "minecraft:the_nether")
                             row
                             plan/goals))
        "in the Nether there is no wood to look for: the wait stands")))

(deftest no-sword-held-no-hunt
  ;; gym batch pearls-warped8-b (plan run 39775345028): runs 1 and 3 respawned with nothing held
  ;; after their first deaths and hunted on bare-handed, 39 and 26 deaths in five hours
  (let [w (standing {7 (enderman [8.5 64.0 20.5])})]
    (is (not= {:plan/wait :no-sword} (plan/next-intent w row plan/goals)))
    (is (= {:plan/wait :no-sword}
           (plan/next-intent (update w :player/inventory dissoc 0) row plan/goals)))))

(deftest a-broken-sword-is-made-when-the-craft-can-start
  ;; sim race 39909643851 (issue #563) world R3/13 seed 2: the stone sword broke with cobblestone
  ;; and a stick held and a trunk two blocks off, and the plan failed :no-sword
  (let [held {1 (item :cobblestone 5) 2 (item :stick 1)}
        bare (assoc (standing {}) :player/inventory held)
        table (assoc bare :player/inventory (assoc held 3 (item :crafting_table 1)))
        trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        forest (-> bare
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        placed (plan/next-intent table row plan/goals)
        walked (plan/next-intent forest row plan/goals)]
    (is (= {:plan/wait :no-sword} (plan/next-intent bare row plan/goals))
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
        bare (-> (standing {})
                 (assoc :player/inventory {} :world/chunks {[0 0] col})
                 (memory/remember-column [0 0] col))
        walked (plan/next-intent bare row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "nothing held: the trunk first, never the :no-sword wait")))

(deftest a-broken-sword-is-made-of-warped-stems
  ;; gym batch pearls-m10-a (plan run 40106206590) run 2: six pearls and twenty-five warped
  ;; stems held when its iron sword broke, and the plan failed :no-sword: sword/payable
  ;; counts planks, and none were held
  (let [bare (assoc (standing {}) :player/inventory {1 (item :warped_stem 2)})]
    (is (= :craft (:intent/kind (plan/next-intent bare row plan/goals)))
        "stems held and no sword: a wooden one is crafted from them")))

(deftest a-worn-sword-is-replaced-before-the-hunt
  ;; the same race held the stone sword with nine swings left and fought until it broke
  (let [worn (assoc (item :stone_sword 1) :durability 9)
        held {0 worn 1 (item :cobblestone 5) 2 (item :stick 1)}
        bare (assoc (standing {}) :player/inventory held)
        trunk {[12 64 8] 136 [12 65 8] 136 [12 66 8] 136 [12 67 8] 252}
        col (world/column trunk)
        forest (-> bare
                   (assoc :world/chunks {[0 0] col})
                   (memory/remember-column [0 0] col))
        laid (-> forest
                 (assoc :window/grid {1 (item :oak_log 1)})
                 (update :player/inventory dissoc 0))
        walked (plan/next-intent forest row plan/goals)
        crafted (plan/next-intent laid row plan/goals)]
    (is (= :walk (:intent/kind walked)))
    (is (= :log (:intent/for walked)) "nine swings left: the trunk, before the fight")
    (is (= :craft (:intent/kind crafted)) "a log in the grid is crafted, not another trunk")
    (is (not= :log (:intent/for crafted)))
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc forest :player/health 6.0) row plan/goals))
        "under danger-health the craft waits too: rest first, no fight on one hit")))

(def ledge-wall
  "Stone three high (y 64 to 66) along x 4, the burrow's first stair left open: from the mouth at
   x 3 the only way east is down through the burrow."
  (into {} (for [z (range 16) :when (not= z 8) y (range 64 67)] [[4 y z] stone])))

(deftest one-far-below-no-route-reaches-is-descended-to-by-stairs
  ;; batch pearls-warped-c (plan run 39152813557, probe 39157605071): both landings that lived
  ;; stood on a ledge over the forest floor, every enderman 14 to 50 blocks below, no route down
  ;; and none in sight; run 2 walked from its burrow's mouth to its end, three lower and nearer by
  ;; the route's measure of height, and out again for forty minutes
  (let [below (enderman [8.5 40.0 8.5])
        w (assoc-in (standing {7 below}) [:player/inventory 1] (item :iron_pickaxe 1))]
    (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "a flight of stairs toward it, descend-step down; one straight below is not at hand")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :plan/boxed-at #{[8 64 8]}) row plan/goals))
        "a staircase from here was boxed: not again from here")
    (is (nil? (pearls/descend (standing {7 below}) {7 below})) "no pickaxe: no stairs")
    (is (nil? (pearls/descend w {7 (enderman [8.5 59.0 8.5])}))
        "five below is a slope's worth, not another level: the roam's")
    ;; batch pearls-warped6-b (plan run 39700288080, probe 39711523945) run 2: four flights down
    ;; toward endermen 47 to 69 blocks off on the plane and 10 to 14 below, about 90 digs, in the
    ;; five minutes after its first kill, and the pickaxe worn out at eleven minutes; the flights
    ;; that paid, run 4's seven kills and run 5's four, went toward ones 12 to 31 off
    (is (nil? (pearls/descend w {7 (enderman [48.5 40.0 8.5])}))
        "forty off on the plane, as far below: walked toward first, no flight toward it")
    (is (not= :stairs-down
              (:intent/kind (plan/next-intent
                             (assoc-in w [:world/entities 7 :entity/pos] [48.5 40.0 8.5])
                             row
                             plan/goals))))
    (is (= 56 (:intent/to-y (pearls/descend w {7 (enderman [40.5 40.0 8.5])})))
        "thirty-two off: within cut-reach, the flight as before")
    (is
     (= :stairs-down (:intent/kind (pearls/descend w {7 below 9 (enderman [8.5 65.0 30.5])})))
     "one on this level too, hidden and far: the flight toward the near one below still goes
         (run 4's paying flights went with one to seven on its level within 128)")
    (let [w (burrowed [3.5 64.0 8.5] {7 (enderman [12.5 40.0 8.5])} ledge-wall)]
      (is (nil? (pearls/toward w (pearls/endermen w pearls/seek-radius)))
          "the only route east ends in the burrow, five nearer on the plane: never into it")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (looked-out (looked w 11)) row plan/goals))
          "the burrow fresh: a lookout at its mouth, never a walk or a dig out of its reach")
      (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [1 0] :intent/for :pearls}
             (plan/next-intent (looked-out (looked w 12)) row plan/goals))
          "a minute of them, the burrow given up: stairs down toward it from the mouth"))))

(def slab
  "Stone one thick over the whole column at y 80: it hides an enderman standing at y 90 from
   the eye below, and no route reaches it."
  (into {} (for [x (range 16) z (range 16)] [[x 80 z] stone])))

(deftest one-far-above-no-route-reaches-is-climbed-to-by-stairs
  ;; batch pearls-warped5-b (plan run 39671293287, probe 39683564524): run 5 took five pearls in
  ;; 150 s on the forest floor, cut seven flights down to three endermen in a cave and killed
  ;; them, then stood thirty-two minutes under the floor with every enderman 20 to 30 blocks
  ;; over it; runs 2, 3 and 4 hunted under the floor the same way. The stairs down had no way back
  (let [above (enderman [8.5 90.0 8.5])
        w (assoc-in (standing {7 above} slab) [:player/inventory 1] (item :iron_pickaxe 1))]
    (is (nil? (pearls/quarry w)) "hidden by the slab")
    (is (= {:intent/kind :stairs-up
            :intent/to-y 72
            :intent/dir [-1 0]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "a flight of stairs toward it, descend-step up, ending at that height")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :plan/boxed-at #{[8 64 8]}) row plan/goals))
        "a staircase from here was boxed: not again from here")
    (is (nil? (pearls/ascend (standing {7 above} slab) {7 above})) "no pickaxe: no stairs")
    (is (nil? (pearls/ascend w {7 (enderman [8.5 69.0 8.5])}))
        "five above is a slope's worth, not another level: the roam's")
    (is (= :stairs-down
           (:intent/kind (plan/next-intent
                          (assoc-in w [:world/entities 9] (enderman [8.5 40.0 8.5]))
                          row
                          plan/goals)))
        "one as far below too: the stairs down first, as before")
    (is (nil? (pearls/ascend w {7 above 9 (enderman [8.5 65.0 30.5])}))
        "one on this level too, hidden: no climb while the level still has one to hunt")))

(deftest a-burrow-is-cut-where-it-would-shelter-and-the-provoke-goes-from-its-mouth
  ;; batch pearls-warped-e (plan run 39164914828) run 1: on broken ground :burrow failed
  ;; :no-shelter six times in a row where the bot stood, it then provoked an enderman fifteen
  ;; blocks from its old burrow, the walk home found no path, and it was slain in the open
  (let [seen {7 (enderman [8.5 64.0 15.5])}
        flat (assoc-in (standing seen) [:player/inventory 1] (item :iron_pickaxe 1))
        cave (assoc flat :world/chunks (:world/chunks (standing seen {[8 62 7] air})))]
    (is (pearls/burrowable? flat [8 64 8] [0 -1]) "rock all the way: the end is a shelter")
    (is (not (pearls/burrowable? cave [8 64 8] [0 -1]))
        "a hollow under the first stair's floor: the stair is a drop")
    (is (= {:intent/kind :walk :intent/target [7 64 8] :intent/for :shelter}
           (plan/next-intent cave row plan/goals))
        "so the hunt walks to the nearest cell a burrow would shelter from, and stands in it")
    (is (= [7 64 8] (pearls/burrow-site cave [0 -1])))
    (is (=
         {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
         (plan/next-intent (burrowed [3.5 64.0 2.5] {7 (enderman [3.5 64.0 14.5])}) row plan/goals))
        "a burrow six blocks off and one to provoke: to the mouth first, never the stare from here")
    (is
     (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
        (plan/next-intent (burrowed [3.5 64.0 2.5]
                                    {7 (enderman [3.5 67.0 14.5])}
                                    (into {} (for [x (range 16) y [64 65]] [[x y 5] stone])))
                          row
                          plan/goals))
     "a fence two high between the bot and its burrow, one to provoke over it: to the mouth
         first, as run 1 should have (the walk fails, and the provoke waits)")
    (is (= :netherrack (stairs/filler {:player/inventory {0 (item :netherrack 3)}} 2))
        "a Nether descent lays its floors from what it digs")))

(deftest a-burrow-site-is-one-a-walk-reaches-and-none-a-walk-failed-toward
  ;; batch pearls-warped2-b (plan run 39182782203) run 1: thirteen walks to a burrow site failed
  ;; :no-path in a row, the same site chosen again after each
  (let [seen {7 (enderman [8.5 64.0 15.5])}
        cave (assoc-in (standing seen {[8 62 7] air}) [:player/inventory 1] (item :iron_pickaxe 1))
        ring (into {[8 62 7] air}
                   (for [dx [-1 0 1]
                         dz [-1 0 1]
                         y [64 65]
                         :when (not= 0 dx dz)]
                     [[(+ 8 dx) y (+ 8 dz)] stone]))
        boxed (assoc-in (standing seen ring) [:player/inventory 1] (item :iron_pickaxe 1))]
    (is (= [7 64 8] (pearls/burrow-site cave [0 -1])) "the nearest")
    (is (not= [7 64 8] (pearls/burrow-site (assoc cave :plan/blacklist #{[7 64 8]}) [0 -1]))
        "a site a walk failed toward is passed over")
    (is (some? (pearls/burrow-site (assoc cave :plan/blacklist #{[7 64 8]}) [0 -1]))
        "for the next one")
    (is (nil? (pearls/burrow-site boxed [0 -1]))
        "walled in two high: cells two off would shelter, but no walk reaches one")))

(deftest a-burrows-stairs-are-four-high-so-an-enderman-walks-down-them
  ;; batch pearls-warped2-b (plan run 39182782203, probe 39206015361): a burrow cut into a hill,
  ;; its stairs three high, held every provoked enderman on the first stair (a body steps off at
  ;; its old height, 2.9 tall), and six held fights in six timed out :unreachable. The model's
  ;; enderman (mob/step-toward, mob/tall) walks down the four-high stairs to the last one, two
  ;; cells from the bot at the tunnel's end, and no further
  (let [mouth [3 64 8]
        rock? (fn [[x y _]] (<= y (if (>= x 4) 69 63))) ; a hill rising from x 4
        walk (fn [opened]
               (let [free? (fn [c] (or (contains? opened c) (not (rock? c))))]
                 (mob/cell-of (nth (iterate #(mob/step-toward free? % [8.5 61.0 8.5] 0.3 0.0 3)
                                            [3.5 64.0 8.5])
                                   200))))
        steps (pearls/burrow-steps mouth [1 0])
        three-high (set (concat (stairs/stair-cells mouth [1 0])
                                (stairs/stair-cells [4 63 8] [1 0])
                                (stairs/stair-cells [5 62 8] [1 0])
                                (stairs/level-cells [6 61 8] [1 0])
                                (stairs/level-cells [7 61 8] [1 0])))]
    (is (= [[4 66 8] [4 65 8] [4 64 8] [4 63 8]] (first steps)) "a stair opens four cells")
    (is (= [8 61 8] (last (last steps))) "the tunnel's end")
    (is (= 3 (mob/tall :enderman)))
    (is (= [6 61 8] (walk (set (apply concat steps)))) "down to the last stair")
    (is (= [3 64 8] (walk three-high)) "three high: it stands at the mouth")))

(deftest a-burrows-mouth-is-opened-to-an-endermans-height-first
  ;; batch pearls-warped7-b (plan run 39725964182, probe 39732372050) run 3: the burrow was cut
  ;; under a huge fungus's cap, a warped wart block two over the mouth's feet, its stairs four
  ;; high below it; the bot stands in two cells and an enderman needs three, so the enderman the
  ;; lure brought stood in the hollows beside the mouth, and six held fights in six timed out
  ;; :unreachable. The model's enderman (mob/step-toward, three tall) stops outside the mouth
  ;; under the cap and walks down to the last stair once the cap is dug
  (let [mouth [3 64 8]
        roof [3 66 8]
        cap (blocks/default-state :warped_wart_block)
        rock? (fn [[x y z]] (or (<= y 63) (= [x y z] roof))) ; flat ground, the cap over the mouth
        walk (fn [opened]
               (let [free? (fn [c] (or (contains? opened c) (not (rock? c))))]
                 (mob/cell-of (nth (iterate #(mob/step-toward free? % [8.5 61.0 8.5] 0.3 0.0 3)
                                            [0.5 64.0 8.5])
                                   200))))
        steps (set (apply concat (pearls/burrow-steps mouth [1 0])))
        seen {7 (enderman [0.5 64.0 8.5])} ; calm, at hand, west of the mouth
        capped (-> (standing seen {roof cap})
                   (assoc :player/pos [3.5 64.0 8.5])
                   (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))
        open (assoc capped :world/chunks (:world/chunks (standing seen)))
        burrow {:intent/kind :burrow :intent/dir [1 0] :intent/for :pearls :intent/status :active}
        first-child (fn [w]
                      (let [i (:plan/intent (intent-step (assoc w :plan/intent burrow) (tick 1)))]
                        [(:intent/stage i)
                         (select-keys (:intent/child i) [:intent/kind :intent/target])]))]
    (is (= [roof] (pearls/mouth-cells mouth)) "one cell, the third over the feet, for 2.9")
    (is (not (pearls/stand? capped mouth)) "under the cap no enderman stands in the mouth")
    (is (= [2 64 8] (walk steps))
        "the stairs four high and the cap over the mouth: it stays outside")
    (is (= [6 61 8] (walk (conj steps roof))) "the cap dug: down to the last stair")
    (is (= {:intent/kind :burrow :intent/dir [1 0] :intent/for :pearls}
           (plan/next-intent capped row plan/goals))
        "a burrow is cut where the bot stands under the cap: the cap is dug first")
    (is (not (pearls/burrowable? (standing seen {roof (blocks/default-state :lava)}) mouth [1 0]))
        "lava over the mouth: no burrow here")
    (is (= [:roof {:intent/kind :dig :intent/target roof}] (first-child capped))
        "the burrow's first child digs the cap")
    (is (= [:down {:intent/kind :stairs-down}] (first-child open))
        "nothing over the mouth: the stairs at once")))

(deftest a-burrow-crowded-by-a-calm-one-for-a-minute-is-given-up-for-one-beside-it
  ;; gym batch pearls-warped4-a (plan run 39544677155): run 1 stood in its burrow from 308 s to
  ;; the end over a calm enderman 2.7 blocks from the mouth that never moved, run 5 twenty-one
  ;; minutes over one 3.9 off; run 5 then burrowed beside another by chance and killed it
  (let [calm (enderman [0.5 64.0 8.5])
        dug (burrowed [8.5 61.0 8.5] {7 calm})
        stale (looked dug 12)
        {:intent/keys [kind target for]} (plan/next-intent stale row plan/goals)]
    (is (= 11 (memory/done-since (looked dug 11) :lookout :burrow)))
    (is (nil? (memory/done-since dug :lookout :burrow)) "no burrow ever cut")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (looked dug 11) row plan/goals))
        "eleven lookouts: no vantage out past the crowder, the bot stays in until the minute is up")
    (is (pearls/stale? stale) "a minute of them: the burrow is given up")
    (is (nil? (pearls/shelter stale)))
    (is (and (= :walk kind) (= :shelter for) (not= [8 61 8] target) (not= [3 64 8] target))
        "the hunt walks out to a new burrow's site")
    ;; sim probe 39883402083 (world R1, seed 4): the walk toward it and the burrow cut beside it
    ;; were the old burrow again, every few minutes for fifty
    (let [at (assoc stale
                    :player/pos (pearls/feet-at target)
                    :plan/last {:intent/kind :walk :intent/for :shelter :intent/status :done})]
      (is (= target (pearls/crowd-site stale)))
      (is (>= (physics/distance (game/eye at) (pearls/head calm)) (+ pearls/stare-from 1.0))
          "past stare-from of it, so the stare from the new mouth runs its course")
      (is (pearls/provokable? at calm) "with a clear line to its head")
      (is (= :burrow (:intent/kind (plan/next-intent at row plan/goals))) "there, the new burrow"))
    (is (nil? (pearls/crowd-site (assoc stale :plan/blacklist #{7})))
        "one that would not provoke is no reason to move")
    (is (nil? (pearls/crowd-site (looked (burrowed [8.5 61.0 8.5] {}) 12))) "no crowd, no site")
    (is (not (pearls/stale? (looked (assoc-in dug [:world/entities 7 :entity/creepy?] true) 12)))
        "one coming is no calm crowd")
    (is
     (pearls/stale? (assoc (looked dug 12) :plan/blacklist #{7}))
     "a calm one given up on (:unprovoked) holds nothing: batch pearls-warped5-a's run 3
         stood 37 minutes beside its burrow over one")
    ;; batch pearls-warped5-a (plan run 39625347094, probe 39668407179) run 4: a held fight on
    ;; one timed out, the burrow went stale with it angry seven blocks off the mouth, and the
    ;; walk out was slain by it
    (let [angry (-> (burrowed [8.5 61.0 8.5] {7 (assoc calm :entity/creepy? true)})
                    (assoc :plan/blacklist #{7}))
          at (fn [p] (assoc-in angry [:world/entities 7 :entity/pos] p))]
      (is (= 7 (first (pearls/angry-near (at [3.5 64.0 15.5]))))
          "angry, seven off the mouth, nine from the end")
      (is (not (pearls/stale? (looked (at [3.5 64.0 15.5]) 12)))
          "an angry one given up on near the mouth keeps the burrow")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (looked (at [3.5 64.0 15.5]) 5) row plan/goals))
          "and the bot in it")
      ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 2 stood eight
      ;; minutes over one 5 off and 5 above the end, then twenty-four over one 10 off and 3 above
      ;; that never moved; it comes the moment the bot shows itself (batch pearls-warped6-a run 4)
      (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
             (plan/next-intent (looked (at [3.5 64.0 15.5]) 6) row plan/goals))
          "half a minute of it not coming: out to the mouth to bring it, and straight back in")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (memory/remember-intention
                                (looked (at [3.5 64.0 15.5]) 12)
                                {:intent/id 30 :intent/kind :walk :intent/target [8 61 8]}
                                :done)
                               row
                               plan/goals))
          "back in from the mouth: the half minute counts again from the walk")
      (is
       (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
          (plan/next-intent (assoc (at [3.5 64.0 15.5]) :player/pos [3.5 64.0 8.5]) row plan/goals))
       "at the mouth: back in")
      (is (nil? (pearls/angry-near (at [3.5 44.0 8.5])))
          "one twenty below the mouth, in another cave, never comes: no danger")
      (is (= 7 (first (pearls/angry-near (at [14.5 64.0 8.5]))))
          "eleven off: within its own stare range of 16, it rushes the bot that steps out")
      (is (nil? (pearls/angry-near (at [21.5 64.0 8.5])))
          "eighteen off: past danger-radius, the mouth is walked to as before"))
    ;; batch pearls-warped4-b (plan run 39596287510, probe 39616249061): the arena's endermen
    ;; wander out of the mouth's line within the first minute, and the forest's stand hidden
    ;; behind its ledges; a burrow with nothing to do from it is given up the same way
    (is (not (pearls/stale? (looked (burrowed [8.5 61.0 8.5] {}) 11))) "no crowd, no minute yet")
    (is (pearls/stale? (looked (burrowed [8.5 61.0 8.5] {}) 12))
        "a minute of lookouts with none by the mouth: given up too")))

(deftest a-calm-one-given-up-on-at-hand-gets-no-burrow-cut-for-it
  ;; batch pearls-warped6-b (plan run 39700288080) run 4: a provoke of one by the mouth failed
  ;; :unprovoked at 408 s and it stood there, calm and blacklisted; the burrow went stale after
  ;; every minute of lookouts and, with it still at hand, the hunt cut the same burrow again from
  ;; the same cell, six times in six minutes (about sixty digs), and the pickaxe wore out at
  ;; thirty-two minutes with four pearls held
  (let [calm (enderman [11.5 64.0 8.5])
        w (assoc-in (standing {7 calm}) [:player/inventory 1] (item :iron_pickaxe 1))
        ghost (assoc w :plan/blacklist #{7})]
    (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "a calm one three off, not given up on: the burrow it is met from, as before")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent ghost row plan/goals))
        "the same one given up on (:unprovoked): no burrow for it, the hunt looks for another")
    (is (= :fight
           (:intent/kind (plan/next-intent (assoc-in ghost [:world/entities 7 :entity/creepy?] true)
                                           row
                                           plan/goals)))
        "an angry one given up on at hand is fought, as before")
    ;; batch pearls-warped7-a (plan run 39714009099, probe 39720871893) run 4: three endermen in
    ;; one spot 3.8 off, two blacklisted and one not; the blacklisted one nearest by a hair got
    ;; no burrow, and the walk to the other, within reach, was planned 18,760 times in fifteen
    ;; minutes
    (let [two (assoc-in ghost [:world/entities 9] (enderman [11.5 64.0 8.6]))]
      (is (= 9 (first (pearls/at-hand two))) "the one not given up on, though a hair farther")
      (is (= {:intent/kind :burrow :intent/dir [-1 0] :intent/for :pearls}
             (plan/next-intent two row plan/goals))
          "is met from a burrow")
      (is (nil? (pearls/routable two {9 (enderman [11.5 64.0 8.6])}))
          "and a walk to one within reach is never planned: it would arrive where it begins"))))

(deftest a-calm-one-at-hand-in-the-burrow-is-waited-out-never-held-a-fight
  ;; batch pearls-warped4-b's arena run 3 (plan run 39596287510, probe 39616249061): from its
  ;; burrow's end it held a fight on a calm enderman 3.8 blocks off from 21 s to 128 s, which
  ;; never came (nothing had provoked it), blacklisted it, and found the others gone from the
  ;; arena's lit square; the run passed at 213 s where the arena's bots pass in about 50
  (let [calm (enderman [4.5 62.0 8.5])
        w (burrowed [8.5 61.0 8.5] {7 calm})]
    (is (= 7 (first (pearls/blocking w))) "a calm one two stairs up, 4.1 off: in the stairs")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals))
        "out of the sword's reach and in the only way out: waited for where the bot stands")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (assoc-in w [:world/entities 7 :entity/pos] [5.5 61.0 8.5])
                             row
                             plan/goals))
        "on the last stair, three off: within the sword's reach, struck, and the blow angers it")
    (is (= {:intent/kind :fight
            :intent/target 7
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent (assoc-in w [:world/entities 7 :entity/creepy?] true) row plan/goals))
        "the same one coming: fought where the bot stands")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :player/health 6.0) row plan/goals))
        "hurt with it there: it stays in, no back-off out of the burrow")
    (is (nil? (pearls/blocking (burrowed [8.5 61.0 8.5] {7 (enderman [0.5 64.0 8.5])})))
        "one on the ground by the mouth is no block: a vantage is walked to past it")))

(def north-wall
  "Stone four high across x 1 to 6 at z 12: it hides the head of an enderman standing north of
   the burrow's mouth at z 20 from the eye at the mouth, and a walk goes round it to the east."
  (into {} (for [x (range 1 7) y (range 64 68)] [[x y 12] stone])))

(deftest one-hidden-from-the-mouth-is-no-reason-to-walk-out-of-the-burrows-close-reach
  ;; batch pearls-warped4-b (plan run 39596287510): the arena's run 2 stood at its mouth with all
  ;; three endermen hidden, walked out to the nearest and met the dark's mobs. Gym plan run
  ;; 40860669235 job 4 (issue #263): a vantage five blocks from home, with an angry one tracked,
  ;; was fought in the open at 11 health. A vantage is only a cell within provoke-home-reach of
  ;; the burrow's end, which a provoke from it can be walked home from before the enderman is there
  (let [far (enderman [3.5 64.0 20.5])
        w (-> (burrowed [3.5 64.0 8.5] {7 far} north-wall)
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (nil? (pearls/quarry w)) "hidden from the mouth")
    (is (nil? (pearls/vantage w (pearls/endermen w pearls/seek-radius))))
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals))
        "a lookout at the mouth, not a walk out")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent (assoc-in w [:world/entities 7 :entity/creepy?] true) row plan/goals))
        "coming: home, where it is met")
    (is (= {:intent/kind :walk
            :intent/target (pearls/stare-site (dissoc w :pearls/shelter) far)
            :intent/for :shelter}
           (plan/next-intent (dissoc w :pearls/shelter) row plan/goals))
        "with no burrow, walked to a new burrow's site it is stared at from (stare-site)")))

(deftest a-vantage-farther-from-home-than-from-the-enderman-is-no-vantage
  ;; batch pearls-warped5-b (plan run 39671293287, probe 39683564524): run 1 provoked one 7.6
  ;; blocks off from a vantage 12 from its burrow's end and was slain on the walk home at 106 s;
  ;; run 5 lost eleven health on the walk home from one provoked 15 off. An enderman comes at the
  ;; bot's pace and more: the bot must be home first
  (let [far (enderman [3.5 64.0 30.5])
        w (-> (burrowed [3.5 64.0 8.5] {7 far} north-wall)
              (assoc-in [:world/chunks [0 1]] (world/column {})))]
    (is (nil? (pearls/quarry w)) "hidden from the mouth")
    (is (nil? (pearls/vantage w (pearls/endermen w pearls/seek-radius)))
        "every cell within ten of it is more than ten from home: none")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w row plan/goals))
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
        at-mouth (burrowed [3.5 64.0 8.5] {9 zombie})
        at-end (burrowed [8.5 61.0 8.5] {9 (assoc zombie :entity/pos [5.5 61.0 8.5])})]
    (is (= 9 (first (pearls/threat at-mouth))) "a zombie three blocks off")
    (is (contains? (combat/answering at-mouth :pearls) :zombie)
        "with a burrow near, the hunt answers it, not the :defend row")
    (is (= #{:enderman} (combat/answering (dissoc at-mouth :pearls/shelter) :pearls))
        "with none, the :defend row does, as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent at-mouth row plan/goals))
        "at the mouth: back in")
    (is (= {:intent/kind :fight
            :intent/target 9
            :intent/hold? true
            :intent/yield? true
            :intent/for :pearls}
           (plan/next-intent at-end row plan/goals))
        "at the end with it on the stairs: fought where the bot stands")
    (is (nil? (pearls/threat
               (assoc-in at-mouth [:world/entities 9 :entity/type] (blocks/entity-types :creeper))))
        "a creeper is the :defend row's: backed away from, and a burrow is no place for that")
    (is (nil? (pearls/threat (assoc at-mouth :plan/blacklist #{9}))) "one given up on is a ghost")))

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
        "a fight broken off left its enderman alive: no kill, and the fight goes on")
    ;; sim probe 39885574495 (world R2, seed 5): two provoked at once, one killed, and the walk
    ;; to its pearl was slain beside it by the other
    (let [angry (assoc (enderman [14.5 64.0 8.5]) :entity/creepy? true)
          w #(assoc (standing {9 drop 8 %}) :plan/last kill)]
      (is (pearls/menaced? (w angry)))
      (is (= {:intent/kind :fight :intent/target 8 :intent/for :pearls}
             (plan/next-intent (w angry) row plan/goals))
          "another angry one six off: met first, the drop left")
      (is (pearls/menaced? (assoc (w angry) :plan/blacklist #{8})) "blacklisted or not")
      (is (not (pearls/menaced? (w (dissoc angry :entity/creepy?)))) "a calm one is no menace")
      (is (= :collect-drops
             (:intent/kind
              (plan/next-intent (w (assoc angry :entity/pos [40.5 64.0 8.5])) row plan/goals)))
          "one thirty-two off, past danger-radius: the drop is picked up"))))

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

(deftest an-enderman-that-hit-the-bot-near-its-burrow-is-the-hunts-not-defends
  ;; churn-a pearls-12 run 3, enderman-end-b runs 1 and 2 (plan runs 41096292443 and 41103285823):
  ;; provoked from the mouth, hit once on the way in (20 to 14.3), and 0.05 s later the :defend row
  ;; began a fight to the end where the bot stood, 2 to 4 blocks from the burrow's end: four hits
  ;; in 3 to 5 s. The hit enderman is the hunt's, whose first branch walks home.
  (let [angry (assoc (enderman [4.5 64.0 8.5]) :entity/creepy? true)
        hit (fn [w] (assoc w :player/attacker 9 :player/health 14.26))
        at-mouth (hit (burrowed [3.5 64.0 8.5] {9 angry}))
        no-burrow (dissoc at-mouth :pearls/shelter :pearls/mouth)]
    (is (empty? (combat/threats at-mouth)) "the attacker is not :defend's with a burrow to walk to")
    (is (not= :defend
              (:goal/id (plan/choose (assoc at-mouth :plan/goals #{:ender-pearls}) plan/goals)))
        "the :defend row is done, so the hunt's walk home is not cut")
    (is (= :defend
           (:goal/id (plan/choose (assoc no-burrow :plan/goals #{:ender-pearls}) plan/goals)))
        "and with no burrow it is :defend's turn as before")
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent at-mouth row plan/goals))
        "and it walks home")
    (is (= [9] (keys (combat/threats no-burrow)))
        "with no burrow near the attacker is :defend's as before")
    (is (empty? (combat/threats (hit (burrowed [8.5 61.0 8.5]
                                               {9 (assoc angry :entity/pos [5.5 61.0 8.5])}))))
        "in the burrow's end it stays the hunt's: a held fight")))

(deftest a-walk-out-ends-when-an-angry-one-is-near-the-mouth-not-only-at-hand
  ;; churn-a pearls-12 run 1 (plan run 41096292443): the hunt's choice had been the way home for
  ;; 8 s while a walk to a site 12 blocks up the stairs went on; the angry one (blacklisted after
  ;; a held fight timed out) arrived with the bot 11 blocks from the end, and killed it on them
  (let [angry (assoc (enderman [3.5 64.0 14.5]) :entity/creepy? true)
        out {:intent/kind :walk :intent/target [3 64 4] :intent/for :shelter :intent/status :active}
        lure
        {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter :intent/status :active}
        home
        {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter :intent/status :active}
        w (-> (burrowed [4.5 63.0 8.5] {9 angry})
              (assoc :plan/blacklist #{9}))]
    (is (pearls/angry-near w) "the blacklisted angry one is still near the mouth")
    (is (not (pearls/beset? w)) "and not at hand")
    (is (plan/interrupts? w row out) "a walk out is cut")
    (is (not (plan/interrupts? w row lure)) "the lure's walk to the mouth is what it is for")
    (is (not (plan/interrupts? w row home)) "the way home is never cut")
    (is (not (plan/interrupts? (assoc w :player/pos [8.5 61.0 8.5]) row out))
        "nor anything at the burrow's end")
    (is (not (plan/interrupts? (assoc-in w [:world/entities 9 :entity/creepy?] false) row out))
        "nor with none angry")))

(deftest an-angry-one-known-and-no-burrow-cuts-one-here-not-a-walk-to-a-site
  ;; pearl-fight-a job 3 (plan run 41143668588): the burrow stale, enderman 964 angry 6 to 19
  ;; blocks off the whole 30 s (fought out and blacklisted, so no ce, no quarry, no angry-near),
  ;; and the hunt walked and climbed through the open toward a crowd site and a new burrow's
  ;; stairs; 964 reached it on the stairs, five hits in 12 s, held four pearls
  (let [angry (assoc (enderman [8.5 64.0 14.5]) :entity/creepy? true)
        w (-> (standing {9 angry})
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1))
              (assoc :plan/blacklist #{9}))]
    (is (some? (pearls/menace w)) "angry, six blocks off, blacklisted: still a menace")
    (is (= :burrow (:intent/kind (plan/next-intent w row plan/goals)))
        "a burrow is cut where the bot stands")
    (is (nil? (pearls/menace (assoc-in w [:world/entities 9 :entity/creepy?] false)))
        "a calm one is none")))

(deftest one-given-up-as-out-of-reach-is-not-fought-again
  ;; gym plan run 40747177526 run 4 (issue #263, pearls-12): an angry enderman eight blocks below
  ;; the bot in its stair shaft was chosen as :fight eight times in 10 ms, each given up as not
  ;; strikable and ended done at once
  (let [below (assoc (enderman [11.5 56.0 8.5]) :entity/creepy? true)
        w (standing {7 below})
        given (combat/given-up w 7)]
    (is (= :fight (:intent/kind (plan/next-intent w row plan/goals)))
        "not given up: the hunt fights it")
    (is (pearls/unfightable? given 7 below) "given up and out of reach: no fight's target")
    (is (not= :fight (:intent/kind (plan/next-intent given row plan/goals)))
        "so the fight is not chosen again, the hunt does something else")
    (testing "once it is within reach it is fought at once"
      (let [near (assoc below :entity/pos [9.5 64.0 8.5])
            given-near (combat/given-up (standing {7 near}) 7)]
        (is (not (pearls/unfightable? given-near 7 near)))))))

(deftest one-that-has-struck-and-stands-in-reach-is-fought-back
  ;; run 4 again: three hits in five seconds, 20 to 0, with a walk going on and no attack sent,
  ;; the enderman counted as fought out
  (let [angry (assoc (enderman [9.5 64.0 8.5]) :entity/creepy? true)
        base (assoc (standing {7 angry}) :player/health 12.0)
        out (fought base pearls/fight-tries)
        walking
        (assoc
         out
         :plan/intent
         {:intent/kind :walk :intent/target [20 64 20] :intent/status :active :intent/for :pearls})]
    (is (pearls/fought-out? out 7))
    (is (= 7 (first (pearls/struck-by out))) "hit once (under struck-health), in reach")
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent out row plan/goals))
        "fought out or not, it is fought back")
    (is (plan/interrupts? walking row (:plan/intent walking)) "a walk is cut by it")
    (testing "at full health it is fought out as before"
      (let [full (assoc out :player/health 20.0)]
        (is (nil? (pearls/struck-by full)))
        (is (not (plan/interrupts? full row (:plan/intent walking))))))
    (testing "out of the sword's reach it is not struck-by"
      (is (nil? (pearls/struck-by
                 (assoc-in out [:world/entities 7 :entity/pos] [9.5 70.0 8.5])))))))

(deftest a-cornered-bot-under-danger-health-eats-or-walls-off
  ;; gym plan run 40747177526 run 1 (issue #263, pearls-12): 7.6 health, an angry enderman a block
  ;; or two off, back-off ten seconds at a time for ninety seconds with the bot backing into a
  ;; wall, forty-eight cooked beef and many blocks held; slain by the next two hits
  (let [angry (assoc (enderman [10.5 64.0 8.5]) :entity/creepy? true)
        held {0 (item :iron_sword 1) 1 (item :cobblestone 64)}
        w (-> (standing {7 angry})
              (assoc :player/health 7.6 :player/inventory held :plan/cornered #{7}))]
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [9 64 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent w row plan/goals))
        "a block at the feet's level on the side it stands on")
    (is (= [9 65 8]
           (:intent/cell
            (plan/next-intent
             (assoc w :world/chunks (:world/chunks (standing {7 angry} {[9 64 8] stone})))
             row
             plan/goals)))
        "then one over it")
    (is (= :eat
           (:intent/kind
            (plan/next-intent
             (assoc w :player/food 12 :player/inventory (assoc held 2 (item :cooked_beef 8)))
             row
             plan/goals)))
        "hungry with food held: the meal first")
    (is (= :back-off (:intent/kind (plan/next-intent (assoc w :plan/cornered #{}) row plan/goals)))
        "not cornered: backed off from as before")
    (is (plan/interrupts? w row {:intent/kind :walk :intent/target [1 64 1] :intent/for :pearls})
        "an angry one at hand cuts a walk")
    (is (not (plan/interrupts? w row (plan/next-intent w row plan/goals))) "but not the wall")))

(deftest a-shelter-walk-asks-the-blacklist
  ;; gym plan run 40583923956 run 3 (issue #263): the walk to the burrow's mouth, planned fifteen
  ;; times in 0.6 s, each failing :no-path
  (let [w (assoc (standing {}) :plan/blacklist #{[2497 53 2134]})]
    (is (nil? (pearls/shelter-walk w [2497 53 2134])))
    (is (nil? (pearls/shelter-walk w nil)))
    (is (= {:intent/kind :walk :intent/target [1 64 1] :intent/for :shelter}
           (pearls/shelter-walk w [1 64 1])))))

(defn wide
  "w with flat stone floor chunks from -2 to 4 round its own, so routes and roams have ground."
  [w]
  (update w
          :world/chunks
          #(into (or % {})
                 (for [cx (range -2 5)
                       cz (range -2 5)
                       :when (not= [0 0] [cx cz])]
                   [[cx cz] (world/column {})]))))

(def stranded
  "Run 1 of gym plan run 40775811459 (issue #263, pearls-12): at its burrow's end with five
   pearls, endermen tracked 22 blocks out and 14 down, and 16 out and 45 up: none with a route.
   A staircase from the feet is boxed, as the cuts toward them were refused (descend, ascend,
   dig-out and climb-out all ask :plan/boxed-at)."
  (-> (wide (burrowed [8.5 61.0 8.5] {7 (enderman [30.5 50.0 8.5]) 9 (enderman [8.5 106.0 24.5])}))
      (assoc :plan/boxed-at #{[8 61 8]})))

(deftest a-hunt-with-no-enderman-it-can-walk-to-goes-elsewhere-after-five-minutes
  (testing "the lookouts stood: not yet, then enough"
    (is (not (pearls/wandering? (looked stranded (dec pearls/wander-lookouts)))))
    (is (pearls/wandering? (looked stranded pearls/wander-lookouts))))
  (testing "an enderman with a route keeps the hunt where it is"
    (let [w (looked (wide (burrowed [8.5 61.0 8.5] {7 (enderman [30.5 64.0 8.5])}))
                    pearls/wander-lookouts)]
      (is (not (pearls/wandering? w)))))
  (testing "the walk is to ground at least roam-reach off"
    (let [{:intent/keys [kind target for]} (pearls/wander (looked stranded pearls/wander-lookouts))]
      (is (= [:walk :pearls] [kind for]))
      (is (>= (path/octile (intent/feet stranded) target) pearls/roam-reach))))
  (testing "and the hunt walks, where it would otherwise stand in lookouts"
    (is (= :walk
           (:intent/kind
            (plan/next-intent (looked stranded pearls/wander-lookouts) row plan/goals))))))

(deftest a-lookout-is-cut-only-when-the-hunt-would-do-something-else
  ;; run 2 of the same plan run ended in 50,301 lookouts 75 ms apart: an angry enderman at hand
  ;; cut each at once and the hunt chose another lookout
  (let [angry (assoc (enderman [10.5 64.0 8.5]) :entity/creepy? true)
        w (standing {7 angry})
        lookout {:intent/kind :lookout :intent/for :pearls :intent/goal :ender-pearls}]
    (is (pearls/beset? w))
    (is (plan/interrupts? w row lookout) "the hunt fights it: cut")
    (is (pearls/same-choice? lookout {:intent/kind :lookout :intent/for :pearls})
        "the hunt would lookout again: left to stand")
    (is (not (pearls/same-choice? lookout {:intent/kind :fight :intent/target 7})))))

(deftest a-fight-that-failed-is-not-planned-again-until-the-enderman-is-in-reach
  ;; runs 2 and 3: :fight :unreachable eight times on one target, thirteen minutes of a held fight
  (let [far (assoc (enderman [8.5 64.0 20.5]) :entity/creepy? true)
        near (assoc far :entity/pos [10.5 64.0 8.5])
        failed (fn [w]
                 (memory/remember-intention (assoc w :time/now 1000)
                                            {:intent/id 5 :intent/kind :fight :intent/target 7}
                                            :failed))
        w (failed (standing {7 far}))]
    (is (pearls/unfightable? w 7 far) "out of reach, just failed")
    (is (not (pearls/unfightable? w 7 near)) "within reach it is fought")
    (is (not (pearls/unfightable? (assoc w :time/now (+ 1000 pearls/fight-retry-ms)) 7 far))
        "after fight-retry-ms it is tried again")
    (is (not (pearls/unfightable? (standing {7 far}) 7 far)) "never failed: fought")))

(deftest a-held-fight-with-nothing-coming-is-cut-after-twelve-seconds
  ;; gym plan run 40825468783 runs 4 and 5 (issue #263): :fight 148 held from 06:51:41 to
  ;; 06:52:41, failing :unreachable after combat/hold-timeout-ms, the enderman never in reach
  (let [far (assoc (enderman [8.5 64.0 30.5]) :entity/creepy? true)
        fight {:intent/kind :fight
               :intent/target 7
               :intent/hold? true
               :intent/since 1000
               :intent/status :active
               :intent/goal :ender-pearls
               :intent/for :pearls}
        at (fn [w ms] (assoc w :time/now ms))
        w (standing {7 far})]
    (is (not (pearls/held-idle? (at w (+ 1000 pearls/hold-patience-ms)) fight)) "not yet")
    (is (pearls/held-idle? (at w (+ 1001 pearls/hold-patience-ms)) fight) "then cut")
    (is (plan/interrupts? (at w (+ 1001 pearls/hold-patience-ms)) row fight))
    (is (not (pearls/held-idle? (at w 99999) (dissoc fight :intent/hold?))) "a fight that walks up")
    (is (not (pearls/held-idle? (at (standing {7 (assoc far :entity/pos [10.5 64.0 8.5])}) 99999)
                                fight))
        "its target within strike-range: the fight goes on")
    (testing "and it is not planned again for a while"
      (let [cut (memory/remember-intention (at w 20000) (assoc fight :intent/id 5) :abandoned)]
        (is (pearls/unfightable? cut 7 far))))))

(defn mob
  "A tracked mob of kind k (a keyword) at pos."
  [k pos]
  {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0 :entity/health 20.0})

(def burst
  "Run 5 of gym plan run 40825468783 (issue #263, pearls-12): an angry enderman and two zombified
   piglins within reach took 20 health in 1.5 s."
  {7 (assoc (enderman [12.5 64.0 8.5]) :entity/creepy? true)
   8 (mob :zombified_piglin [11.5 64.0 9.5])
   9 (mob :zombified_piglin [5.5 64.0 6.5])})

(deftest a-swarm-is-two-monsters-near-or-one-while-hurt
  (let [w (standing burst)]
    (is (= #{8 9} (set (keys (pearls/swarm w)))) "the enderman is the quarry, not the swarm")
    (is (pearls/swarmed? w))
    (is (not (pearls/swarmed? (standing {8 (mob :zombified_piglin [11.5 64.0 9.5])})))
        "one, at full health")
    (is (pearls/swarmed?
         (assoc (standing {8 (mob :zombified_piglin [11.5 64.0 9.5])}) :player/health 8.0))
        "one, under danger-health")
    (is (not (pearls/swarmed? (standing {8 (mob :magma_cube [10.5 64.0 8.5])})))
        "run 4: a lone magma cube at full health is the fight rules'")
    (is (not (pearls/swarmed? (standing {8 (mob :zombified_piglin [11.5 64.0 9.5])
                                         9 (mob :zombified_piglin [40.5 64.0 9.5])})))
        "the second is past swarm-radius")
    (testing "gym plan run 40839050567 job 5: two piglins shooting from past eight blocks"
      (is (pearls/swarmed? (standing {8 (mob :piglin [20.5 64.0 9.5])
                                      9 (mob :piglin [8.5 64.0 22.5])})))
      (is (not (pearls/swarmed? (standing {8 (mob :zombified_piglin [20.5 64.0 9.5])
                                           9 (mob :zombified_piglin [8.5 64.0 22.5])})))
          "a zombified piglin has no bow"))))

(deftest a-swarmed-hunt-goes-home-seals-the-mouth-and-opens-it-when-they-are-gone
  (let [held {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1) 2 (item :cobblestone 64)}
        outside (-> (burrowed [3.5 64.0 2.5] burst)
                    (assoc :player/inventory held))
        inside (-> (burrowed [8.5 61.0 8.5] burst)
                   (assoc :player/inventory held))]
    (is (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
           (plan/next-intent outside row plan/goals))
        "out in the open: home")
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [7 61 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent inside row plan/goals))
        "in the burrow's end: one block between the bot and the mouth")
    (testing "the feet sealed, the head's level is next: a crossbow shoots over a block"
      (let [feet-sealed (-> (burrowed [8.5 61.0 8.5] burst {[7 61 8] stone})
                            (assoc :player/inventory held))]
        (is (= [7 62 8] (:intent/cell (plan/next-intent feet-sealed row plan/goals))))))
    (testing "sealed at both, it waits and does not fight"
      (let [sealed (-> (burrowed [8.5 61.0 8.5] burst {[7 61 8] stone [7 62 8] stone})
                       (assoc :player/inventory held))]
        (is (= {:intent/kind :lookout :intent/for :pearls}
               (plan/next-intent sealed row plan/goals)))))
    (testing "they gone, the seal is dug"
      (let [gone (-> (burrowed [8.5 61.0 8.5] {} {[7 61 8] stone})
                     (assoc :player/inventory held))]
        (is (= {:intent/kind :dig :intent/target [7 61 8] :intent/for :pearls}
               (plan/next-intent gone row plan/goals)))))))

(deftest the-hunt-does-not-walk-out-to-a-mouth-a-monster-stands-at
  ;; gym plan run 40825468783 run 4 (issue #263, pearls-12): the walk between the burrow's end and
  ;; its mouth every ~2 s, forty-eight times in 3m40, a magma cube at the mouth landing seven hits
  ;; of three each, 20 health to 3.7: home when it was near, out to the mouth when it was not
  (let [held {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}
        end [8.5 61.0 8.5]
        clear (assoc (burrowed end {}) :player/inventory held)
        cube (assoc (burrowed end {7 (mob :magma_cube [3.5 64.0 8.5])}) :player/inventory held)]
    (is (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
           (plan/next-intent clear row plan/goals))
        "nothing at the mouth: out to look round from it")
    (is (not= [:walk [3 64 8]]
              ((juxt :intent/kind :intent/target) (plan/next-intent cube row plan/goals)))
        "a magma cube at the mouth: the walk is not planned")))

(def tunnel
  "A tunnel two high cut east through the stone from x 8 to 13 at y 60 and 61, opening into a hall
   (y 60 to 64) at x 14 and 15: the open line a stair cut gave a ghast in gym plan run 40839050567
   job 6 (issue #263, pearls-12), the bot at [2229 96 2686] and the ghast at [2253 83 2703]."
  (into {}
        (concat (for [x (range 8 14) y [60 61]] [[x y 8] air])
                (for [x [14 15] y (range 60 65)] [[x y 8] air]))))

(deftest a-ghast-in-sight-is-covered-from-never-dug-under
  (let [ghast (mob :ghast [15.5 60.0 8.5])
        held {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1) 2 (item :cobblestone 64)}
        at (fn [entities blocks]
             (-> (standing entities blocks)
                 (assoc :player/pos [8.5 60.0 8.5] :player/inventory held)))
        down-the-cut (at {7 ghast} tunnel)
        stairs {:intent/kind :stairs-down
                :intent/status :active
                :intent/goal :ender-pearls
                :intent/for :pearls}]
    (is (pearls/shot-at? down-the-cut) "in sight down the tunnel")
    (is (pearls/swarmed? down-the-cut))
    (is (not (pearls/shot-at? (at {7 (mob :ghast [15.5 60.0 8.5])} {}))) "in solid stone: no line")
    (is (plan/interrupts? down-the-cut row stairs) "a staircase is cut")
    (is (nil? (pearls/hide-cell down-the-cut (get-in down-the-cut [:world/entities 7])))
        "a tunnel two high with straight walls has no cell out of the line beside the feet")
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [9 61 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent down-the-cut row plan/goals))
        "a block on the line, the first open cell with a wall beside it")
    (testing "with no filler held, a hole is dug on the side away from it"
      (let [no-filler (assoc down-the-cut :player/inventory {0 (item :iron_pickaxe 1)})]
        (is (= {:intent/kind :dig :intent/target [7 60 8] :intent/for :pearls}
               (pearls/cover-from no-filler)))))))

(deftest a-zombie-at-hand-is-fought-at-full-health-and-never-looked-out-from
  ;; the same plan run, after the death (overworld, zombie 4548): :fight, then :lookout and
  ;; :back-off alternating every 0.4 to 2 s for 16 s, 86 back-off ticks, then death
  (let [zombie (mob :zombie [11.5 64.0 8.5])
        w (standing {7 zombie})
        lookout {:intent/kind :lookout :intent/for :pearls :intent/goal :ender-pearls}]
    (is (= {:intent/kind :fight :intent/target 7 :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "at full health with no burrow: fought")
    (is (= :back-off (:intent/kind (plan/next-intent (assoc w :player/health 8.0) row plan/goals)))
        "hurt: backed away from")
    (is (plan/interrupts? w row lookout) "a lookout does not run with it at hand")))

(deftest an-uncovered-ghast-is-logged-once-in-five-seconds
  ;; the open-ground case: nothing to lay a block against, nothing to dig, no cell out of the line
  (let [held {0 (item :iron_sword 1)}
        w (-> (wide (standing {7 (mob :ghast [8.5 75.0 50.5])}))
              (assoc :player/inventory held :time/tick 0))
        logged (fn [w] (filter #(= :log (:effect/kind %)) (:bot/effects (pearls/uncovered-log w))))]
    (is (pearls/shot-at? w))
    (is (nil? (pearls/cover-from w)))
    (is (= 1 (count (logged w))))
    (is (re-find #"ghast 7 at \[8 75 50\] shoots the bot at \[8 64 8\]: no cover"
                 (:effect/message (first (logged w)))))
    (is (empty? (logged (assoc w :time/tick 1))) "not every tick")))

(deftest a-provoke-keeps-two-hits-of-health-and-no-walk-out-over-an-angry-one
  ;; gym plan run 40860669235 job 4 (issue #263, pearls-12): provoked at about 11 health, then
  ;; the walk between the burrow's end and its mouth ten times in 17 s, then a fight in the open
  ;; and three hits of 5.7
  (let [mail (fn [w]
               (-> w
                   (assoc-in [:player/inventory (armour/worn-slot 0)] (item :iron_helmet 1))
                   (assoc-in [:player/inventory (armour/worn-slot 1)] (item :iron_chestplate 1))))
        sighted (enderman [3.5 64.0 0.5])
        at-mouth (-> (burrowed [3.5 64.0 8.5] {7 sighted})
                     (assoc :player/food 20)
                     mail)
        calm-end (-> (burrowed [8.5 61.0 8.5] {})
                     mail)
        angry-end (-> (burrowed [8.5 61.0 8.5]
                                {7 (assoc (enderman [3.5 64.0 14.5]) :entity/creepy? true)})
                      mail)]
    (is (< 5.7 (pearls/enderman-hit at-mouth) 5.8) "7 through iron helmet and chestplate")
    (is (< 14.4 (pearls/provoke-line at-mouth) 14.6) "3 over two hits")
    (is (= 7.0 (pearls/enderman-hit (burrowed [3.5 64.0 8.5] {}))) "no armour: all of it")
    (is (= :provoke
           (:intent/kind (plan/next-intent (assoc at-mouth :player/health 15.0) row plan/goals))))
    (is (not= :provoke
              (:intent/kind (plan/next-intent (assoc at-mouth :player/health 14.0) row plan/goals)))
        "under the line: home, not a provoke")
    (is (= [:walk [3 64 8]]
           ((juxt :intent/kind :intent/target) (plan/next-intent calm-end row plan/goals)))
        "nothing coming: out to look round from the mouth")
    (is (not= [:walk [3 64 8]]
              ((juxt :intent/kind :intent/target) (plan/next-intent angry-end row plan/goals)))
        "an angry one tracked: the bot stays in, where the walk out sent it home again")))

(deftest a-ghast-in-sight-is-covered-from-before-the-walk-home-and-a-walk-is-not-cut-for-itself
  ;; gym plan run 40860669235 job 5 (issue #263, pearls-12): five fireballs took 20 health to 0
  ;; while the same :walk was planned every 50 ms, each plan cut by the one before it, and no
  ;; cover was ever chosen (a burrow was cut, so the walk home came first)
  (let [held {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1) 2 (item :cobblestone 64)}
        w (-> (burrowed [3.5 64.0 8.5] {7 (mob :ghast [30.5 75.0 8.5])} {[4 64 8] stone})
              wide
              (assoc :player/inventory held))
        walk {:intent/kind :walk
              :intent/target [3 64 8]
              :intent/status :active
              :intent/goal :ender-pearls
              :intent/for :shelter}]
    (is (pearls/shot-at? w))
    (is (= {:intent/kind :place
            :intent/item :cobblestone
            :intent/cell [4 65 8]
            :intent/for :pearls
            :intent/wall? true}
           (plan/next-intent w row plan/goals))
        "cover first, not the walk home")
    (testing "a walk the hunt would plan again is not cut"
      (is (pearls/same-choice? walk (assoc walk :intent/status :new :intent/id 9)))
      (is (not (pearls/same-choice? walk {:intent/kind :lookout})))
      (is (not (pearls/same-choice? walk (assoc walk :intent/target [4 64 8])))))))

(defn walked
  "w with a :walk intention done to each target of cells, in order, as memory keeps them."
  [w cells]
  (reduce-kv #(memory/remember-intention
               %1
               {:intent/id (+ 100 %2) :intent/kind :walk :intent/target %3}
               :done)
             w
             (vec cells)))

(deftest a-hunt-shuffling-two-blocks-for-hours-wanders
  ;; gym plan run 40839050567 jobs 1 to 4 (issue #263, pearls-12): five-hour timeouts, each a walk
  ;; between two cells a few blocks apart, 446, 784, 346 and 460 times, a lookout between every
  ;; two, so that no run of lookouts since the last walk ever reached wander-lookouts. The offsets
  ;; between the two cells of the jobs: [0 0 2] (job 1, [2449 47 2198] and [2449 47 2200]), [0 0 2]
  ;; (job 2, [2459 55 2202] and [2459 55 2204]), [0 0 2] (job 3, [2473 55 2210] and [2473 55 2212])
  ;; and [5 3 0] (job 4, [2457 55 2195] and [2452 52 2195])
  (doseq [[job [dx dy dz]] {1 [0 0 2]
                            2 [0 0 2]
                            3 [0 0 2]
                            4 [5 3 0]
                            ;; gym plan run 40905321229 (stuck-e), five timeouts of 378 to 718
                            ;; swings: [2460 55 2208] and [2458 55 2208]; [2449 47 2198] and
                            ;; [2449 47 2200]; [2452 55 2201] and [2452 55 2203]; [2489 46 2214]
                            ;; and [2489 46 2216]; [2778 39 2530] and [2783 36 2530]
                            :e1 [-2 0 0]
                            :e2 [0 0 2]
                            :e3 [0 0 2]
                            :e4 [0 0 2]
                            :e5 [5 -3 0]}] ; job 3: [2473 55 2210] and [2473 55 2212]
    (let [w (-> stranded
                (assoc :player/pos [8.5 61.0 8.5])
                (walked (take pearls/loiter-walks
                              (cycle [[8 61 8] [(+ 8 dx) (+ 61 dy) (+ 8 dz)]]))))]
      (is (pearls/loitering? w) (str "job " job " shuffles"))
      (is (pearls/wandering? w) (str "job " job " wanders"))))
  (testing "twelve walks that went somewhere are no loitering"
    (let [w (walked stranded (for [k (range pearls/loiter-walks)] [(+ 8 (* 20 k)) 61 8]))]
      (is (not (pearls/loitering? w)))))
  (testing "eleven walks are too few"
    (let [w (walked stranded (repeat (dec pearls/loiter-walks) [8 61 8]))]
      (is (not (pearls/loitering? w))))))

(deftest a-calm-one-crowding-the-mouth-is-no-reason-to-walk-out-past-an-angry-one
  ;; gym plan run 40860669235 job 4 (issue #263, pearls-12), 6.30 min: at home with a calm
  ;; enderman three blocks from the mouth and an angry one thirteen away below, the hunt chose a
  ;; vantage five blocks out, was fought in the open at 11 health there and slain
  (let [calm (enderman [5.5 64.0 8.5])
        angry (assoc (enderman [8.5 50.0 18.5]) :entity/creepy? true)
        w (assoc (burrowed [8.5 61.0 8.5] {7 calm 8 angry}) :player/food 20)
        i (plan/next-intent w row plan/goals)]
    (is (pearls/crowded? w) "the calm one crowds the mouth")
    (is (nil? (pearls/vantage w (pearls/endermen w pearls/seek-radius))))
    (is (not= :walk (:intent/kind i)) (pr-str i))))

(deftest a-fight-cut-three-times-in-ten-minutes-is-not-planned-again
  ;; gym plan run 40860669235 job 3 (issue #263, pearls-12): :fight 65 held twelve seconds, cut,
  ;; and planned again after thirty, every 47 seconds for 286 minutes, 80 of them in a fight with
  ;; thirty-three attacks sent: an angry enderman six blocks off and three below that never came
  (let [far (assoc (enderman [8.5 61.0 14.5]) :entity/creepy? true)
        near (assoc far :entity/pos [10.5 64.0 8.5])
        cut (fn [w at id]
              (memory/remember-intention (assoc w :time/now at)
                                         {:intent/id id :intent/kind :fight :intent/target 7}
                                         :abandoned))
        base (standing {7 far})
        w (-> base
              (cut 0 1)
              (cut 50000 2)
              (cut 100000 3)
              (assoc :time/now 140000))]
    (is (pearls/unfightable? w 7 far)
        "three cuts in ten minutes, the last long past fight-retry-ms")
    (is (not (pearls/unfightable? w 7 near)) "in reach, it is fought")
    (is (not (pearls/unfightable? (-> base
                                      (cut 0 1)
                                      (cut 50000 2)
                                      (assoc :time/now 140000))
                                  7
                                  far))
        "two cuts: tried again")
    (is (not (pearls/unfightable? (assoc w :time/now (+ 100000 pearls/fight-giveup-ms 1)) 7 far))
        "ten minutes on, tried again")))

(deftest a-shuffle-wanders-even-with-an-enderman-a-route-reaches
  ;; gym plan run 40905321229 job 6 (issue #263, pearls-12): [2242 76 2701] and [2244 76 2701]
  ;; walked 136 times over 51 minutes, an angry enderman routable and never coming
  (let [angry (assoc (enderman [30.5 64.0 8.5]) :entity/creepy? true)
        w (-> (wide (burrowed [8.5 61.0 8.5] {7 angry}))
              (walked (take pearls/loiter-walks (cycle [[8 61 8] [10 61 8]]))))]
    (is (some? (pearls/routable w (pearls/endermen w pearls/seek-radius))) "a route reaches it")
    (is (pearls/loitering? w))
    (is (pearls/wandering? w))
    (is (not (pearls/wandering? (looked (wide (burrowed [8.5 61.0 8.5] {7 angry}))
                                        pearls/wander-lookouts)))
        "lookouts alone, with one routable: as before, it stays")))

(deftest a-walk-out-of-a-ghasts-sight-ends-centred-in-the-cell
  ;; gym plan run 40905321229 job 6 (issue #263, pearls-12): :walk [2241 83 2705], one cell from the
  ;; feet, planned 45 times in 2.5 s: a walk for :pearls is over within reach of its target, so it
  ;; ended where it began and was planned again
  (let [wall (into {} (for [y (range 64 70)] [[11 y 9] stone]))
        w (-> (standing {7 (mob :ghast [14.5 66.0 8.5])} wall)
              (assoc :player/inventory {0 (item :iron_sword 1)}))
        i (pearls/cover-from w)]
    (is (pearls/shot-at? w))
    (is (= [8 64 9] (pearls/hide-cell w (get-in w [:world/entities 7])))
        "out of its line, beside the feet")
    (is (= {:intent/kind :walk :intent/target [8 64 9] :intent/for :cover :intent/wall? true} i))
    (is (not (intent/arrived? w i 1.0)) "from the cell beside it, a walk for :cover is not over")
    (is (intent/arrived? (assoc w :player/pos [8.5 64.0 9.5]) i 0.0) "centred in the cell it is")))

(deftest a-wander-comes-before-the-lookouts-of-a-burrow-with-an-angry-one-near
  ;; gym plan run 40973173654 (pearls-stuck-f) jobs 2, 4, 5 and 6: the fold at 120, 200, 60 and 105
  ;; minutes had wandering? and wander both true and next-intent :lookout or the walk to the mouth,
  ;; because the lure, crowd and quarry branches come first
  (let [angry (assoc (enderman [30.5 64.0 8.5]) :entity/creepy? true)
        w (-> (wide (burrowed [8.5 61.0 8.5] {7 angry}))
              (assoc :plan/given-up {7 0})
              (walked (take pearls/loiter-walks (cycle [[8 61 8] [10 61 8]]))))
        i (plan/next-intent w row plan/goals)]
    (is (pearls/wandering? w))
    (is (some? (pearls/wander w)))
    (is (not (pearls/menaced? w)) "the angry one is not at the feet")
    (is (= [:walk :pearls] [(:intent/kind i) (:intent/for i)]) (pr-str i))
    (is (not= [10 61 8] (:intent/target i)))
    (is (>= (path/octile (intent/feet w) (:intent/target i)) pearls/roam-reach))))

(def nether "minecraft:the_nether")

(deftest a-chunk-of-warped-nylium-is-a-forest-fact
  (let [nylium (blocks/default-state :warped_nylium)
        col (fn [n] (world/column (into {} (for [x (range n)] [[x 64 3] nylium]))))
        fact (fn [n dim] (memory/forest-fact [2 -1] (col n) 77 dim))]
    (is (= {:forest/chunk [2 -1]
            :forest/kind :warped
            :forest/count 12
            :forest/pos [40 64 -13]
            :forest/at 77
            :forest/dimension nether}
           (select-keys (fact memory/forest-min nether)
                        [:forest/chunk :forest/kind :forest/count :forest/pos :forest/at
                         :forest/dimension]))
        "the block nearest the chunk's centre (40, -8), twelve of them")
    (is (nil? (fact (dec memory/forest-min) nether)) "eleven: no forest")
    (is (nil? (fact memory/forest-min "minecraft:overworld")) "only the Nether has them")
    (testing "remembered on load, known after, and the newest per chunk"
      (let [w (-> (game/init fx/opts)
                  (assoc :player/dimension nether :time/now 5)
                  (memory/remember-column [2 -1] (col 12))
                  (memory/remember-column [2 -1] (col 12))
                  (memory/remember-column [2 -1] (col 14)))]
        (is (= [[[2 -1] 14]] (map (juxt :forest/chunk :forest/count) (memory/warped-chunks w))))
        (is (= 2 (count (d/q '[:find [?e ...] :where [?e :forest/chunk]] (:world/facts w))))
            "an unchanged reload adds no fact")))))

(deftest a-hunt-with-no-warped-forest-near-travels-or-explores
  ;; real race 40980342194, Clj_r13 (issue #263): two hours of lookouts and walks round a fortress
  ;; at x 4250-4440, z 1700-1750, no pearl, 0 pearls in all of the race's 6 to 7 bots with rods:
  ;; no code went to a warped forest
  (let [base (-> (wide (standing {}))
                 (assoc :player/dimension nether)
                 (assoc :player/inventory {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}))
        forest (fn [chunk pos]
                 {:forest/chunk chunk
                  :forest/kind :warped
                  :forest/count 40
                  :forest/pos pos
                  :forest/at 0
                  :forest/dimension nether})
        known (memory/remember-forest base (forest [30 0] [480 70 8]))
        here (memory/remember-forest base (forest [0 0] [8 63 8]))]
    (testing "none known: explores, as far as the loaded ground goes"
      (let [i (plan/next-intent base row plan/goals)]
        (is (= [:walk :pearls] [(:intent/kind i) (:intent/for i)]) (pr-str i))
        (is (>= (path/octile (intent/feet base) (:intent/target i)) pearls/roam-reach))))
    (testing "one known: the cell over its nylium"
      (is (= {:intent/kind :walk :intent/target [480 71 8] :intent/for :pearls}
             (plan/next-intent known row plan/goals))))
    (testing "one failed (blacklisted): not again"
      (is (not= [480 71 8]
                (:intent/target
                 (plan/next-intent (assoc known :plan/blacklist #{[480 71 8]}) row plan/goals)))))
    (testing "in one: the hunt, no travel"
      (is (nil? (pearls/travel here)))
      (is (pearls/in-forest? here))
      (is
       (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent here row plan/goals))
       "in a forest with no enderman yet: the hunt stands where it is, no walk out (a walk
           out of a forest it reached was job 5's 13 minutes of walking in pearls-travel-a)"))
    (testing "the overworld has no such search"
      (is (nil? (pearls/travel (assoc base :player/dimension "minecraft:overworld")))))
    (testing "an enderman tracked: hunted, not travelled from"
      (let [w (assoc-in base [:world/entities 7] (enderman [10.5 64.0 8.5]))]
        (is (not= [:walk :pearls]
                  ((juxt :intent/kind :intent/for) (plan/next-intent w row plan/goals))))))))

(deftest one-enderman-at-a-time-is-provoked
  ;; churn-a (plan 41096292443) pearls-12 run 3 at 59.47 to 59.69 min (issue #263): :provoke 1310,
  ;; :provoke 1561 half a second on, :provoke 1691 0.2 min later; at 59.73 the three stood within
  ;; 2.5 blocks of the bot, and the last of them struck it five times in five seconds
  (let [sighted (enderman [3.5 64.0 0.5])
        coming (assoc (enderman [3.5 64.0 20.5]) :entity/creepy? true)
        w (assoc (burrowed [3.5 64.0 8.5] {7 sighted}) :player/food 20)
        busy (assoc-in w [:world/entities 8] coming)]
    (is (= {:intent/kind :provoke :intent/target 7 :intent/for :pearls}
           (plan/next-intent w row plan/goals))
        "none angry: provoked from the mouth")
    (is (not= :provoke (:intent/kind (plan/next-intent busy row plan/goals)))
        "another angry and coming: not a second")
    (let [given-up (-> busy
                       (assoc :plan/given-up {8 0})
                       (assoc-in [:world/entities 8 :entity/pos] [3.5 64.0 30.5]))]
      (is (pearls/coming? busy))
      (is (not (pearls/coming? given-up)) "given up on and out of reach: it is not coming")
      (is
       (= {:intent/kind :walk :intent/target [8 61 8] :intent/for :shelter}
          (plan/next-intent given-up row plan/goals))
       "so it blocks nothing; at the mouth the bot goes home first (an angry one tracked sends
           it there), where the next plan provokes"))))

(deftest only-a-weak-mob-at-hand-is-fought-with-no-burrow
  ;; gym plan run 41109441111 (pearls-travel-a) job 5: `walk [2396 72 2184]`, `fight 744`, three
  ;; hits of a piglin in four seconds, dead at 39 minutes
  (let [zombie (mob :zombie [11.5 64.0 8.5])
        piglin (mob :piglin [11.5 64.0 8.5])]
    (is (pearls/weak? zombie))
    (is (not (pearls/weak? piglin)) "it shoots and its hit is not in mob/kinds")
    (is (= :fight (:intent/kind (plan/next-intent (standing {7 zombie}) row plan/goals))))
    (is (not= :fight (:intent/kind (plan/next-intent (standing {7 piglin}) row plan/goals))))))

(deftest an-explore-goes-on-away-from-where-it-has-been
  ;; gym plan run 41134469333 (pearls-travel-b) job 2: walks of 12 s alternating between x 2240 and
  ;; x 2200 for 5.5 minutes, the heading chosen by the count of walks arrived at
  (let [w (-> (wide (standing {}))
              (assoc :player/dimension nether
                     :player/inventory {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}))
        west (walked w [[-20 64 8] [-24 64 8] [-28 64 8]])
        east (walked w [[40 64 8] [44 64 8] [48 64 8]])
        x-of #(first (:intent/target %))]
    (is (> (x-of (pearls/explore west)) 8) "been west: east")
    (is (< (x-of (pearls/explore east)) 8) "been east: west")))

(deftest nothing-is-cut-down-into-the-nethers-lava-sea
  ;; gym plan run 41134469333 (pearls-travel-b) job 6 (issue #263): stairs-down from y 47 toward an
  ;; enderman far below, to y 34, a burrow cut at [2498 34 2160], lava let in, two flees, dead in lava
  (let [low (-> (standing {7 (enderman [8.5 40.0 14.5])} {})
                (assoc :player/dimension nether
                       :player/pos [8.5 38.0 8.5]
                       :player/inventory {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}))]
    (is (= 36 (pearls/lava-floor low)))
    (is (zero? (pearls/lava-floor (assoc low :player/dimension "minecraft:overworld"))))
    (is (nil? (pearls/descend low (pearls/endermen low pearls/descend-radius)))
        "an enderman 40 and below the cut's floor: none")
    (is (not (pearls/burrowable? (assoc low :player/pos [8.5 38.0 8.5]) [8 38 8] [1 0]))
        "a burrow whose last stair is at y 35 is under the floor")))

(deftest an-angry-one-is-told-parked-from-stalking-by-its-motion
  ;; travel-b job 6 (plan 41134469333, issue #263): enderman 51 parked at 12 blocks for 41 minutes,
  ;; the wander held in; pearl-fight-a job 3: a blacklisted angry one stalked at 6 to 19 blocks for
  ;; 30 s and hit five times
  (let [at (fn [pos] (assoc (enderman pos) :entity/creepy? true))
        w (-> (wide (burrowed [8.5 61.0 8.5] {7 (at [8.5 61.0 20.5])}))
              (walked (take pearls/loiter-walks (cycle [[8 61 8] [10 61 8]])))
              (assoc :time/now 0))
        tracked (pearls/track-angry w)
        later (fn [w ms]
                (-> w
                    (assoc :time/now ms)
                    pearls/track-angry))]
    (is (= {7 {:since 0 :best 12.0 :closed-at 0}} (:pearls/angry tracked)))
    (testing "parked 41 minutes at twelve blocks: not a menace, the wander goes"
      (let [parked (later tracked (* 41 60000))]
        (is (pearls/parked? parked 7 [8.5 61.0 20.5]))
        (is (not (pearls/live-menaced? parked)))))
    (testing "seen only half a minute: still a menace, the wander stays in"
      (let [fresh (later tracked 30000)]
        (is (not (pearls/parked? fresh 7 [8.5 61.0 20.5])))
        (is (pearls/live-menaced? fresh))))
    (testing "one that stalks: closing in resets the clock"
      (let [t1 (later tracked 50000)
            near (-> t1
                     (assoc-in [:world/entities 7 :entity/pos] [8.5 61.0 16.5])
                     (later 100000))
            parked-again (later near 130000)]
        (is (= 100000 (get-in near [:pearls/angry 7 :closed-at])) "four blocks nearer: closing")
        (is (not (pearls/parked? parked-again 7 [8.5 61.0 16.5])) "only thirty seconds since")
        (is (pearls/live-menaced? parked-again))))
    (testing "within park-near it is never parked"
      (let [on-us (-> tracked
                      (assoc-in [:world/entities 7 :entity/pos] [8.5 61.0 14.5])
                      (later (* 41 60000)))]
        (is (not (pearls/parked? on-us 7 [8.5 61.0 14.5])) "6 blocks")))))

(deftest the-menace-is-the-angry-one-within-eight-blocks
  ;; the two logged states of an-angry-one-is-told-parked-from-stalking-by-its-motion, for the
  ;; burrow cut where the bot stands: travel-b job 6's enderman parked at twelve blocks for 41
  ;; minutes is no menace (live-menaced? alone holds the wander), pearl-fight-a job 3's stalker
  ;; at six blocks is one, blacklisted or not
  (let [angry (assoc (enderman [8.5 64.0 20.5]) :entity/creepy? true)
        w (-> (standing {9 angry})
              (assoc :plan/blacklist #{9}))]
    (is (nil? (pearls/menace w)) "twelve blocks off")
    (is (some? (pearls/menace (assoc-in w [:world/entities 9 :entity/pos] [8.5 64.0 14.5])))
        "six blocks off")))

(def ledge
  "A pillar of stone at [8 y 8] up to y 63 in a void: x and z 2 to 14 and y 40 to 63 are air but
   the pillar and a stair of cells going down from [8 63 9] (the burrow's shaft): every cell
   round the feet at [8 64 8] is open over a drop but the stair."
  (into
   {}
   (concat
    (for [x (range 2 15) z (range 2 15) y (range 40 64) :when (not= [8 8] [x z])] [[x y z] air])
    (for [y [63 62 61]] [[8 y 9] air]))))

(deftest ^:eftest/synchronized a-bot-on-a-ledge-over-a-cavern-goes-down-to-its-burrow-to-cut-from
  ;; pearl-fight-a job 4 (plan run 41143668588): 23 minutes in lookouts at [2533 41 2181], 141 digs
  ;; left, endermen 17 to 63 blocks off, every move nil: the feet a brink, so climb-out, dig-out
  ;; and bore refuse to cut from them; the burrow's end five blocks down was all the bot could walk to
  (let [w (-> (standing {} ledge)
              (assoc :player/pos [8.5 64.0 8.5] :pearls/shelter [8 61 9] :pearls/mouth [8 64 8])
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))]
    (is (pearls/brink? w [8 64 8]) "a ledge")
    (with-redefs [intent/routable? (constantly true)]
      (is (= {:intent/kind :walk :intent/target [8 61 9] :intent/for :shelter} (pearls/uncage w))
          "down to the burrow's end")
      (is (nil? (pearls/uncage (assoc w :player/pos [8.5 61.0 9.5])))
          "at the end it is not a brink")
      (is (nil? (pearls/uncage (dissoc w :pearls/shelter)))
          "with no burrow, nothing to go down to"))))

(deftest a-hunt-caged-in-a-pocket-cuts-its-way-out
  ;; gym plan run 41134469333 (pearls-travel-b) job 6 (issue #263), the fold at 25 min: feet
  ;; [2500 47 2155] at the burrow's end, every heading's route ended within thirteen blocks (:none,
  ;; the frontier spent: 17 and 35 waypoints), so wander was nil; and at home the hunt never
  ;; reached the roam, climb-out and dig-out of its :else chain. 136 walks over 41 minutes
  (let [w (-> (burrowed [8.5 61.0 8.5] {})
              (walked (take pearls/loiter-walks (cycle [[8 61 8] [10 61 8]]))))]
    (is (pearls/loitering? w))
    (is (nil? (pearls/wander w)) "one chunk loaded: no route reaches sixteen blocks out")
    (is (= :stairs-up (:intent/kind (plan/next-intent w row plan/goals)))
        "so it cuts stairs up toward the open ground")
    (testing "not loitering: nothing coming, so out to the mouth to look round from"
      (is
       (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
          (plan/next-intent (burrowed [8.5 61.0 8.5] {}) row plan/goals))
       "the branch (and in? mouth (nil? ce) ...): the walk to the mouth, as in the travel test
           nothing-at-the-mouth. My earlier expectation (a lookout) was written without a run and
           was wrong from the start"))))

(deftest a-hunt-sealed-in-the-nether-with-nowhere-to-travel-cuts-its-way-out
  ;; pearls-travel-c (plan run 41144362671) jobs 2 and 3, pearls-12-from-fortress: landed in the
  ;; gym's netherrack pad, lookout x1262 for 106 minutes with no walk at all
  (let [sealed (-> (burrowed [8.5 61.0 8.5] {})
                   (assoc :player/dimension nether))]
    (is (pearls/travel-caged? sealed))
    (is (nil? (pearls/travel sealed)))
    (is (= :tunnel (:intent/kind (plan/next-intent sealed row plan/goals)))
        "in the Nether, level: there is no sky to climb to")
    (testing "the overworld has no such search"
      (is (not (pearls/travel-caged? (assoc sealed :player/dimension "minecraft:overworld")))))))

(deftest a-walk-to-cover-and-a-walk-away-do-not-alternate
  ;; pearls-fight-c job 1 (plan run 41155212096, issue #263): walk [2474 100 2232] (a cell beside the
  ;; feet) and walk [2466 73 2292] (far) alternated every second, 23 swings in 5.6 s, a ghast shooting,
  ;; 13 hits, dead at 1m48
  (let [base (-> (wide (standing {}))
                 (assoc :player/dimension nether
                        :player/inventory {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}
                        :time/now 8000))
        far? #(= [:walk :pearls]
                 ((juxt :intent/kind :intent/for) (plan/next-intent % row plan/goals)))]
    (is (far? base) "nothing in sight: travels, as before")
    (is (not (far? (assoc base :pearls/shot-at-ms 5000)))
        "shot at three seconds ago: stays where it is")
    (is (far? (assoc base :pearls/shot-at-ms 5000 :time/now 16000)) "eleven seconds on: travels")
    (is (pearls/recently-shot? (assoc base :pearls/shot-at-ms 5000)))
    (is (not (pearls/recently-shot? base)))))

(deftest on-open-ground-a-block-is-laid-between-the-bot-and-a-ghast
  ;; pearl-fight-c job 1 (plan run 41155212096, issue #263): ghast 88 at [2463 101 2235], the bot at
  ;; [2474 99 2233] on open ground, "no cover" and stood in the fire. Offsets kept: 11 west, 2 up, 2 south
  (let [ghast (mob :ghast [-2.5 66.0 10.5])
        held {0 (item :iron_sword 1) 1 (item :cobblestone 64)}
        w (-> (wide (standing {7 ghast}))
              (assoc :player/inventory held))
        wall {:intent/kind :place
              :intent/item :cobblestone
              :intent/cell [7 64 8]
              :intent/for :pearls
              :intent/wall? true}]
    (is (pearls/shot-at? w))
    (is (nil? (pearls/hide-cell w (get-in w [:world/entities 7])))
        "open ground: nothing to hide in")
    (is (nil? (pearls/line-cell w (get-in w [:world/entities 7])))
        "and nothing to lay against on the line")
    (is (= wall (pearls/cover-from w)) "so a block at the feet's level on its side")
    (testing "then one over it"
      (let [floor (-> (wide (standing {7 ghast} {[7 64 8] stone}))
                      (assoc :player/inventory held))]
        (is (= [7 65 8] (:intent/cell (pearls/cover-from floor))))))))

(deftest a-nether-climb-stops-under-the-bedrock-roof
  ;; pearls-travel-d job 3 (plan run 41156746096, issue #263): the open pad was cut in rock; stairs
  ;; up from y 72 in flights of eight to y 126, then :rejected three times at the server's bedrock
  (let [w (-> (burrowed [8.5 100.0 8.5] {})
              (assoc :player/dimension nether))]
    (is (= 120 (pearls/roof-cap w 112)) "eight up from 112 is the roof")
    (is (= 120 (pearls/roof-cap w 118)))
    (is (= 80 (pearls/roof-cap w 72)))
    (is (= 119 (pearls/roof-cap (assoc w :player/dimension "minecraft:overworld") 111))
        "elsewhere eight")
    (is (nil? (pearls/climb-out (assoc w :player/pos [8.5 119.0 8.5]))) "no flight at the roof")))

(deftest a-lone-piglin-is-avoided-not-fought-in-the-open
  ;; pearls-travel-d job 5 (plan run 41156746096, issue #263): 73 minutes, two pearls, then :defend
  ;; fought a piglin 2.8 blocks away with no burrow yet: two swings, hits of 6.7, dead
  (let [piglin (mob :piglin [11.5 64.0 8.5])
        zombie (mob :zombie [11.5 64.0 8.5])
        zpig (mob :zombified_piglin [11.5 64.0 8.5])
        w (fn [m]
            (assoc (standing {7 m})
                   :plan/goals #{:ender-pearls}
                   :player/inventory {0 (item :iron_sword 1) 1 (item :cobblestone 64)}))]
    (is (pearls/swarmed? (w piglin)) "one piglin at full health")
    (is (not (pearls/swarmed? (w zombie))) "one zombie: the fight rules")
    (is (not (pearls/swarmed? (w zpig))) "a zombified piglin is neutral until it is hit")
    (is (contains? (combat/answering (w piglin) :pearls) :piglin) ":defend leaves it to the hunt")
    (is (not (contains? (combat/answering (w zombie) :pearls) :zombie)))
    (testing "only when the pearl stage is in play: at an earlier stage a silverfish is :defend's"
      (let [early (assoc (w (mob :silverfish [11.5 64.0 8.5])) :plan/goals #{:wood})]
        (is (not (pearls/pearls-stage? early)))
        (is (not (contains? (combat/answering early :pearls) :piglin)))
        (is (not (contains? (combat/answering early :pearls) :silverfish))))
      (is (pearls/pearls-stage?
           (assoc (w piglin) :plan/goals #{:wood} :plan/reached #{:blaze-rods}))))
    (is (not= :fight (:intent/kind (plan/next-intent (w piglin) row plan/goals))))))

(deftest on-open-ground-with-no-block-a-hole-is-dug-under-the-feet
  ;; pearls-travel-d job 6 (plan run 41156746096, issue #263), the fold at 0.22 to 0.4 min: the bot at
  ;; [2463 101 2230] on open ground, ghast 53 at [2454.5 101 2265.5] in sight, shot-at? true, the
  ;; pearl kit holds no block, cover-from nil, and the hunt went on with its stairs; fireballed at 39 s
  (let [ghast (mob :ghast [8.5 64.0 44.5])
        w (-> (wide (standing {7 ghast}))
              (assoc :player/inventory {0 (item :iron_sword 1) 1 (item :iron_pickaxe 1)}))]
    (is (pearls/shot-at? w))
    (is (nil? (pearls/hide-cell w (get-in w [:world/entities 7]))))
    (is (= {:intent/kind :dig :intent/target [8 63 8] :intent/for :pearls} (pearls/cover-from w))
        "the cell under the feet: a hole to stand in, the rim between it and the ghast")
    (is (= :dig (:intent/kind (plan/next-intent w row plan/goals))))))

(deftest no-pearl-for-eight-minutes-relocates-the-hunt-and-leaves-the-burrow-behind
  ;; pearl-fight-e job 4 (plan run 41157320074): one pearl in 102 minutes, 943 lookouts and 26
  ;; burrows beside an angry enderman parked at 8 blocks and calm ones at 7 to 9, others at 52, 53
  ;; and 63. Production as data: no pearl gained in relocate-ms starts a relocation, however the
  ;; lookouts and walks count (wandering? was false throughout)
  (let [w (-> (standing {})
              (assoc :time/now 0)
              pearls/track-gain)
        at (fn [ms]
             (-> w
                 (assoc :time/now ms)
                 pearls/track-gain
                 pearls/relocation))]
    (is (= {:held 0 :at 0} (:pearls/gain w)) "the clock starts with the first tick")
    (is (nil? (:pearls/relocate (at (dec pearls/relocate-ms)))) "seven minutes fifty-nine: not yet")
    (is (= {:from [8 64 8] :at pearls/relocate-ms :near #{}}
           (:pearls/relocate (at pearls/relocate-ms)))
        "eight minutes without a pearl")
    (testing "a pearl gained moves the clock on"
      (let [got (-> (at 400000)
                    (assoc-in [:player/inventory 5] (item :ender_pearl 1))
                    (assoc :time/now 500000)
                    pearls/track-gain
                    pearls/relocation)]
        (is (= {:held 1 :at 500000} (:pearls/gain got)))
        (is (nil? (:pearls/relocate got)))))
    (testing "thirty-two blocks on, it is over: the burrow and the blacklist are left behind"
      (let [begun (-> (at pearls/relocate-ms)
                      (assoc :pearls/shelter [8 61 8] :pearls/mouth [3 64 8] :plan/blacklist #{9}))
            there (-> begun
                      (assoc :player/pos [48.5 64.0 8.5] :time/now 600000)
                      pearls/relocation)]
        (is (nil? (:pearls/relocate there)))
        (is (nil? (:pearls/shelter there)))
        (is (nil? (:plan/blacklist there)))
        (is (= 600000 (get-in there [:pearls/gain :at])) "and the clock starts again")))))

(deftest a-relocation-walks-toward-the-densest-endermen-past-thirty-two-blocks
  ;; pearl-fight-e job 4's state: an angry enderman parked 8.5 blocks off for an hour, a lone one
  ;; 37 off, and three together 42 to 50 off: the walk goes to the three
  (let [angry (assoc (enderman [17.0 64.0 8.5]) :entity/creepy? true)
        group [(enderman [50.5 64.0 8.5]) (enderman [52.5 64.0 12.5]) (enderman [48.5 64.0 4.5])]
        lone (enderman [8.5 64.0 -28.5])
        w (-> (wide (standing {1 angry 2 lone 3 (nth group 0) 4 (nth group 1) 5 (nth group 2)}))
              (assoc-in [:player/inventory 1] (item :iron_pickaxe 1))
              (assoc :time/now 3600000
                     :pearls/angry {1 {:since 0 :best 8.5 :closed-at 0}}
                     :pearls/gain {:held 1 :at 0}
                     :pearls/relocate {:from [8 64 8] :at 3600000}))
        walk (pearls/relocate-walk w)]
    (is (= :walk (:intent/kind walk)))
    (is (= :relocate (:intent/for walk)) "marked, so that it is cut when the relocation ends")
    (is (> (first (:intent/target walk)) 40) "toward the group, not the lone one")))

(deftest a-relocation-ends-short-of-the-endermen-it-walks-toward
  ;; pearl-fight-g job 4 (plan run 41166403154): the walk went the whole way to the cluster and was
  ;; slain in the open fifteen blocks from a calm one. It ends half the distance on, within
  ;; cluster-radius of an enderman that was not near the start, and the running walk is cut
  (let [calm (enderman [40.5 64.0 8.5])
        base (-> (standing {1 calm})
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
    (is (plan/interrupts? (pearls/relocation arrived) row walk) "the walk is cut once it is over")
    (is (not (plan/interrupts? base row walk)) "and not while it goes on")))
