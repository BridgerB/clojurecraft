(ns clojurecraft.pearls.move-test
  "The pearl hunt's movers: roam, wander, explore, travel, relocate and the cuts out of a pocket."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest a-roam-with-every-end-spent-walks-to-the-farthest-again
  ;; gym batch pearls-warped9-a (plan run 39898400239) run 1: three pearls by about minute
  ;; forty, then four hours of lookouts in one place with no enderman tracked and nothing for
  ;; the roam to go after. Never one of the last two ends walked to, though: gym batch
  ;; pearls-w1 (plan run 40387599204) run 2 walked between [2474 63 2168] and [2487 63 2168]
  ;; 1,443 times to the five-hour timeout, each the farthest spent end from the other
  (let [col (world/column {})
        w (-> (ps/standing {})
              (assoc :time/now 0 :world/chunks (zipmap [[0 0] [1 0] [0 1] [1 1]] (repeat col))))
        spent (loop [w w
                     n 0
                     seen []]
                (let [to (:intent/target (pmove/roam w))]
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
        again (:intent/target (pmove/roam back))]
    (is (< 2 (count seen)) "ends to spend, and more than the last two")
    (is (contains? (set seen) to) "every end a route reaches spent: a far one is walked to again")
    (is (not (contains? recent to)) "never where it stands or where it just came from")
    (is (>= (path/octile [8 64 8] to) (- (apply max (map #(path/octile [8 64 8] %) allowed)) 2))
        "the farthest of the rest a route reaches (ends the search only came to may lie farther)")
    (is (= {:intent/kind :walk :intent/target to :intent/for :pearls} (pmove/roam w*)))
    (is (not (contains? #{to (last seen)} again))
        "arrived there, the next revisit is neither it nor the end before it: no ping-pong")))

(deftest a-walk-toward-one-never-ends-where-a-walk-already-arrived
  ;; gym batch pearls-warped3-a (plan run 39529384355, probe 39543118281) run 3: for twenty-five
  ;; minutes the bot walked between the ends of the routes toward two endermen, each end four
  ;; nearer one of them than the other end was; run 1 walked between its burrow's mouth and one
  ;; such end, ninety-eight times
  (let [w (-> (ps/standing {7 (ps/enderman [8.5 70.0 30.5])})
              (assoc :time/now 0)
              (assoc-in [:world/chunks [0 1]] (world/column ps/cliff)))
        been (memory/remember-intention
              w
              {:intent/id 1 :intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
              :done)]
    (is (= #{[8 64 19]} (memory/arrived been)) "where a walk arrived is a fact")
    (is (= {:intent/kind :walk :intent/target [8 64 19] :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "the cliff's foot, not yet walked to")
    (is (nil? (pmove/toward been (psense/endermen been pparams/seek-radius)))
        "walked to once: not again")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent been ps/row plan/goals))
        "the lookout instead")
    (let [open (-> (ps/standing {})
                   (assoc :time/now 0)
                   (assoc-in [:world/chunks [1 0]] (world/column {})))
          end (:intent/target (pmove/roam open))
          roamed (memory/remember-intention
                  open
                  {:intent/id 2 :intent/kind :walk :intent/target end :intent/for :pearls}
                  :done)]
      (is (some? end) "a roam east on open ground")
      (is (not= end (:intent/target (pmove/roam roamed)))
          "roamed to once: the next heading, somewhere else"))))

(deftest a-roam-with-every-near-end-walked-to-goes-farther
  ;; batch pearls-warped6-b (plan run 39700288080, probe 39711523945) run 5: six pearls in
  ;; twenty-two minutes from one stretch of the floor, then no enderman within 64 for most of
  ;; the last sixteen, every end the roam found within thirty blocks already walked to (86 of
  ;; them), and lookouts from the twenty-seventh minute to the end
  (let [open (reduce #(assoc-in %1 [:world/chunks [%2 0]] (world/column {}))
                     (assoc (ps/standing {}) :time/now 0)
                     [1 2 3])
        far? (fn [c] (>= (path/octile [8 64 8] c) (* 2 pparams/roam-reach)))
        roamed (fn [[w ends] n]
                 (if-let [end (:intent/target (pmove/roam w))]
                   [(memory/remember-intention w
                                               {:intent/id n :intent/kind :walk :intent/target end}
                                               :done) (conj ends end)]
                   (reduced [w ends])))
        [_ ends] (reduce roamed [open []] (range 12))]
    (is (= (count ends) (count (set ends))) "each end somewhere else")
    (is (not (far? (first ends))) "the first sixteen off")
    (is (some far? ends) "the near ends spent, one thirty-two off or more")
    (is (= 64 (second (first (filter far? ends)))) "on the floor, by the route's own measure")))

(deftest a-hunt-walled-in-with-no-end-to-roam-to-cuts-its-way-out
  ;; gym batch pearls-warped8-a (plan run 39731473452, probes 39776762205, 39776787768,
  ;; 39778589970 and 39780802996): runs 2 and 3 held 3 and 4 pearls by their 21st and 31st
  ;; minutes, then stood in lookouts for four and a half hours, every route from the feet ending
  ;; within a few blocks, the roam finding no end and every enderman past cut-reach; run 2 at y 45
  ;; in its own stairs and tunnels with 21 digs left, its second flight down having taken a stair
  ;; of its first
  (let [above [8.5 80.0 48.5]
        w (ps/walled-in ps/feet-cells above)]
    (is (= 40 (pupkeep/digs-left w)))
    (is (nil? (pmove/roam w)) "no end for a roam in the pocket")
    (is (nil? (pmove/ascend w (psense/endermen w pparams/descend-radius)))
        "and none within cut-reach for the stairs up toward one")
    (is (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent w ps/row plan/goals)))
    (is (= {:intent/kind :stairs-up
            :intent/to-y 72
            :intent/dir [0 1]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent (ps/looked-out w) ps/row plan/goals))
        "after a lookout that found nothing, stairs up toward it, descend-step up")
    (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [0 1] :intent/for :pearls}
           (pmove/dig-out (ps/walled-in ps/feet-cells [8.5 50.0 20.5])))
        "down toward one below")
    (is (= {:intent/kind :tunnel
            :intent/dir [0 1]
            :intent/length pparams/bore-length
            :intent/for :pearls}
           (pmove/dig-out (ps/walled-in ps/feet-cells [8.5 65.0 20.5])))
        "a tunnel toward one on this level")
    (is (= {:intent/kind :walk :intent/target [8 64 14] :intent/for :pearls}
           (pmove/dig-out (ps/walled-in (ps/passage 14 8) [8.5 80.0 24.5])))
        "from the end of the pocket nearest it first")
    (is (nil? (pmove/dig-out (assoc w :plan/boxed-at #{[8 64 8]}))) "boxed here: not again")
    (is (nil? (pmove/dig-out (update w :player/inventory dissoc 1))) "no pickaxe: no cut")
    ;; sim probes 39889965974 and 39889946639 (world R3, seeds 4 and 5): cuts out of pockets wore
    ;; the pickaxe out, and with none to dig a burrow the hunt fought calm ones in the open and
    ;; was slain
    (is (nil? (pmove/dig-out (assoc-in w [:player/inventory 1 :components :damage] 229)))
        "21 digs left: kept for a burrow")
    (is (nil? (pmove/dig-out (assoc w :world/entities {}))) "no enderman: nowhere to cut to")))

(deftest a-pocket-with-no-enderman-to-cut-toward-is-climbed-out-of
  ;; gym batch pearls-w3 (plan run 40434266158) run 2: five pearls by its forty-fourth
  ;; minute, then :lookout to the five-hour timeout in the caves under the forest floor,
  ;; every roam end spent or short and no enderman tracked for dig-out to cut toward
  (let [w (assoc (ps/walled-in ps/feet-cells [8.5 80.0 48.5]) :world/entities {})]
    (is (nil? (pmove/roam w)) "no end for a roam in the pocket")
    (is (nil? (pmove/dig-out w)) "no enderman: nowhere to cut to")
    (is (not (pmove/stuck? (ps/stuck-in w (dec pparams/stale-lookouts)))))
    (is (pmove/stuck? (ps/stuck-in w pparams/stale-lookouts))
        "a minute of lookouts since the last walk")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (ps/stuck-in w (dec pparams/stale-lookouts)) ps/row plan/goals))
        "before the minute is out it still looks")
    (is (= [:stairs-up 72 true :pearls]
           ((juxt :intent/kind :intent/to-y :intent/to-height? :intent/for)
            (plan/next-intent (ps/stuck-in w pparams/stale-lookouts) ps/row plan/goals)))
        "stuck: stairs up toward the floor, descend-step up")
    (is (nil? (pmove/climb-out (assoc w :plan/boxed-at #{[8 64 8]}))) "boxed here: not again")
    (is (nil? (pmove/climb-out (assoc-in w [:player/inventory 1 :components :damage] 229)))
        "21 digs left: kept for a burrow")))

(deftest a-way-out-is-cut-where-the-rock-is-already-cut
  ;; the same run 2: its second flight down took the floor of its first flight's third stair, and
  ;; the way back up lay two stairs north, open, one block to lay and no dig
  (let [open (into ps/feet-cells [[8 66 8] [7 66 8] [7 65 8]])
        w (ps/walled-in open [8.5 80.0 20.5])]
    (is (= [0 1]
           (pmove/cheapest-heading (ps/walled-in ps/feet-cells [8.5 80.0 20.5])
                                   :stairs-up
                                   [8 64 8]
                                   [0 1]))
        "every heading as dear: toward it")
    (is (= [-1 0] (pmove/cheapest-heading w :stairs-up [8 64 8] [0 1]))
        "the heading whose stair is open")
    (is (= [-1 0] (:intent/dir (pmove/dig-out w))))))

(deftest one-far-below-no-route-reaches-is-descended-to-by-stairs
  ;; batch pearls-warped-c (plan run 39152813557, probe 39157605071): both landings that lived
  ;; stood on a ledge over the forest floor, every enderman 14 to 50 blocks below, no route down
  ;; and none in sight; run 2 walked from its burrow's mouth to its end, three lower and nearer by
  ;; the route's measure of height, and out again for forty minutes
  (let [below (ps/enderman [8.5 40.0 8.5])
        w (assoc-in (ps/standing {7 below}) [:player/inventory 1] (ps/item :iron_pickaxe 1))]
    (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [-1 0] :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "a flight of stairs toward it, descend-step down; one straight below is not at hand")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :plan/boxed-at #{[8 64 8]}) ps/row plan/goals))
        "a staircase from here was boxed: not again from here")
    (is (nil? (pmove/descend (ps/standing {7 below}) {7 below})) "no pickaxe: no stairs")
    (is (nil? (pmove/descend w {7 (ps/enderman [8.5 59.0 8.5])}))
        "five below is a slope's worth, not another level: the roam's")
    ;; batch pearls-warped6-b (plan run 39700288080, probe 39711523945) run 2: four flights down
    ;; toward endermen 47 to 69 blocks off on the plane and 10 to 14 below, about 90 digs, in the
    ;; five minutes after its first kill, and the pickaxe worn out at eleven minutes; the flights
    ;; that paid, run 4's seven kills and run 5's four, went toward ones 12 to 31 off
    (is (nil? (pmove/descend w {7 (ps/enderman [48.5 40.0 8.5])}))
        "forty off on the plane, as far below: walked toward first, no flight toward it")
    (is (not= :stairs-down
              (:intent/kind (plan/next-intent
                             (assoc-in w [:world/entities 7 :entity/pos] [48.5 40.0 8.5])
                             ps/row
                             plan/goals))))
    (is (= 56 (:intent/to-y (pmove/descend w {7 (ps/enderman [40.5 40.0 8.5])})))
        "thirty-two off: within cut-reach, the flight as before")
    (is
     (= :stairs-down (:intent/kind (pmove/descend w {7 below 9 (ps/enderman [8.5 65.0 30.5])})))
     "one on this level too, hidden and far: the flight toward the near one below still goes
         (run 4's paying flights went with one to seven on its level within 128)")
    (let [w (ps/burrowed [3.5 64.0 8.5] {7 (ps/enderman [12.5 40.0 8.5])} ps/ledge-wall)]
      (is (nil? (pmove/toward w (psense/endermen w pparams/seek-radius)))
          "the only route east ends in the burrow, five nearer on the plane: never into it")
      (is (= {:intent/kind :lookout :intent/for :pearls}
             (plan/next-intent (ps/looked-out (ps/looked w 11)) ps/row plan/goals))
          "the burrow fresh: a lookout at its mouth, never a walk or a dig out of its reach")
      (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [1 0] :intent/for :pearls}
             (plan/next-intent (ps/looked-out (ps/looked w 12)) ps/row plan/goals))
          "a minute of them, the burrow given up: stairs down toward it from the mouth"))))

(deftest one-far-above-no-route-reaches-is-climbed-to-by-stairs
  ;; batch pearls-warped5-b (plan run 39671293287, probe 39683564524): run 5 took five pearls in
  ;; 150 s on the forest floor, cut seven flights down to three endermen in a cave and killed
  ;; them, then stood thirty-two minutes under the floor with every enderman 20 to 30 blocks
  ;; over it; runs 2, 3 and 4 hunted under the floor the same way. The stairs down had no way back
  (let [above (ps/enderman [8.5 90.0 8.5])
        w
        (assoc-in (ps/standing {7 above} ps/slab) [:player/inventory 1] (ps/item :iron_pickaxe 1))]
    (is (nil? (psense/quarry w)) "hidden by the slab")
    (is (= {:intent/kind :stairs-up
            :intent/to-y 72
            :intent/dir [-1 0]
            :intent/to-height? true
            :intent/for :pearls}
           (plan/next-intent w ps/row plan/goals))
        "a flight of stairs toward it, descend-step up, ending at that height")
    (is (= {:intent/kind :lookout :intent/for :pearls}
           (plan/next-intent (assoc w :plan/boxed-at #{[8 64 8]}) ps/row plan/goals))
        "a staircase from here was boxed: not again from here")
    (is (nil? (pmove/ascend (ps/standing {7 above} ps/slab) {7 above})) "no pickaxe: no stairs")
    (is (nil? (pmove/ascend w {7 (ps/enderman [8.5 69.0 8.5])}))
        "five above is a slope's worth, not another level: the roam's")
    (is (= :stairs-down
           (:intent/kind (plan/next-intent
                          (assoc-in w [:world/entities 9] (ps/enderman [8.5 40.0 8.5]))
                          ps/row
                          plan/goals)))
        "one as far below too: the stairs down first, as before")
    (is (nil? (pmove/ascend w {7 above 9 (ps/enderman [8.5 65.0 30.5])}))
        "one on this level too, hidden: no climb while the level still has one to hunt")))

(deftest a-shelter-walk-asks-the-blacklist
  ;; gym plan run 40583923956 run 3 (issue #263): the walk to the burrow's mouth, planned fifteen
  ;; times in 0.6 s, each failing :no-path
  (let [w (assoc (ps/standing {}) :plan/blacklist #{[2497 53 2134]})]
    (is (nil? (pmove/shelter-walk w [2497 53 2134])))
    (is (nil? (pmove/shelter-walk w nil)))
    (is (= {:intent/kind :walk :intent/target [1 64 1] :intent/for :shelter}
           (pmove/shelter-walk w [1 64 1])))))

(deftest a-hunt-with-no-enderman-it-can-walk-to-goes-elsewhere-after-five-minutes
  (testing "the lookouts stood: not yet, then enough"
    (is (not (pmove/wandering? (ps/looked ps/stranded (dec pparams/wander-lookouts)))))
    (is (pmove/wandering? (ps/looked ps/stranded pparams/wander-lookouts))))
  (testing "an enderman with a route keeps the hunt where it is"
    (let [w (ps/looked (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 (ps/enderman [30.5 64.0 8.5])}))
                       pparams/wander-lookouts)]
      (is (not (pmove/wandering? w)))))
  (testing "the walk is to ground at least roam-reach off"
    (let [{:intent/keys [kind target for]} (pmove/wander (ps/looked ps/stranded
                                                                    pparams/wander-lookouts))]
      (is (= [:walk :pearls] [kind for]))
      (is (>= (path/octile (intent/feet ps/stranded) target) pparams/roam-reach))))
  (testing "and the hunt walks, where it would otherwise stand in lookouts"
    (is (= :walk
           (:intent/kind (plan/next-intent (ps/looked ps/stranded pparams/wander-lookouts)
                                           ps/row
                                           plan/goals))))))

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
    (let [w (-> ps/stranded
                (assoc :player/pos [8.5 61.0 8.5])
                (ps/walked (take pparams/loiter-walks
                                 (cycle [[8 61 8] [(+ 8 dx) (+ 61 dy) (+ 8 dz)]]))))]
      (is (pmove/loitering? w) (str "job " job " shuffles"))
      (is (pmove/wandering? w) (str "job " job " wanders"))))
  (testing "twelve walks that went somewhere are no loitering"
    (let [w (ps/walked ps/stranded (for [k (range pparams/loiter-walks)] [(+ 8 (* 20 k)) 61 8]))]
      (is (not (pmove/loitering? w)))))
  (testing "eleven walks are too few"
    (let [w (ps/walked ps/stranded (repeat (dec pparams/loiter-walks) [8 61 8]))]
      (is (not (pmove/loitering? w))))))

(deftest a-shuffle-wanders-even-with-an-enderman-a-route-reaches
  ;; gym plan run 40905321229 job 6 (issue #263, pearls-12): [2242 76 2701] and [2244 76 2701]
  ;; walked 136 times over 51 minutes, an angry enderman routable and never coming
  (let [angry (assoc (ps/enderman [30.5 64.0 8.5]) :entity/creepy? true)
        w (-> (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 angry}))
              (ps/walked (take pparams/loiter-walks (cycle [[8 61 8] [10 61 8]]))))]
    (is (some? (pmove/routable w (psense/endermen w pparams/seek-radius))) "a route reaches it")
    (is (pmove/loitering? w))
    (is (pmove/wandering? w))
    (is (not (pmove/wandering? (ps/looked (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 angry}))
                                          pparams/wander-lookouts)))
        "lookouts alone, with one routable: as before, it stays")))

(deftest a-wander-comes-before-the-lookouts-of-a-burrow-with-an-angry-one-near
  ;; gym plan run 40973173654 (pearls-stuck-f) jobs 2, 4, 5 and 6: the fold at 120, 200, 60 and 105
  ;; minutes had wandering? and wander both true and next-intent :lookout or the walk to the mouth,
  ;; because the lure, crowd and quarry branches come first
  (let [angry (assoc (ps/enderman [30.5 64.0 8.5]) :entity/creepy? true)
        w (-> (ps/wide (ps/burrowed [8.5 61.0 8.5] {7 angry}))
              (assoc :plan/given-up {7 0})
              (ps/walked (take pparams/loiter-walks (cycle [[8 61 8] [10 61 8]]))))
        i (plan/next-intent w ps/row plan/goals)]
    (is (pmove/wandering? w))
    (is (some? (pmove/wander w)))
    (is (not (psense/menaced? w)) "the angry one is not at the feet")
    (is (= [:walk :pearls] [(:intent/kind i) (:intent/for i)]) (pr-str i))
    (is (not= [10 61 8] (:intent/target i)))
    (is (>= (path/octile (intent/feet w) (:intent/target i)) pparams/roam-reach))))

(deftest a-hunt-with-no-warped-forest-near-travels-or-explores
  ;; real race 40980342194, Clj_r13 (issue #263): two hours of lookouts and walks round a fortress
  ;; at x 4250-4440, z 1700-1750, no pearl, 0 pearls in all of the race's 6 to 7 bots with rods:
  ;; no code went to a warped forest
  (let [base (-> (ps/wide (ps/standing {}))
                 (assoc :player/dimension ps/nether)
                 (assoc :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))
        forest (fn [chunk pos]
                 {:forest/chunk chunk
                  :forest/kind :warped
                  :forest/count 40
                  :forest/pos pos
                  :forest/at 0
                  :forest/dimension ps/nether})
        known (memory/remember-forest base (forest [30 0] [480 70 8]))
        here (memory/remember-forest base (forest [0 0] [8 63 8]))]
    (testing "none known: explores, as far as the loaded ground goes"
      (let [i (plan/next-intent base ps/row plan/goals)]
        (is (= [:walk :pearls] [(:intent/kind i) (:intent/for i)]) (pr-str i))
        (is (>= (path/octile (intent/feet base) (:intent/target i)) pparams/roam-reach))))
    (testing "one known: the cell over its nylium"
      (is (= {:intent/kind :walk :intent/target [480 71 8] :intent/for :pearls}
             (plan/next-intent known ps/row plan/goals))))
    (testing "one failed (blacklisted): not again"
      (is (not= [480 71 8]
                (:intent/target (plan/next-intent (assoc known :plan/blacklist #{[480 71 8]})
                                                  ps/row
                                                  plan/goals)))))
    (testing "in one: the hunt, no travel"
      (is (nil? (pmove/travel here)))
      (is (psense/in-forest? here))
      (is
       (= {:intent/kind :lookout :intent/for :pearls} (plan/next-intent here ps/row plan/goals))
       "in a forest with no enderman yet: the hunt stands where it is, no walk out (a walk
           out of a forest it reached was job 5's 13 minutes of walking in pearls-travel-a)"))
    (testing "the overworld has no such search"
      (is (nil? (pmove/travel (assoc base :player/dimension "minecraft:overworld")))))
    (testing "an enderman tracked: hunted, not travelled from"
      (let [w (assoc-in base [:world/entities 7] (ps/enderman [10.5 64.0 8.5]))]
        (is (not= [:walk :pearls]
                  ((juxt :intent/kind :intent/for) (plan/next-intent w ps/row plan/goals))))))))

(deftest an-explore-goes-on-away-from-where-it-has-been
  ;; gym plan run 41134469333 (pearls-travel-b) job 2: walks of 12 s alternating between x 2240 and
  ;; x 2200 for 5.5 minutes, the heading chosen by the count of walks arrived at
  (let [w (-> (ps/wide (ps/standing {}))
              (assoc :player/dimension ps/nether
                     :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))
        west (ps/walked w [[-20 64 8] [-24 64 8] [-28 64 8]])
        east (ps/walked w [[40 64 8] [44 64 8] [48 64 8]])
        x-of #(first (:intent/target %))]
    (is (> (x-of (pmove/explore west)) 8) "been west: east")
    (is (< (x-of (pmove/explore east)) 8) "been east: west")))

(deftest ^:eftest/synchronized a-bot-on-a-ledge-over-a-cavern-goes-down-to-its-burrow-to-cut-from
  ;; pearl-fight-a job 4 (plan run 41143668588): 23 minutes in lookouts at [2533 41 2181], 141 digs
  ;; left, endermen 17 to 63 blocks off, every move nil: the feet a brink, so climb-out, dig-out
  ;; and bore refuse to cut from them; the burrow's end five blocks down was all the bot could walk to
  (let [w (-> (ps/standing {} ps/ledge)
              (assoc :player/pos [8.5 64.0 8.5] :pearls/shelter [8 61 9] :pearls/mouth [8 64 8])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
    (is (pplace/brink? w [8 64 8]) "a ledge")
    (with-redefs [intent/routable? (ps/routable-here (constantly true))]
      (is (= {:intent/kind :walk :intent/target [8 61 9] :intent/for :shelter} (pmove/uncage w))
          "down to the burrow's end")
      (is (nil? (pmove/uncage (assoc w :player/pos [8.5 61.0 9.5]))) "at the end it is not a brink")
      (is (nil? (pmove/uncage (dissoc w :pearls/shelter)))
          "with no burrow, nothing to go down to"))))

(deftest a-hunt-caged-in-a-pocket-cuts-its-way-out
  ;; gym plan run 41134469333 (pearls-travel-b) job 6 (issue #263), the fold at 25 min: feet
  ;; [2500 47 2155] at the burrow's end, every heading's route ended within thirteen blocks (:none,
  ;; the frontier spent: 17 and 35 waypoints), so wander was nil; and at home the hunt never
  ;; reached the roam, climb-out and dig-out of its :else chain. 136 walks over 41 minutes
  (let [w (-> (ps/burrowed [8.5 61.0 8.5] {})
              (ps/walked (take pparams/loiter-walks (cycle [[8 61 8] [10 61 8]]))))]
    (is (pmove/loitering? w))
    (is (nil? (pmove/wander w)) "one chunk loaded: no route reaches sixteen blocks out")
    (is (= :stairs-up (:intent/kind (plan/next-intent w ps/row plan/goals)))
        "so it cuts stairs up toward the open ground")
    (testing "not loitering: nothing coming, so out to the mouth to look round from"
      (is
       (= {:intent/kind :walk :intent/target [3 64 8] :intent/for :shelter}
          (plan/next-intent (ps/burrowed [8.5 61.0 8.5] {}) ps/row plan/goals))
       "the branch (and in? mouth (nil? ce) ...): the walk to the mouth, as in the travel test
           nothing-at-the-mouth. My earlier expectation (a lookout) was written without a run and
           was wrong from the start"))))

(deftest a-hunt-sealed-in-the-nether-with-nowhere-to-travel-cuts-its-way-out
  ;; pearls-travel-c (plan run 41144362671) jobs 2 and 3, pearls-12-from-fortress: landed in the
  ;; gym's netherrack pad, lookout x1262 for 106 minutes with no walk at all
  (let [sealed (-> (ps/burrowed [8.5 61.0 8.5] {})
                   (assoc :player/dimension ps/nether))]
    (is (pmove/travel-caged? sealed))
    (is (nil? (pmove/travel sealed)))
    (is (= :tunnel (:intent/kind (plan/next-intent sealed ps/row plan/goals)))
        "in the Nether, level: there is no sky to climb to")
    (testing "the overworld has no such search"
      (is (not (pmove/travel-caged? (assoc sealed :player/dimension "minecraft:overworld")))))))

(deftest a-nether-climb-stops-under-the-ceiling
  ;; pearls-travel-d job 3 (plan run 41156746096, issue #263): the open pad was cut in rock; stairs
  ;; up from y 72 in flights of eight to y 126, then :rejected three times at the server's bedrock.
  ;; Probe 42374244659 (sim R1/17 seed 1): with the cap at the roof, stairs up from 88 to 120 and an
  ;; hour in a cavity under the roof, with no forest or enderman there
  (let [w (-> (ps/burrowed [8.5 100.0 8.5] {})
              (assoc :player/dimension ps/nether))]
    (is (= 100 (pmove/roof-cap w 96)) "a pocket at y 96: the flight stops at the ceiling")
    (is (= 100 (pmove/roof-cap w 112)))
    (is (= 100 (pmove/roof-cap w 118)))
    (is (= 80 (pmove/roof-cap w 72)))
    (is (every? #(<= (:intent/to-y %) pparams/nether-climb-ceiling)
                (keep #(pmove/climb-out (assoc w :player/pos [8.5 (double %) 8.5])) (range 80 125)))
        "no climb reaches past the ceiling, from any height")
    (is (nil? (pmove/climb-out (assoc w :player/pos [8.5 99.0 8.5]))) "no flight at the ceiling")
    (is (= 119 (pmove/roof-cap (assoc w :player/dimension "minecraft:overworld") 111))
        "elsewhere eight")
    (is (nil? (pmove/climb-out (assoc w :player/pos [8.5 119.0 8.5]))) "no flight at the roof")))

(deftest a-relocation-walks-toward-the-densest-endermen-past-thirty-two-blocks
  ;; pearl-fight-e job 4's state: an angry enderman parked 8.5 blocks off for an hour, a lone one
  ;; 37 off, and three together 42 to 50 off: the walk goes to the three
  (let [angry (assoc (ps/enderman [17.0 64.0 8.5]) :entity/creepy? true)
        group [(ps/enderman [50.5 64.0 8.5]) (ps/enderman [52.5 64.0 12.5])
               (ps/enderman [48.5 64.0 4.5])]
        lone (ps/enderman [8.5 64.0 -28.5])
        w (-> (ps/wide (ps/standing
                        {1 angry 2 lone 3 (nth group 0) 4 (nth group 1) 5 (nth group 2)}))
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :time/now 3600000
                     :pearls/angry {1 {:since 0 :best 8.5 :closed-at 0}}
                     :pearls/gain {:held 1 :at 0}
                     :pearls/relocate {:from [8 64 8] :at 3600000}))
        walk (pmove/relocate-walk w)]
    (is (= :walk (:intent/kind walk)))
    (is (= :relocate (:intent/for walk)) "marked, so that it is cut when the relocation ends")
    (is (> (first (:intent/target walk)) 40) "toward the group, not the lone one")))

(deftest ^:eftest/synchronized
         a-relocation-with-no-route-and-nothing-tracked-falls-back-to-the-cage-cuts
  ;; pearl-fight-g jobs 3 and 5 (plan run 41166403154): relocate-walk was nil, six remembered
  ;; endermen 82 to 110 blocks off with no route in job 3, none tracked in a cave pocket in job 5,
  ;; and neither bot moved in 80 minutes. From a ledge it goes down to its burrow to cut from there
  (let [w (-> (ps/standing {} ps/ledge)
              (assoc :player/pos [8.5 64.0 8.5] :pearls/shelter [8 61 9] :pearls/mouth [8 64 8])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))]
    (with-redefs [intent/routable? (ps/routable-here (constantly true))]
      (is (= {:intent/kind :walk :intent/target [8 61 9] :intent/for :relocate}
             (pmove/relocate-walk w))))))

(deftest a-distant-sighting-is-gone-toward-until-in-hunt-range
  ;; race-read 1105b Q1: 23 of 141 real rod bots saw an enderman, every first sighting
  ;; 53.9 to 104.8 blocks off, past hunt-radius 32, so endermen filtered it out and
  ;; stare/provoke stayed 0. A safe bot out of the fortress goes toward the sighting.
  (let [w (ps/distant-world [64.5 64.0 8.5])
        i (pmove/go-toward-sighting w)]
    (is (= 7 (first (pmove/distant-sighting w))) "56 blocks off is a sighting, past hunt-radius")
    (is (= :walk (:intent/kind i)))
    (is (= :chase (:intent/for i)))
    (is (= 7 (:intent/chase-eid i)))
    (is (not= [64 64 8] (:intent/target i)) "to a stand near it, never its own cell")
    (is (<= (physics/distance (physics/centre (:intent/target i)) [64.5 64.0 8.5])
            pparams/hunt-radius)
        "the stand leaves it within hunt range")
    (testing "on another level, 40 blocks above: a go at the bot's level, not its cell"
      (let [high (ps/distant-world [40.5 104.0 8.5])
            hi (pmove/go-toward-sighting high)]
        (is (some? hi))
        (is (contains? #{:walk :tunnel-to} (:intent/kind hi)))
        (is (not= [40 104 8] (:intent/target hi)))
        (when (= :walk (:intent/kind hi))
          (is (= 64 (second (:intent/target hi)))
              "the walk stays on ground the bot can stand on"))))
    (testing "never under danger health, and never with a blaze watching"
      (is (nil? (pmove/go-toward-sighting (assoc w :player/health 8.0))))
      (let [watched (assoc w
                           :world/entities
                           {7 (ps/enderman [64.5 64.0 8.5])
                            9 {:entity/type (blocks/entity-types :blaze)
                               :entity/pos [12.5 65.0 8.5]
                               :entity/seen-at 0
                               :entity/health 20.0}})]
        (is (nil? (pmove/go-toward-sighting watched)))))
    (testing "given up after bounded failures, and blacklisted so it never churns"
      (let [gave (assoc w :pearls/chase {:eid 7 :fails pparams/chase-max-fails :since 0})]
        (is (pmove/distant-gave-up? gave 7))
        (is (nil? (pmove/distant-sighting gave)))
        (is (nil? (pmove/go-toward-sighting gave)))
        (let [tracked (pmove/track-distant gave)]
          (is (contains? (:plan/blacklist tracked) 7))
          (is (nil? (:pearls/chase tracked))))))))

(deftest the-fortress-exit-never-searches-routes
  ;; explore's eight route searches ran in every fortress-exit-dir, several a tick through
  ;; fortress-escaping?, and froze real rod bots 2 to 19 s a tick (Clj_r08, lane 42202999306
  ;; b1, profile probe 42210270571): the exit is away from the spawner with no search
  (let [w (-> (ps/standing {})
              (assoc :player/dimension "minecraft:the_nether" :player/pos [120.5 59.0 100.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [100 64 100] blaze/spawner))]
    (with-redefs [pmove/explore (fn [_] (throw (ex-info "searched" {})))
                  pmove/far-ends (fn [& _] (throw (ex-info "searched" {})))]
      (is (= [1.0 0.0] (fexit/fortress-exit-dir w)) "away from the spawner, east")
      (is (fexit/fortress-escaping? w)))))

(deftest the-way-to-a-forest-no-walk-reaches-is-a-sealed-tunnel
  ;; real-18 Clj_r09 (4f317da lane 8): boxed in, eleven walks to one forest in four seconds, each
  ;; failed :no-path, and the run ended :no-path
  (let [w (assoc-in (ps/standing {}) [:player/inventory 1] (ps/item :iron_pickaxe 1))]
    (with-redefs [intent/routable? (ps/routable-here (constantly false))]
      (is (= {:intent/kind :tunnel-to
              :intent/target [40 64 40]
              :intent/reach 1.5
              :intent/seal-behind? true
              :intent/for :pearls}
             (pmove/forest-way w [40 64 40]))
          "no route, a pickaxe: tunnel")
      (is (= :walk (:intent/kind (pmove/forest-way (ps/standing {}) [40 64 40])))
          "no pickaxe: the walk as before"))
    (with-redefs [intent/routable? (ps/routable-here (constantly true))]
      (is (= {:intent/kind :walk :intent/target [40 64 40] :intent/for :pearls}
             (pmove/forest-way w [40 64 40]))
          "a route: the walk"))))

(deftest a-far-end-near-one-already-walked-to-is-not-new-ground
  ;; Logged state, sim R1/17 (probe 42374244659, issue #263): under the roof, in the cavity of its own
  ;; tunnels, relocate walked between the two ends [1013 120 -205] and [1015 120 -165] forty minutes,
  ;; each within a block or two of the other's last arrival, a pickaxe at 131 digs unused. An end
  ;; within roam-reach of where one of the last walks arrived is left out, so the explore finds none
  ;; and the cage-break cuts a new way.
  (let [w (-> (ps/wide (ps/standing {}))
              (assoc :player/dimension ps/nether
                     :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))
        ends (mapv second (pmove/far-ends w 0 #{}))
        visited (vec (take pparams/explore-memory ends))
        after (ps/walked w (mapv #(update % 0 inc) visited))]
    (is (>= (count ends) 2) "an open world has far ends")
    (is (not-any? (fn [[_ to _]] (some #(< (path/octile to %) pparams/roam-reach) visited))
                  (pmove/far-ends after 0 #{}))
        "no end is left within roam-reach of a visited one")
    (is (< (count (pmove/far-ends after 0 #{})) (count ends)) "the ends walked to are gone")))
