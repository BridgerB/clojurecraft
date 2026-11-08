(ns clojurecraft.pearls.reach-test
  "The hunt's way to a forest when nothing else finds one: the held heading, what it turns toward,
   the rule that gives up a held target the feet do not near, and the fortress escape that ends in
   a forest (issue #263)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.rules :as rules]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn with-forest
  "w with a warped forest remembered in chunk key (twenty nylium blocks, as a loaded chunk records one)."
  [w key]
  (memory/remember-column
   (update w :time/now #(or % 5))
   key
   (world/column (into {} (for [x (range 20)] [[x 64 3] (blocks/default-state :warped_nylium)])))))

(deftest a-caged-bot-with-no-forest-known-tunnels-one-held-heading
  ;; Logged state, sim R2/21 (probe stall-R2-21, 42383622038, issue #263): caged at [-106 59 462] with
  ;; 131 digs, no forest known (the nearest 170 to 485 blocks off), explore nil: lookouts to the cap,
  ;; 106 blocks from where it began. It holds one heading and tunnels long legs along it.
  (let [netherrack (blocks/default-state :netherrack)
        rock (into {} (for [x (range 0 16) y (range 50 70) z (range 0 16)] [[x y z] netherrack]))
        w (-> (ps/standing {} rock)
              (assoc :player/dimension ps/nether :player/pos [8.5 60.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (memory/observe [2 60 2] blaze/spawner))
        held (pmove/reach-heading-hold w)
        h (:pearls/heading held)
        i (pmove/reach-heading held)
        moved (pmove/reach-heading-hold (assoc held :player/pos [8.5 60.0 20.5]))]
    (is (some? h) "a heading is picked")
    (is (= [1 0] (:dir h)) "away from the spawner at [2 60 2]")
    (is (= :tunnel-to (:intent/kind i)))
    (is (>= (- (first (:intent/target i)) 8) pparams/heading-leg) "a long leg along the heading")
    (is (<= pparams/heading-min-y (second (:intent/target i)) pparams/heading-max-y))
    (is (= h (:pearls/heading moved))
        "the same heading and leg target at the next act, from other feet")
    (is (= (:intent/target i) (:intent/target (pmove/reach-heading moved)))
        "the same target from two different feet along the leg")
    (testing "a new leg target once the feet are within 3 of it"
      (let [near (pmove/reach-heading-hold (assoc held :player/pos [(+ 8.5 62) 60.0 8.5]))]
        (is (= [(+ 8 62 pparams/heading-leg) 60 8] (:target (:pearls/heading near))))))
    (testing "a blocked leg turns a quarter and goes on"
      (let [blocked (assoc moved :plan/blacklist #{(:intent/target i)})
            turned (:pearls/heading (pmove/reach-heading-hold blocked))]
        (is (= [0 1] (:dir turned)) "blocked after the bot moved: the held target still matches")
        (is (= 1 (:turns turned)))))
    (testing "picked again after heading-span blocks"
      (is (= [(+ 8 400) 60 8]
             (:from (:pearls/heading (pmove/reach-heading-hold
                                      (assoc held :player/pos [(+ 408.5) 60.0 8.5])))))))
    (testing "dropped once a forest is known, and at a death"
      (is (not (contains? (pmove/reach-heading-hold (with-forest held [60 60])) :pearls/heading)))
      (is (not (contains? (game/die held) :pearls/heading))))))

(deftest a-bot-above-the-climb-ceiling-with-no-mover-tunnels-down-along-its-heading
  ;; Logged state, sim R1/17 seed 1 (probe p2facts 42402292588, issue #263): at [961 104 -144] in the
  ;; Nether, health 20, fortress exit done, no forest known, can-tunnel-out? true, 5 hoglins 25 to 30
  ;; blocks off. travel, explore, wander, cage-break and relocate all nil: 55 minutes of
  ;; :hunt/else lookouts, above nether-climb-ceiling (100) where no climb or route goes. The held
  ;; heading's leg target is held to y 90 and under, so the tunnel goes down and along.
  (let [netherrack (blocks/default-state :netherrack)
        pocket #{[8 104 8] [8 105 8]}
        rock (into {}
                   (for [x (range 0 16)
                         y (range 80 112)
                         z (range 0 16)
                         :when (not (contains? pocket [x y z]))]
                     [[x y z] netherrack]))
        w (-> (ps/standing {} rock)
              (assoc :player/dimension ps/nether :player/pos [8.5 104.0 8.5])
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1))
              (assoc :plan/status :active :plan/goals #{:ender-pearls} :plan/active [ps/row]))
        held (pmove/reach-heading-hold w)
        i (pmove/travel held)]
    (is (> 104 pparams/nether-climb-ceiling) "above the ceiling")
    (is (some? (:pearls/heading held)) "a heading is held with no forest known")
    (is (= :tunnel-to (:intent/kind i)) "the heading's tunnel")
    (is (nil? (pmove/travel held)) "travel finds nothing: the heading is not one of its ends")
    (is (= :tunnel-to (:intent/kind (rules/decide held))) "the hunt's last mover, after the others")
    (is (<= (second (:intent/target i)) pparams/heading-max-y) "down to y 90 and under")
    (is (= i (pmove/reach-heading (pmove/reach-heading-hold held)))
        "the same leg at the next act")))

(deftest a-fortress-exit-whose-tunnel-failed-is-given-up
  ;; Logged state, sim R1/17 seed 1 (probes rh5-20 and rh5-33, issue #263): :tunnel-to [938 75 -209]
  ;; failed :blocked in 50 ms and was chosen again: 107 attempts by minute 20, a thirty-second
  ;; :retrying wait between each, forty minutes with no intent running. A first failure is not the end
  ;; (R4/1 reached its forest through exits that failed once); a lack of progress is: the column the
  ;; exit was abandoned at (watch-progress) is not a target again, and the exit ends.
  (let [w (-> (ps/standing {})
              (assoc :player/dimension ps/nether :player/pos [-163.5 38.0 221.5])
              (memory/observe [-176 70 219] blaze/spawner)
              (memory/observe [-164 40 221] (blocks/default-state :nether_bricks))
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
        target (fexit/fortress-tunnel-target w)
        failed (assoc w :plan/exit-columns #{[(first target) (last target)]})
        once (assoc w :plan/blacklist #{target})]
    (is (some? target) "an exit target is chosen")
    (is (fexit/fortress-escaping? w))
    (is (some? (fexit/fortress-tunnel-target once)) "one failure: the exit goes on")
    (is (nil? (fexit/fortress-tunnel-target failed)) "abandoned: not that column again")
    (is (not (fexit/fortress-escaping? failed)) "the exit is over: the other movers apply")))

(deftest a-far-end-a-walk-failed-toward-is-not-chosen-again
  ;; The same bug as the exit's (issue #263, probes rh5-20 and rh5-33): a target that ignores
  ;; :plan/blacklist is chosen again after it failed. A far end whose walk failed is left out.
  (let [w (-> (ps/wide (ps/standing {}))
              (assoc :player/dimension ps/nether
                     :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)}))
        end (second (first (pmove/far-ends w 0 #{})))
        failed (assoc w :plan/blacklist #{end})]
    (is (some? end) "an open world has far ends")
    (is (not-any? #(= end (second %)) (pmove/far-ends failed 0 #{}))
        "the end a walk failed toward is not an end again")))

(deftest a-held-target-the-feet-do-not-near-is-given-up
  ;; One rule for every held target (progress-table), from the stalls it ends: the exit that failed
  ;; :blocked every 30 s for forty minutes (sim R1/17, probes rh5-20 and rh5-33), the heading's leg,
  ;; the forest tunnel that stood still at [-96 65 466] (R2/21, wide-br-R2-21) and the relocation
  ;; shuttling between two cells. Not nearer by :blocks within :ms: the intent fails :no-progress.
  (let [base (-> (ps/standing {} {})
                 (assoc :player/dimension ps/nether :time/now 0))
        running
        (fn [w kind for target]
          (assoc w
                 :plan/intent
                 {:intent/kind kind :intent/for for :intent/target target :intent/status :active}))
        kinds {:exit (-> (running base :tunnel-to :pearls [8 64 -60])
                         (assoc :pearls/exit-target [8 64 -60]))
               :heading (-> (running base :tunnel-to :pearls [8 64 -60])
                            (assoc :pearls/heading
                                   {:dir [0 -1] :from [8 64 8] :target [8 64 -60] :turns 0}))
               :forest (running base :tunnel-to :pearls [8 64 -60])
               :relocate (running base :walk :relocate [8 64 -60])}
        later (fn [w ms] (assoc w :time/now ms))]
    (doseq [[kind w] kinds
            :let [rule (pparams/progress kind)
                  started (pmove/watch-progress w)
                  waiting (pmove/watch-progress (later started (dec (:ms rule))))
                  given-up (pmove/watch-progress (later started (:ms rule)))
                  nearer (pmove/watch-progress (-> (later started (dec (:ms rule)))
                                                   (assoc :player/pos
                                                          [8.5 64.0 (- 8.5 (:blocks rule) 1.0)])))
                  then-waiting (pmove/watch-progress (later nearer
                                                            (+ (dec (:ms rule)) (:ms rule) -1)))]]
      (testing (name kind)
        (is (= kind (pmove/held-class started (:plan/intent started))) "the class of the target")
        (is (avoided/noted? given-up :blacklist [8 64 -60]) "not nearer in the time: avoided")
        (is (= (= kind :exit) (avoided/noted? given-up :exit-columns [8 -60]))
            "the exit's column too")
        (is (not (avoided/noted? waiting :blacklist [8 64 -60])) "within the time: not")
        (is (not (contains? given-up :pearls/watch)))
        (is (not (avoided/noted? nearer :blacklist [8 64 -60]))
            "nearer by the blocks: the clock restarts")
        (is (not (avoided/noted? then-waiting :blacklist [8 64 -60]))
            "and a full time again is allowed")))
    (is (not (contains? (pmove/watch-progress
                         (assoc base :plan/intent {:intent/kind :lookout :intent/status :active}))
                        :pearls/watch))
        "no held target running: nothing watched")))

(deftest a-bot-in-a-warped-forest-is-no-longer-escaping-the-fortress
  ;; Logged state, sim R4/1 seed 1 (probe wide-st2-R4-1, issue #263): in its forest from minute 8.7
  ;; with 81 endermen in sight, the exit tunnel still ran at minute 47 (fortress-escaping? stayed true,
  ;; and the exit outranks the hunt). A forest known at the feet ends the escape.
  (let [w (-> (ps/standing {})
              (assoc :player/dimension ps/nether :player/pos [-163.5 38.0 221.5])
              (memory/observe [-176 70 219] blaze/spawner)
              (memory/observe [-164 40 221] (blocks/default-state :nether_bricks))
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
        forest (with-forest w [-11 13])]
    (is (fexit/fortress-escaping? w) "near the fortress, no forest: escaping")
    (is (psense/in-forest? forest) "the feet are in a warped chunk")
    (is (not (fexit/fortress-escaping? forest)) "in a forest: the escape is over")))

(deftest a-heading-turns-toward-ground-it-has-not-seen
  ;; The sim worlds hold a warped forest 170 to 485 blocks from the fortress start (probe
  ;; forest-reach2, issue #263) and the bot sees 96: moving is not searching. With the corridor
  ;; ahead on the heading away from the fortress already seen, the heading goes where it has not
  ;; looked; a blocked leg turns to the less-seen side.
  (let [w (-> (ps/standing {} {})
              (assoc :player/dimension ps/nether :player/pos [8.5 64.0 8.5])
              (memory/observe [2 60 8] blaze/spawner))
        {:keys [near far width]} pparams/seen-corridor
        corridor (fn [[dx dz]]
                   (set (for [d (range near (inc far))
                              k (range (- width) (inc width))]
                          [(+ 0 (* d dx) (* k (abs dz))) (+ 0 (* d dz) (* k (abs dx)))])))
        away (:dir (:pearls/heading (pmove/reach-heading-hold w)))
        seen (assoc w :pearls/seen (corridor away))
        turned (:dir (:pearls/heading (pmove/reach-heading-hold seen)))]
    (is (= [1 0] away) "nothing seen: away from the spawner at x 2")
    (is (= [0 0] [(pmove/unseen-ahead seen away) 0]) "the corridor east is all seen")
    (is (not= away turned) "the heading goes where it has not looked")
    (is (pos? (pmove/unseen-ahead seen turned)))
    (let [h (:pearls/heading (pmove/reach-heading-hold seen))
          blocked (assoc seen :pearls/heading h)
          blocked (assoc blocked :plan/blacklist #{(:target h)} :pearls/seen (corridor [0 1]))
          sideways (:dir (:pearls/heading (pmove/reach-heading-hold blocked)))]
      (is (contains? #{[0 1] [0 -1]} sideways) "a blocked leg turns a quarter")
      (is (= [0 -1] sideways) "to the side with the more unseen ground"))))
