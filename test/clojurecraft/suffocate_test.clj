(ns clojurecraft.suffocate-test
  "Reaction and prevention under falling sand and gravel: r08 and r15 logged states,
   stepping out of a falling column before digging it, waiting out a block already in the air,
   and escaping suffocation by stepping to a free neighbour or digging the head cell."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.suffocate :as suffocate]
            [clojurecraft.trap :as trap]))

(use-fixtures :once fx/instrumented)

(def gravel-state (blocks/default-state :gravel))
(def sand-state (blocks/default-state :sand))
(def stone-state (blocks/default-state :stone))
(def air-state (blocks/default-state :air))

(defn world-at
  "A minimal test world with player at pos and given blocks in :world/blocks."
  [pos blocks]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos pos
         :player/loaded? true
         :player/on-ground? true
         :world/blocks blocks
         :time/now 1000
         :time/tick 20))

(defn run-plan
  "One intent tick. A failed intent is counted by plan/fail-intent, so a third :unsafe or a
   twelfth :no-path sets :plan/status :failed. A tick that stays active does not."
  [world intent]
  (let [world (intent/run world intent {:event/kind :tick})
        i (:plan/intent world)]
    (if (intent/failed? i) (plan/fail-intent world i) world)))

(deftest r08-prevention-refuses-unsafe-dig-and-move
  (testing "r08: gravel column at [2435 58..61 2321]"
    (let [gravel-col (into {} (for [y (range 58 62)] [[2435 y 2321] gravel-state]))
          floor {[2434 57 2321] stone-state
                 [2434 58 2321] air-state
                 [2434 59 2321] air-state
                 [2435 57 2321] stone-state}
          all-blocks (merge gravel-col floor)
          w-beside (world-at [2434.56 58.0 2321.51] all-blocks)]
      ;; Beside the column: body does not overlap column 2435
      (is (false? (physics/overlaps-column? (:player/pos w-beside) [2435 2321])))
      (is (false? (intent/unsafe-falling? w-beside [2435 58 2321])))
      ;; Dig [2435 58 2321]: gravel falls, clearing 58 while 59..61 remain falling gravel
      (let [w-dug (assoc-in w-beside [:world/blocks [2435 58 2321]] air-state)
            move-pos [2434.98 58.0 2321.51]]
        ;; A stack that is still sitting is not a wall. Walking under it does not drop it.
        (is (true? (intent/falling-column? w-dug [2435 58 2321])))
        (is (false? (intent/step-into-falling? w-dug move-pos)))
        (is (true? (:control/forward? (intent/toward w-dug move-pos 0.0))))
        ;; A block already in the air is. The walk waits and does not fail the plan.
        (let [falling {1 {:entity/type 51 :entity/pos [2435.5 60.0 2321.5]}}
              w-fall (assoc w-dug :world/entities falling)
              walk-i {:intent/kind :walk :intent/target [2435 58 2321] :intent/status :active}
              w-walk (intent/run
                      (assoc w-fall :plan/intent walk-i :plan/status :active :plan/attempts 2)
                      walk-i
                      {:event/kind :tick})]
          (is (true? (intent/step-into-falling? w-fall move-pos)))
          (is (false? (:control/forward? (intent/toward w-fall move-pos 0.0))))
          (is (not= :failed (get-in w-walk [:plan/intent :intent/status])))
          (is (empty? (:player/controls w-walk)))
          (is (= 2 (:plan/attempts w-walk))))
        ;; a stack that is still sitting does not hold the step: the stair walks on
        (let [step-i {:intent/kind :stairs-down
                      :intent/stage :step
                      :intent/cells [[2435 58 2321]]
                      :intent/dir [1 0]
                      :intent/started-y 58.0
                      :intent/started-tick 20
                      :intent/status :active}
              w-step
              (stairs/stairs-stage (assoc w-dug :plan/intent step-i) step-i {:event/kind :tick})]
          (is (true? (:control/forward? (:player/controls w-step)))))
        ;; a block already in the air does: empty controls until the step's own timeout
        (let [step-i {:intent/kind :stairs-down
                      :intent/stage :step
                      :intent/cells [[2435 58 2321]]
                      :intent/dir [1 0]
                      :intent/started-y 58.0
                      :intent/started-tick 20
                      :intent/status :active}
              falling {1 {:entity/type 51 :entity/pos [2435.5 60.0 2321.5]}}
              w-air (stairs/stairs-stage (assoc w-dug :world/entities falling :plan/intent step-i)
                                         step-i
                                         {:event/kind :tick})
              w-late (stairs/stairs-stage
                      (assoc w-dug :world/entities falling :time/tick 221 :plan/intent step-i)
                      step-i
                      {:event/kind :tick})]
          (is (empty? (:player/controls w-air)))
          (is (= :failed (get-in w-late [:plan/intent :intent/status])))
          (is (= :stair-not-taken (get-in w-late [:plan/intent :intent/reason])))))
      ;; Overlapping the column: body extends into x 2435
      (let [w-overlap (world-at [2434.98 58.0 2321.51] all-blocks)]
        (is (true? (physics/overlaps-column? (:player/pos w-overlap) [2435 2321])))
        (is (true? (intent/falling-column? w-overlap [2435 57 2321])))
        (is (true? (intent/unsafe-falling? w-overlap [2435 57 2321])))
        ;; Digging under the column while overlapping steps out to [2434 58 2321]. One more
        ;; :unsafe would fail the plan; the dig stays active.
        (is (= [2434 58 2321] (intent/column-stand w-overlap [2435 57 2321])))
        (let [dig-i {:intent/kind :dig :intent/target [2435 57 2321] :intent/status :active}
              w0 (assoc w-overlap :plan/status :active :plan/attempts 2 :plan/intent dig-i)
              w-dig (run-plan w0 dig-i)]
          (is (not= :failed (:plan/status w-dig)))
          (is (= 2 (:plan/attempts w-dig)))
          (is (= :active (get-in w-dig [:plan/intent :intent/status])))
          (is (true? (:control/forward? (:player/controls w-dig)))))))))

(deftest r08-reaction-steps-to-free-neighbour
  (testing "r08: bot buried under gravel with free neighbour [2434 58 2321]"
    (let [blocks {[2435 59 2321] gravel-state
                  [2435 58 2321] gravel-state
                  [2434 58 2321] air-state
                  [2434 59 2321] air-state
                  [2434 57 2321] stone-state
                  ;; Other horizontal neighbours blocked
                  [2436 58 2321] stone-state
                  [2435 58 2322] stone-state
                  [2435 58 2320] stone-state}
          solid-fn (fn [x y z] (when-let [b (blocks [x y z])] (if (blocks/solid? b) 1.0 false)))
          w (assoc (world-at [2435.16 58.0 2321.51] blocks) :player/suffocating? true)]
      ;; The logged burial is x 2434.98: the eye point is still in cell 2434 (air) and the
      ;; eye box reaches the gravel at [2435 59 2321].
      (is (true? (physics/suffocating? solid-fn [2434.98 58.0 2321.51])))
      (is (false? (physics/suffocating? solid-fn [2434.56 58.0 2321.51])))
      (let [w-edge (assoc w :player/pos [2434.98 58.0 2321.51])
            esc-i {:intent/kind :escape-suffocation :intent/status :active}
            w-edge-run (intent/run (assoc (trap/trapped-tick w-edge) :plan/intent esc-i)
                                   esc-i
                                   {:event/kind :tick})]
        (is (empty? (suffocate/free-neighbours w-edge (:player/pos w-edge))))
        (is (= :dig (get-in w-edge-run [:plan/intent :intent/child :intent/kind])))
        (is (= [2435 59 2321] (get-in w-edge-run [:plan/intent :intent/child :intent/target]))))
      ;; Fully inside the column the eye point itself is the gravel
      (is (true? (physics/suffocating? solid-fn (:player/pos w))))
      (is (true? (trap/suffocating? w)))
      ;; trapped-tick sets :suffocation
      (let [w-trapped (trap/trapped-tick w)]
        (is (= :suffocation (:plan/trap w-trapped)))
        ;; The planner chooses :escape-suffocation at priority 0
        (let [goal (plan/choose w-trapped plan/goals)]
          (is (= :escape-suffocation (:goal/id goal)))
          (is (= {:intent/kind :escape-suffocation} (plan/next-intent w-trapped goal plan/goals))))
        ;; Free neighbour is found at [2434 58 2321]
        (let [neighbours (suffocate/free-neighbours w-trapped (:player/pos w))]
          (is (= [[2434 58 2321]] neighbours)))
        ;; escape-suffocation intent steers towards the free neighbour
        (let [esc-i {:intent/kind :escape-suffocation :intent/status :active}
              w-run (intent/run (assoc w-trapped :plan/intent esc-i) esc-i {:event/kind :tick})]
          (is (true? (get-in w-run [:player/controls :control/forward?])))
          ;; Once moved out to [2434.5 58.0 2321.51], head is clear and intent finishes :done
          (let [w-freed (assoc w-run :player/pos [2434.5 58.0 2321.51] :player/suffocating? false)
                w-done (intent/run w-freed (:plan/intent w-freed) {:event/kind :tick})]
            (is (false? (trap/suffocating? w-freed)))
            (is (= :done (get-in w-done [:plan/intent :intent/status])))))))))

(deftest r15-prevention-and-reaction-in-sand-column
  (testing "r15: sand column at [2588 57..60 2824], bot inside column"
    (let [sand-col (into {} (for [y (range 57 61)] [[2588 y 2824] sand-state]))
          walls (into
                 {}
                 (for [dx [-1 1] dz [-1 1] y [57 58 59]] [[(+ 2588 dx) y (+ 2824 dz)] stone-state]))
          blocks (merge sand-col walls)
          solid-fn (fn [x y z] (when-let [b (blocks [x y z])] (if (blocks/solid? b) 1.0 false)))
          w (assoc (world-at [2588.94 58.0 2824.60] blocks) :player/suffocating? true)]
      ;; Body overlaps column 2588 2824
      (is (true? (physics/overlaps-column? (:player/pos w) [2588 2824])))
      (is (true? (intent/unsafe-falling? w [2588 57 2824])))
      ;; Digging [2588 57 2824] under the bot is refused as :unsafe
      (let [dig-i {:intent/kind :dig :intent/target [2588 57 2824] :intent/status :active}
            w-dug (intent/run (assoc w :plan/intent dig-i) dig-i {:event/kind :tick})]
        (is (= :failed (get-in w-dug [:plan/intent :intent/status])))
        (is (= :unsafe (get-in w-dug [:plan/intent :intent/reason]))))
      ;; Sand at head: suffocating
      (is (true? (physics/suffocating? solid-fn (:player/pos w))))
      (is (true? (trap/suffocating? w)))
      ;; Digging below feet while buried is refused as :unsafe
      (let [dig-down {:intent/kind :dig :intent/target [2588 56 2824] :intent/status :active}
            w-dug-down (intent/run (assoc w :plan/intent dig-down) dig-down {:event/kind :tick})]
        (is (= :failed (get-in w-dug-down [:plan/intent :intent/status])))
        (is (= :unsafe (get-in w-dug-down [:plan/intent :intent/reason]))))
      ;; With no free neighbours, escape-suffocation starts child dig on the head cell
      (let [w-trapped (trap/trapped-tick w)
            neighbours (suffocate/free-neighbours w-trapped (:player/pos w))]
        (is (empty? neighbours))
        (let [esc-i {:intent/kind :escape-suffocation :intent/status :active}
              w-run (intent/run (assoc w-trapped :plan/intent esc-i) esc-i {:event/kind :tick})]
          (is (= :dig (get-in w-run [:plan/intent :intent/child :intent/kind])))
          (is (= [2588 59 2824] (get-in w-run [:plan/intent :intent/child :intent/target]))))))))

(deftest toward-approaches-a-far-column
  (testing "a sitting column is walked under; a block in the air fourteen blocks away is approached"
    (let [gravel-col (into {} (for [y (range 58 62)] [[2435 y 2321] gravel-state]))
          shore [2435.5 58.5 2321.5]
          w-far (world-at [2420.5 58.0 2321.5] gravel-col)
          falling {1 {:entity/type 51 :entity/pos [2435.5 60.0 2321.5]}}
          w-ent (assoc w-far :world/entities falling)]
      (is (false? (intent/step-into-falling? w-far shore)))
      (is (true? (:control/forward? (intent/toward w-far shore 0.0))))
      (is (true? (intent/step-into-falling? w-ent shore)))
      (is (true? (:control/forward? (intent/toward w-ent shore 0.0))))
      (let [w-near (assoc (world-at [2434.56 58.0 2321.51] gravel-col) :world/entities falling)
            move-pos [2434.98 58.0 2321.51]]
        (is (true? (intent/step-into-falling? w-near move-pos)))
        (is (false? (:control/forward? (intent/toward w-near move-pos 0.0))))))))

(deftest r3-walk-under-gravel-does-not-fail-no-path
  (testing "probe 40641701980 R3/1 seed 1: a gravel ceiling is not twelve :no-path walks"
    (let [pos [2303.5 17.0 1833.2]
          wp [2303 17 1834]
          blocks {[2303 16 1834] stone-state
                  [2303 17 1834] air-state
                  [2303 18 1834] air-state
                  [2303 19 1834] gravel-state
                  [2303 20 1834] gravel-state
                  [2303 16 1833] stone-state
                  [2303 17 1833] air-state
                  [2303 18 1833] air-state}
          walk-i {:intent/kind :walk
                  :intent/target [2303 17 1839]
                  :intent/status :active
                  :intent/waypoints [wp]
                  :intent/at 0
                  :intent/route :found
                  :intent/replans 6
                  :intent/best-tick 20
                  :intent/started 20}
          w0 (assoc (world-at pos blocks)
                    :plan/status :active
                    :plan/attempts 0
                    :plan/unwalkable 11
                    :plan/intent walk-i
                    :time/tick 20)]
      (is (true? (intent/falling-column? w0 wp)))
      (is (false? (intent/step-into-falling? w0 (physics/centre wp))))
      (is (false? (intent/route-stale? w0 walk-i)))
      (let [w (run-plan w0 walk-i)]
        (is (not= :failed (:plan/status w)))
        (is (= 11 (:plan/unwalkable w)))
        (is (= :active (get-in w [:plan/intent :intent/status])))
        (is (true? (:control/forward? (:player/controls w)))))
      (let [falling {1 {:entity/type 51 :entity/pos [2303.5 19.0 1834.5]}}
            w-ent (assoc w0 :world/entities falling)
            w (run-plan w-ent walk-i)]
        (is (true? (intent/step-into-falling? w-ent (physics/centre wp))))
        (is (not= :failed (:plan/status w)))
        (is (= 11 (:plan/unwalkable w)))
        (is (empty? (:player/controls w)))
        (is (= :active (get-in w [:plan/intent :intent/status])))))))

(deftest r4-dig-beside-keeps-the-plan-alive
  (testing
    "probe 40641701942 snapshot [1985.456 115 2119.648]: overlapping a gravel column steps aside"
    (let [pos [1985.456 115.0 2119.648]
          target [1985 114 2119]
          blocks {[1985 114 2119] stone-state
                  [1985 115 2119] air-state
                  [1985 116 2119] air-state
                  [1985 117 2119] gravel-state
                  [1985 118 2119] gravel-state
                  [1985 119 2119] gravel-state
                  [1984 114 2119] stone-state
                  [1984 115 2119] air-state
                  [1984 116 2119] air-state}
          dig-i {:intent/kind :dig
                 :intent/target target
                 :intent/status :active
                 :intent/stage :settle
                 :intent/since 0}
          w0 (assoc (world-at pos blocks)
                    :plan/status :active
                    :plan/attempts 2
                    :plan/intent dig-i
                    :time/now 0
                    :time/tick 19200)]
      (is (true? (physics/overlaps-column? pos [1985 2119])))
      (is (true? (intent/unsafe-falling? w0 target)))
      (is (= [1984 115 2119] (intent/column-stand w0 target)))
      (let [w-end (loop [w w0
                         n 5]
                    (if (zero? n)
                      w
                      (recur (-> (run-plan w (:plan/intent w))
                                 (update :time/tick inc)
                                 (update :time/now + 1000))
                             (dec n))))]
        (is (not= :failed (:plan/status w-end)))
        (is (= 2 (:plan/attempts w-end)))
        (is (= :settle (get-in w-end [:plan/intent :intent/stage])))
        (is (true? (:control/forward? (:player/controls w-end))))
        (is (true? (physics/overlaps-column? (:player/pos w-end) [1985 2119])))))))
