(ns clojurecraft.physics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.physics :as ph]))

(defn floor-at
  "Solid everywhere at or below y=63, plus extra solid blocks."
  [& extra]
  (let [extra (set extra)]
    (fn [x y z] (or (<= y 63) (contains? extra [x y z])))))

(def standing {:pos [0.5 64.0 0.5] :vel [0.0 0.0 0.0] :on-ground? true :jump-ticks 0})

(defn run [solid? player controls n]
  (nth (iterate #(ph/step solid? % controls) player) n))

(deftest falling-lands-on-the-floor
  (let [p (run (floor-at) {:pos [0.5 70.0 0.5] :vel [0.0 0.0 0.0] :on-ground? false :jump-ticks 0} {} 60)]
    (is (= 64.0 (nth (:pos p) 1)))
    (is (:on-ground? p))
    (is (< -0.08 (nth (:vel p) 1) 0.0) "vanilla keeps a resting vy of -0.0784")))

(deftest walking-reaches-vanilla-speed
  (let [p (run (floor-at) standing {:forward? true :yaw 0.0} 60)
        [x _ z] (:pos p)]
    (is (= 0.5 x))
    (is (< 10.0 (- z 0.5) 13.0) "about 0.216 blocks/tick after warm-up")
    (testing "yaw 90 walks west (-x)"
      (let [[x _ _] (:pos (run (floor-at) standing {:forward? true :yaw 90.0} 20))]
        (is (< x -1.0))))))

(deftest walls-and-jumps
  (let [wall (floor-at [0 64 2] [0 65 2])
        p (run wall standing {:forward? true :yaw 0.0} 40)]
    (is (:horizontal-collision? p))
    (is (< (nth (:pos p) 2) 1.71))
    (testing "a one-block ledge is cleared with a jump"
      (let [ledge (floor-at [0 64 2] [0 64 3] [0 64 4])
            states (take 40 (iterate #(ph/step ledge % {:forward? true :jump? true :yaw 0.0}) standing))]
        (is (some #(and (:on-ground? %) (= 65.0 (nth (:pos %) 1)) (> (nth (:pos %) 2) 2.0)) states))))))

(deftest jump-arc
  (let [ys (map #(nth (:pos %) 1) (take 14 (iterate #(ph/step (floor-at) % {:jump? true}) standing)))]
    (is (< 1.2 (- (apply max ys) 64.0) 1.3) "vanilla jump peaks about 1.25 blocks up")))

(deftest look-at-conventions
  (is (= [-90.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [10 0 0]))) "east is yaw -90")
  (is (= [0.0 0.0] (mapv #(Math/rint %) (ph/look-at [0 0 0] [0 0 10]))) "south is yaw 0")
  (is (= [0.0 45.0] (mapv #(Math/rint %) (ph/look-at [0 10 0] [0 0 10]))) "down is positive pitch"))
