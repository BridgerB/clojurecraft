(ns clojurecraft.pearls.reach-test
  "The hunt's way to a forest when nothing else finds one: the held heading, what it turns toward,
   the stuck-pocket rule (net displacement over a window), and the fortress escape that ends in
   a forest (issue #263)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
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
        i (pmove/reach-heading held)]
    (is (> 104 pparams/nether-climb-ceiling) "above the ceiling")
    (is (some? (:pearls/heading held)) "a heading is held with no forest known")
    (is (= :tunnel-to (:intent/kind i)) "the heading's tunnel")
    (is (<= (second (:intent/target i)) pparams/heading-max-y) "down to y 90 and under")
    (is (= i (pmove/reach-heading (pmove/reach-heading-hold held)))
        "the same leg at the next act")))

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


(defn sitting
  "w with the bot at pos, watched by watch-stuck at every `every` ms from `from` to `to`."
  [w pos from to every]
  (reduce (fn [w ms] (pmove/watch-stuck (assoc w :time/now ms :player/pos pos)))
          w
          (range from (inc to) every)))

(deftest a-pocket-the-bot-nets-no-ground-in-is-stuck-whatever-its-movers-did
  ;; Logged states, sim R2/21 (probe meas-wf-R2-21, issue #263): two tunnels, the exit and the
  ;; heading, passed targets back and forth at x -97 to -100, z 456 to 459 for 47 minutes; R1/17:
  ;; tunnels and stairs about [925 98 -74] for the hour. Net displacement over five minutes under
  ;; twelve blocks is the one test.
  (doseq [pos [[-99.5 56.0 457.5] [925.5 98.0 -73.5]]]
    (let [window (:window-ms pparams/stuck)
          w (-> (ps/standing {} {})
                (assoc :player/dimension ps/nether
                       :pearls/heading {:dir [0 -1] :from [0 0 0] :target [0 56 -64] :turns 0}))
          before (sitting w pos 0 (- window 5000) 5000)
          stuck (sitting before pos window window 5000)
          region (psense/region-of (intent/feet stuck))]
      (is (not (avoided/noted? before :pocket-regions region)) "not yet: the window is not over")
      (is (contains? (avoided/all-of stuck :pocket-regions) region) "the pocket's region is noted")
      (is (contains? (avoided/mark-of stuck :pocket-regions region) [0 -1])
          "with the heading tried")
      (is (nil? (:pearls/heading stuck)) "the held heading is dropped")
      (is (empty? (:pearls/trail stuck)) "and the clock starts again")
      (is (psense/in-stuck-pocket? stuck))
      (let [again (pmove/reach-heading-hold stuck)]
        (is (some? (:pearls/heading again)) "a new heading is picked")
        (is (not= [0 -1] (:dir (:pearls/heading again))) "not the one that got nowhere")))))

(deftest a-bot-that-moves-on-is-not-stuck-and-a-flip-changes-nothing
  (let [w (-> (ps/standing {} {})
              (assoc :player/dimension ps/nether))
        window (:window-ms pparams/stuck)
        walking (reduce (fn [w ms]
                          (pmove/watch-stuck
                           (assoc w :time/now ms :player/pos [(+ 8.5 (* 0.01 ms)) 64.0 8.5])))
                        w
                        (range 0 (inc window) 5000))
        flipping (reduce (fn [w ms]
                           (pmove/watch-stuck
                            (assoc w
                                   :time/now ms
                                   :player/pos [8.5 64.0 8.5]
                                   :pearls/exit-target
                                   (if (even? (quot ms 350)) [8 64 -60] [7 64 -60]))))
                         w
                         (range 0 (inc window) 350))]
    (is (empty? (avoided/all-of walking :pocket-regions))
        "three blocks a second for the window: free")
    (is (seq (avoided/all-of flipping :pocket-regions)) "targets flipping, no movement: stuck")))

(deftest the-fortress-exit-is-over-in-a-stuck-pocket
  (let [w (-> (ps/standing {})
              (assoc :player/dimension ps/nether :player/pos [-163.5 38.0 221.5])
              (memory/observe [-176 70 219] blaze/spawner)
              (memory/observe [-164 40 221] (blocks/default-state :nether_bricks))
              (assoc-in [:player/inventory 1] (ps/item :iron_pickaxe 1)))
        pocket (avoided/mark w :pocket-regions (psense/region-of (intent/feet w)) #{[1 0]})]
    (is (fexit/fortress-escaping? w) "near the fortress, no pocket: escaping")
    (is (not (fexit/fortress-escaping? pocket)) "in a stuck pocket: the exit was tried")
    (is (nil? (fexit/fortress-tunnel-target pocket)))))

(deftest a-leg-over-an-open-air-cavern-is-not-taken
  ;; sim R2/21: the bot stood in an air cavern whose floor was a few placed blocks, and the heading
  ;; bridged over air at 26 blocks a minute. A leg end with no ground under it in the y band is not
  ;; a direction; an unloaded column is not held against it.
  (let [w (-> (ps/standing {} {})
              (assoc :player/dimension ps/nether :player/pos [8.5 64.0 8.5]))
        cavern (assoc-in w
                [:world/chunks [4 0]]
                (world/column (into {} (for [y (range 30 72)] [[8 y 8] ps/air]))))]
    (is (pmove/ground-under? w [8 64 8]) "stone under the feet")
    (is (pmove/ground-under? w [200 64 8]) "an unloaded column: nothing against it")
    (is (not (pmove/ground-under? cavern [72 70 8]))
        "a loaded column of air from y 70 down to the band's floor: no ground")))
