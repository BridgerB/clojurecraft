(ns clojurecraft.suffocate-test
  "Reaction and prevention under falling sand and gravel: r08 and r15 logged states,
   refusing unsafe digs and moves under falling columns, and escaping suffocation by stepping
   to a free neighbour or digging the head cell."
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
        ;; Movement toward x 2434.98 would enter column 2435 while blocks above can still fall
        (is (true? (intent/step-into-falling? w-dug move-pos)))
        ;; intent/toward suppresses forward push
        (let [ctrls (intent/toward w-dug move-pos 0.0)] (is (false? (:control/forward? ctrls))))
        ;; intent/run :walk refuses to step into the falling column
        (let [walk-i {:intent/kind :walk :intent/target [2435 58 2321] :intent/status :active}
              w-walk (intent/run (assoc w-dug :plan/intent walk-i) walk-i {:event/kind :tick})]
          (is (= :failed (get-in w-walk [:plan/intent :intent/status])))
          (is (= :unsafe (get-in w-walk [:plan/intent :intent/reason]))))
        ;; stairs stage :step waits with empty controls rather than walking onto the stair
        (let [step-i {:intent/kind :stairs-down
                      :intent/stage :step
                      :intent/cells [[2435 58 2321]]
                      :intent/dir [1 0]
                      :intent/started-y 58.0
                      :intent/started-tick 20
                      :intent/status :active}
              w-step
              (stairs/stairs-stage (assoc w-dug :plan/intent step-i) step-i {:event/kind :tick})]
          (is (empty? (:player/controls w-step)))))
      ;; Overlapping the column: body extends into x 2435
      (let [w-overlap (world-at [2434.98 58.0 2321.51] all-blocks)]
        (is (true? (physics/overlaps-column? (:player/pos w-overlap) [2435 2321])))
        (is (true? (intent/falling-column? w-overlap [2435 57 2321])))
        (is (true? (intent/unsafe-falling? w-overlap [2435 57 2321])))
        ;; Digging under the gravel column while overlapping is refused as :unsafe
        (let [dig-i {:intent/kind :dig :intent/target [2435 57 2321] :intent/status :active}
              w-dig (intent/run (assoc w-overlap :plan/intent dig-i) dig-i {:event/kind :tick})]
          (is (= :failed (get-in w-dig [:plan/intent :intent/status])))
          (is (= :unsafe (get-in w-dig [:plan/intent :intent/reason]))))))))

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
      ;; The physics engine reports suffocating (eye cell holds gravel)
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
