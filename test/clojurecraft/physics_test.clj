(ns clojurecraft.physics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.physics :as ph]))

(defn floor-at
  "Solid everywhere at or below y=63, plus extra solid blocks."
  [& extra]
  (let [extra (set extra)] (fn [x y z] (or (<= y 63) (contains? extra [x y z])))))

(def standing
  {:player/pos [0.5 64.0 0.5]
   :player/vel [0.0 0.0 0.0]
   :player/on-ground? true
   :player/jump-ticks 0})

(defn run [solid? world controls n] (nth (iterate #(ph/step solid? % controls) world) n))

(deftest falling-lands-on-the-floor
  (let [w
        (run (floor-at) (assoc standing :player/pos [0.5 70.0 0.5] :player/on-ground? false) {} 60)]
    (is (= 64.0 (nth (:player/pos w) 1)))
    (is (:player/on-ground? w))
    (is (< -0.08 (nth (:player/vel w) 1) 0.0) "vanilla keeps a resting vy of -0.0784")))

(deftest walking-reaches-vanilla-speed
  (let [w (run (floor-at) standing {:control/forward? true :control/yaw 0.0} 60)
        [x _ z] (:player/pos w)]
    (is (= 0.5 x))
    (is (< 10.0 (- z 0.5) 13.0) "about 0.216 blocks/tick after warm-up")
    (testing "yaw 90 walks west (-x)"
      (let [[x _ _] (:player/pos
                     (run (floor-at) standing {:control/forward? true :control/yaw 90.0} 20))]
        (is (< x -1.0))))))

(deftest walls-and-jumps
  (let [wall (floor-at [0 64 2] [0 65 2])
        w (run wall standing {:control/forward? true :control/yaw 0.0} 40)]
    (is (:player/horizontal-collision? w))
    (is (< (nth (:player/pos w) 2) 1.71))
    (testing "a one-block ledge is cleared with a jump"
      (let [ledge (floor-at [0 64 2] [0 64 3] [0 64 4])
            states
            (take 40
                  (iterate
                   #(ph/step ledge % {:control/forward? true :control/jump? true :control/yaw 0.0})
                   standing))]
        (is (some #(and (:player/on-ground? %)
                        (= 65.0 (nth (:player/pos %) 1))
                        (> (nth (:player/pos %) 2) 2.0))
                  states))))))

(deftest jump-arc
  (let [ys (map #(nth (:player/pos %) 1)
                (take 14 (iterate #(ph/step (floor-at) % {:control/jump? true}) standing)))]
    (is (< 1.2 (- (apply max ys) 64.0) 1.3) "vanilla jump peaks about 1.25 blocks up")))

(deftest look-at-conventions
  (is (= [-90.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [10 0 0]))) "east is yaw -90")
  (is (= [0.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [0 0 10]))) "south is yaw 0")
  (is (= [0.0 45.0] (mapv #(Math/rint %) (ph/look-at [0 10 0] [0 0 10]))) "down is positive pitch"))

;; ---------------------------------------------------------------- fluids

(def water {:fluid/kind :water :fluid/level 0})

(defn pond-at
  "A fluid oracle: water sources in every cell with y in ys (over x and z anywhere), none elsewhere."
  [ys]
  (let [ys (set ys)] (fn [_ y _] (when (contains? ys y) water))))

(defn swim
  [solid? fluid-at world controls n]
  (nth (iterate #(ph/step solid? fluid-at % controls) world) n))

(deftest a-body-in-water-sinks-to-a-slow-equilibrium
  ;; a 2-deep pond: water at y 64 and 65 over a floor at 63; no controls
  (let [w0 (assoc standing :player/pos [0.5 65.5 0.5] :player/on-ground? false)
        w (swim (floor-at) (pond-at [64 65]) w0 {} 40)
        vy (nth (:player/vel w) 1)]
    (is (:player/in-water? w))
    (is (< (nth (:player/pos w) 1) 65.5) "it sank")
    (is (< -0.006 vy -0.004) "vy settles at water's gravity, -0.005 a tick, not land's -0.08")))

(deftest swimming-up-holds-the-feet-just-under-the-surface-with-the-eye-in-air
  ;; the surface of a source sits 8/9 up its block: y 65 + 8/9 = 65.89; jumping lifts 0.04 a
  ;; tick against 0.005 of gravity and 0.8 inertia, so the feet rise until the body is mostly out
  (let [w0 (assoc standing :player/pos [0.5 64.0 0.5] :player/on-ground? false)
        states (take 120
                     (iterate #(ph/step (floor-at) (pond-at [64 65]) % {:control/jump? true}) w0))
        w (last states)
        y (nth (:player/pos w) 1)]
    (is (> y 64.5) "it rose")
    (is (not (:player/eye-in-water? w)) "the eye (y + 1.62) is above the surface")
    (is (some :player/in-water? states))))

(deftest pressing-into-a-bank-lifts-the-body-onto-it
  ;; water at y 64 (floor 63) ahead of a one-high bank at z 2 (solid at y 64): forward with jump
  ;; held, the impulse fires on the collision and the bot stands on the bank within 60 ticks
  (let [bank (floor-at [0 64 2] [0 64 3] [0 64 4] [1 64 2] [-1 64 2])
        fluid (fn [_ y z] (when (and (= y 64) (< z 2)) water))
        w0 (assoc standing :player/pos [0.5 64.0 0.5] :player/on-ground? false)
        states
        (take 60
              (iterate
               #(ph/step bank fluid % {:control/forward? true :control/jump? true :control/yaw 0.0})
               w0))]
    (is (some #(and (:player/on-ground? %)
                    (>= (nth (:player/pos %) 1) 65.0)
                    (> (nth (:player/pos %) 2) 1.7))
              states)
        "on the bank, a block up")))

(deftest a-fall-counts-its-blocks-and-water-forgives-it
  (let [w (swim (floor-at)
                ph/no-fluid
                (assoc standing :player/pos [0.5 68.0 0.5] :player/on-ground? false)
                {}
                60)]
    (is (:player/on-ground? w))
    (is (= 0.0 (:player/fall-distance w)) "landing resets it")
    (let [states (take 60
                       (iterate
                        #(ph/step (floor-at) ph/no-fluid % {})
                        (assoc standing :player/pos [0.5 68.0 0.5] :player/on-ground? false)))
          before (last (take-while #(not (:player/on-ground? %)) states))
          fallen (+ (:player/fall-distance before) (- (nth (:player/pos before) 1) 64.0))] ; what was counted, plus the last drop to the floor
      (is (< 3.99 fallen 4.01) "four blocks fallen onto the floor")))
  (let [w (swim (floor-at)
                (pond-at [64 65])
                (assoc standing
                       :player/pos [0.5 68.0 0.5]
                       :player/on-ground? false
                       :player/fall-distance 2.5)
                {}
                30)]
    (is (= 0.0 (:player/fall-distance w)) "water resets it")))

(deftest horizontal-speed-in-water-settles-at-a-tenth-of-a-block
  (let [fluid (pond-at [64 65 66])
        w0 (assoc standing :player/pos [0.5 64.0 0.5] :player/on-ground? false)
        a (swim (floor-at) fluid w0 {:control/forward? true :control/yaw 0.0} 60)
        b (ph/step (floor-at) fluid a {:control/forward? true :control/yaw 0.0})]
    (is (< 0.09 (- (nth (:player/pos b) 2) (nth (:player/pos a) 2)) 0.11)
        "0.02 / (1 - 0.8) = 0.1 blocks a tick")))
