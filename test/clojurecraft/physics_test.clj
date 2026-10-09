(ns clojurecraft.physics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.physics :as ph]))

(defn floor-at
  "Solid everywhere at or below y=63, plus extra solid blocks."
  [& extra]
  (let [extra (set extra)]
    (fn [x y z] (or (<= y 63) (contains? extra [x y z])))))

(def standing {:player/pos [0.5 64.0 0.5] :player/vel [0.0 0.0 0.0] :player/on-ground? true :player/jump-ticks 0})

(defn run [solid? world controls n]
  (nth (iterate #(ph/step solid? % controls) world) n))

(deftest falling-lands-on-the-floor
  (let [w (run (floor-at) (assoc standing :player/pos [0.5 70.0 0.5] :player/on-ground? false) {} 60)]
    (is (= 64.0 (nth (:player/pos w) 1)))
    (is (:player/on-ground? w))
    (is (< -0.08 (nth (:player/vel w) 1) 0.0) "vanilla keeps a resting vy of -0.0784")))

(deftest walking-reaches-vanilla-speed
  (let [w (run (floor-at) standing {:control/forward? true :control/yaw 0.0} 60)
        [x _ z] (:player/pos w)]
    (is (= 0.5 x))
    (is (< 10.0 (- z 0.5) 13.0) "about 0.216 blocks/tick after warm-up")
    (testing "yaw 90 walks west (-x)"
      (let [[x _ _] (:player/pos (run (floor-at) standing {:control/forward? true :control/yaw 90.0} 20))]
        (is (< x -1.0))))))

(deftest walls-and-jumps
  (let [wall (floor-at [0 64 2] [0 65 2])
        w (run wall standing {:control/forward? true :control/yaw 0.0} 40)]
    (is (:player/horizontal-collision? w))
    (is (< (nth (:player/pos w) 2) 1.71))
    (testing "a one-block ledge is cleared with a jump"
      (let [ledge (floor-at [0 64 2] [0 64 3] [0 64 4])
            states (take 40 (iterate #(ph/step ledge % {:control/forward? true :control/jump? true :control/yaw 0.0}) standing))]
        (is (some #(and (:player/on-ground? %) (= 65.0 (nth (:player/pos %) 1)) (> (nth (:player/pos %) 2) 2.0)) states))))))

(deftest jump-arc
  (let [ys (map #(nth (:player/pos %) 1) (take 14 (iterate #(ph/step (floor-at) % {:control/jump? true}) standing)))]
    (is (< 1.2 (- (apply max ys) 64.0) 1.3) "vanilla jump peaks about 1.25 blocks up")))

(deftest look-at-conventions
  (is (= [-90.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [10 0 0]))) "east is yaw -90")
  (is (= [0.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [0 0 10]))) "south is yaw 0")
  (is (= [0.0 45.0] (mapv #(Math/rint %) (ph/look-at [0 10 0] [0 0 10]))) "down is positive pitch"))
