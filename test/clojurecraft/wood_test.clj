(ns clojurecraft.wood-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.game :as game]
            [clojurecraft.wood :as wood]
            [clojurecraft.world :as world]))

(def step (game/compose game/step wood/step))

(defn world-state
  "In play, loaded, standing at pos, with a 3-log trunk at [tx 64..66 0] under a leaf block."
  ([pos] (world-state pos 3))
  ([pos tx]
   (-> (game/init {:host "h" :port 1 :name "Clj_test"})
       (assoc :phase :play)
       (update :player assoc :pos pos :loaded? true :on-ground? true)
       (assoc :chunks {[0 0] (world/column {[tx 64 0] 136 [tx 65 0] 136 [tx 66 0] 136 [tx 67 0] 252})}))))

(defn run [state events]
  (reduce (fn [[s fx] e] (let [r (step s e)] [(:state r) (into fx (map (fn [f] [(:now (:state r)) f]) (:effects r)))]))
          [state []] events))

(defn sent [fx] (for [[t [k p]] fx :when (= k :send)] [t p]))
(defn ticks [from to] (for [t (range from to 50)] [:tick t]))

(deftest finds-the-bottom-of-the-trunk
  (let [s (world-state [0.5 64.0 0.5])]
    (is (= [3 64 0] (wood/find-log s #{})))
    (is (= 4 (wood/face-toward (game/eye s) [3 64 0])) "eye is west of the block, so its west face")
    (is (nil? (wood/find-log (assoc-in s [:blocks [3 64 0]] 0) #{[3 65 0]})) "overlay and blacklist both exclude")))

(deftest face-is-the-side-nearest-the-eye
  (is (= 4 (wood/face-toward [0.5 65.62 0.5] [3 64 0])) "eye west of the block → west face")
  (is (= 1 (wood/face-toward [3.5 70.0 0.5] [3 64 0])) "eye above → top")
  (is (= 2 (wood/face-toward [3.5 65.0 -5.0] [3 64 0])) "eye north → north face"))

(deftest dig-timeline-then-pickup
  (let [s0 (world-state [0.5 64.0 0.5])
        [s fx] (run s0 (cons [:go] (ticks 50 8000)))
        actions (filter #(= :player-action (:name (second %))) (sent fx))
        [[t-start start] [t-finish finish]] actions
        swings (filter #(= :swing (:name (second %))) (sent fx))]
    (is (= :collect (get-in s [:task :phase])))
    (is (= [3 64 0] (get-in s [:task :target])))
    (testing "START after settling, FINISH late enough never to abort the break"
      (is (= 0 (:status start)))
      (is (= 2 (:status finish)))
      (is (= [3 64 0] (:pos start)))
      (is (<= 500 t-start 2000))
      (is (>= (- t-finish t-start) (+ (* 3000 1.35) 200)))
      (is (= (:sequence finish) (inc (:sequence start)))))
    (testing "swings every 350 ms while digging"
      (is (<= 11 (count swings) 14)))
    (testing "the broken block is air locally"
      (is (= 0 (game/block-at s [3 64 0]))))
    (testing "done once the inventory shows a log"
      (let [[s2 _] (run s [[:packet {:name :set-player-inventory :slot 0 :item {:item 134 :count 1}}] [:tick 8050]])]
        (is (wood/done? s2))
        (is (= {} (:controls s2)))))))

(deftest walks-to-a-far-log
  (let [s0 (world-state [0.5 64.0 0.5] 13)
        [s fx] (run s0 (cons [:go] (ticks 50 6000)))]
    (is (contains? #{:settle :dig :collect} (get-in s [:task :phase])))
    (is (<= (clojurecraft.physics/distance (game/eye s) [13.5 64.5 0.5]) 4.0))
    (is (some #(= :move-player-pos-rot (:name (second %))) (sent fx)))))

(deftest gives-up-without-logs
  (let [s0 (assoc (world-state [0.5 64.0 0.5]) :chunks {[0 0] (world/column {})})
        [s _] (run s0 (cons [:go] (ticks 50 25000)))]
    (is (wood/failed? s))
    (is (= :no-log (get-in s [:task :reason])))))
