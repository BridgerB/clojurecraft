(ns clojurecraft.game-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.game :as game]
            [clojurecraft.world :as world]))

(def opts {:host "h" :port 1 :name "Clj_test"})

(defn run
  "Fold events through step; returns [final-state all-effects]."
  [state events]
  (reduce (fn [[s fx] e] (let [r (game/step s e)] [(:state r) (into fx (:effects r))]))
          [state []] events))

(defn sent [effects] (mapv second (filter #(= :send (first %)) effects)))
(defn names [effects] (mapv :name (sent effects)))

(deftest login-to-play
  (let [[s fx] (run (game/init opts)
                    [[:start]
                     [:packet {:name :login-compression :threshold 256}]
                     [:packet {:name :login-finished}]
                     [:packet {:name :select-known-packs}]
                     [:packet {:name :keep-alive :id 5}]
                     [:packet {:name :finish-configuration}]
                     [:packet {:name :login :entity-id 7}]])]
    (is (= [:intention :hello :login-acknowledged :select-known-packs :keep-alive :finish-configuration :client-information]
           (names fx)))
    (is (= :play (:phase s)))
    (is (= 7 (:entity-id s)))
    (is (= [] (:packs (nth (sent fx) 3))))
    (is (= 5 (:id (nth (sent fx) 4))))
    (is (= 775 (:protocol-version (first (sent fx)))))))

(defn in-play [] (first (run (game/init opts) [[:start] [:packet {:name :login-finished}] [:packet {:name :finish-configuration}] [:packet {:name :login :entity-id 7}]])))

(deftest teleports
  (let [[s fx] (run (in-play) [[:packet {:name :player-position :teleport-id 3 :x 1.5 :y 64.0 :z 2.5 :dx 0.0 :dy 0.0 :dz 0.0 :yaw 90.0 :pitch 10.0 :flags 0}]])]
    (is (= [:accept-teleportation :move-player-pos-rot :player-loaded] (names fx)))
    (is (= 3 (:teleport-id (first (sent fx)))))
    (is (= {:name :move-player-pos-rot :x 1.5 :y 64.0 :z 2.5 :yaw 90.0 :pitch 10.0 :flags 0} (second (sent fx))))
    (is (get-in s [:player :loaded?]))
    (testing "relative flags add; player-loaded only once"
      (let [[s2 fx2] (run s [[:packet {:name :player-position :teleport-id 4 :x 1.0 :y -1.0 :z 0.0 :dx 0.0 :dy 0.0 :dz 0.0 :yaw 0.0 :pitch 0.0 :flags 7}]])]
        (is (= [2.5 63.0 2.5] (get-in s2 [:player :pos])))
        (is (= [0.0 0.0] (get-in s2 [:player :look])))
        (is (= [:accept-teleportation :move-player-pos-rot] (names fx2)))))))

(deftest keep-alive-and-friends
  (let [[_ fx] (run (in-play) [[:packet {:name :keep-alive :id 99}]
                               [:packet {:name :ping :id 4}]
                               [:packet {:name :chunk-batch-finished :batch-size 3}]
                               [:packet {:name :start-configuration}]])]
    (is (= [{:name :keep-alive :id 99} {:name :pong :id 4} {:name :chunk-batch-received :chunks-per-tick 20.0}
            {:name :configuration-acknowledged}]
           (sent fx))))
  (testing "start-configuration flips the phase back"
    (let [[s _] (run (in-play) [[:packet {:name :start-configuration}]])]
      (is (= :configuration (:phase s))))))

(deftest inventory-one-key-space
  (let [log {:item 134 :count 1}
        items (vec (concat (repeat 36 nil) [log] (repeat 9 nil)))
        [s _] (run (in-play) [[:packet {:name :container-set-content :window-id 0 :state-id 1 :items items :carried nil}]
                              [:packet {:name :set-player-inventory :slot 5 :item {:item 134 :count 2}}]
                              [:packet {:name :container-set-slot :window-id 0 :state-id 2 :slot 9 :item {:item 1 :count 3}}]
                              [:packet {:name :container-set-slot :window-id 3 :state-id 2 :slot 9 :item {:item 134 :count 64}}]])]
    (is (= {0 log 5 {:item 134 :count 2} 9 {:item 1 :count 3}} (:inventory s)))
    (is (= 3 (game/logs-held s)))
    (is (= 0 (game/container->player-slot 36)))
    (is (= 40 (game/container->player-slot 45)))
    (is (nil? (game/container->player-slot 2)))))

(deftest chunks-blocks-and-entities
  (let [col (world/column-bytes {[3 64 0] 136 [3 65 0] 136})
        [s _] (run (in-play) [[:packet {:name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col}]
                              [:packet {:name :block-update :pos [3 65 0] :state 0}]
                              [:packet {:name :section-blocks-update :section (bit-or (bit-shift-left 1 42) 8) :blocks [(bit-or (bit-shift-left 2 12) (bit-shift-left 1 8) 5)]}]
                              [:packet {:name :add-entity :entity-id 50 :uuid nil :type 71 :x 1.0 :y 64.0 :z 1.0}]
                              [:packet {:name :add-entity :entity-id 51 :uuid nil :type 5 :x 1.0 :y 64.0 :z 1.0}]
                              [:packet {:name :move-entity-pos :entity-id 50 :dx 4096 :dy 0 :dz -2048 :on-ground true}]])]
    (is (= 136 (game/block-at s [3 64 0])))
    (is (= 0 (game/block-at s [3 65 0])) "overlay wins")
    (is (= 1 (game/block-at s [0 63 0])) "stone floor")
    (is (= 0 (game/block-at s [0 64 0])))
    (is (nil? (game/block-at s [16 64 0])) "unloaded")
    (is (= 2 (game/block-at s [17 133 0])) "section update: chunk x=1 z=0 section y=8, local (1,5,0)")
    (is (= {50 {:type 71 :pos [2.0 64.0 0.5] :seen 0}} (:entities s)) "only items are tracked")
    (testing "physics runs once loaded, and sends status-only once a second when idle"
      (let [s (assoc-in s [:player :pos] [0.5 64.0 0.5])
            [s fx] (run (assoc-in s [:player :loaded?] true) (for [i (range 1 30)] [:tick (* 50 i)]))]
        (is (= [0.5 64.0 0.5] (get-in s [:player :pos])))
        (is (get-in s [:player :on-ground?]))
        (is (= [:move-player-pos-rot :move-player-status-only] (names fx)))))))

(deftest unknown-packets-are-counted
  (let [[s _] (run (in-play) [[:packet {:name :unknown :id 83}] [:packet {:name :unknown :id 83}]])]
    (is (= {[:play 83] 2} (get-in s [:stats :unknown])))))
