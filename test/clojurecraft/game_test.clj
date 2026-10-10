(ns clojurecraft.game-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx :refer [fold names packets packet ticks]]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn run [world events] (fold game/step world events))

(deftest login-to-play
  (let [[w fx] (run (game/init fx/opts)
                    [{:event/kind :start}
                     (packet {:packet/name :login-compression :threshold 256})
                     (packet fx/login-finished)
                     (packet {:packet/name :select-known-packs})
                     (packet {:packet/name :keep-alive :id 5})
                     (packet {:packet/name :finish-configuration})
                     (packet {:packet/name :login :entity-id 7})])]
    (is (= [:intention :hello :login-acknowledged :select-known-packs :keep-alive :finish-configuration :client-information]
           (names fx)))
    (is (= :play (:bot/phase w)))
    (is (= 7 (:player/entity-id w)))
    (is (= [] (:packs (nth (packets fx) 3))))
    (is (= 5 (:id (nth (packets fx) 4))))
    (is (= 775 (:protocol-version (first (packets fx)))) "from version.edn, not a constant")
    (is (= 775 (:version/protocol game/version)))))

(defn in-play []
  (first (run (game/init fx/opts)
              [{:event/kind :start} (packet fx/login-finished)
               (packet {:packet/name :finish-configuration}) (packet {:packet/name :login :entity-id 7})])))

(deftest teleports
  (let [[w fx] (run (in-play) [(packet {:packet/name :player-position :teleport-id 3 :x 1.5 :y 64.0 :z 2.5
                                        :dx 0.0 :dy 0.0 :dz 0.0 :yaw 90.0 :pitch 10.0 :flags 0})])]
    (is (= [:accept-teleportation :move-player-pos-rot :player-loaded] (names fx)))
    (is (= 3 (:teleport-id (first (packets fx)))))
    (is (= {:packet/name :move-player-pos-rot :x 1.5 :y 64.0 :z 2.5 :yaw 90.0 :pitch 10.0 :flags 0} (second (packets fx))))
    (is (:player/loaded? w))
    (testing "relative flags add; player-loaded only once"
      (let [[w2 fx2] (run w [(packet {:packet/name :player-position :teleport-id 4 :x 1.0 :y -1.0 :z 0.0
                                      :dx 0.0 :dy 0.0 :dz 0.0 :yaw 0.0 :pitch 0.0 :flags 7})])]
        (is (= [2.5 63.0 2.5] (:player/pos w2)))
        (is (= [0.0 0.0] (:player/look w2)))
        (is (= [:accept-teleportation :move-player-pos-rot] (names fx2)))))))

(deftest keep-alive-and-friends
  (let [[_ fx] (run (in-play) [(packet {:packet/name :keep-alive :id 99})
                               (packet {:packet/name :ping :id 4})
                               (packet {:packet/name :chunk-batch-finished :batch-size 3})
                               (packet {:packet/name :start-configuration})])]
    (is (= [{:packet/name :keep-alive :id 99} {:packet/name :pong :id 4}
            {:packet/name :chunk-batch-received :chunks-per-tick 20.0} {:packet/name :configuration-acknowledged}]
           (packets fx))))
  (testing "start-configuration flips the phase back"
    (let [[w _] (run (in-play) [(packet {:packet/name :start-configuration})])]
      (is (= :configuration (:bot/phase w))))))

(deftest inventory-one-key-space
  (let [log {:item 134 :count 1}
        items (vec (concat (repeat 36 nil) [log] (repeat 9 nil)))
        [w _] (run (in-play) [(packet {:packet/name :container-set-content :window-id 0 :state-id 1 :items items :carried nil})
                              (packet {:packet/name :set-player-inventory :slot 5 :item {:item 134 :count 2}})
                              (packet {:packet/name :container-set-slot :window-id 0 :state-id 2 :slot 9 :item {:item 1 :count 3}})
                              (packet {:packet/name :container-set-slot :window-id 3 :state-id 2 :slot 9 :item {:item 134 :count 64}})])]
    (is (= {0 log 5 {:item 134 :count 2} 9 {:item 1 :count 3}} (:player/inventory w)))
    (is (= 2 (:window/state-id w)) "window 0's latest state id is kept")
    (is (= 3 (inventory/logs-held w)))
    (is (= 0 (inventory/container->player-slot 36)))
    (is (= 40 (inventory/container->player-slot 45)))
    (is (= 39 (inventory/container->player-slot 5)) "window 5 is the helmet, player slot 39")
    (is (= 36 (inventory/container->player-slot 8)) "window 8 is the boots, player slot 36")
    (is (nil? (inventory/container->player-slot 2)))))

(deftest the-crafting-grid-is-never-invisible
  (let [items (vec (concat [{:item 30 :count 1} {:item 36 :count 1} nil {:item 36 :count 1} nil] (repeat 41 nil)))
        [w _] (run (in-play) [(packet {:packet/name :container-set-content :window-id 0 :state-id 4 :items items
                                       :carried {:item 36 :count 6}})
                              (packet {:packet/name :container-set-slot :window-id 0 :state-id 5 :slot 3 :item {:item 36 :count 1}})
                              (packet {:packet/name :container-set-slot :window-id 0 :state-id 6 :slot 1 :item nil})])]
    (is (= {0 {:item 30 :count 1} 3 {:item 36 :count 1}} (:window/grid w)))
    (is (= {:item 36 :count 6} (:window/cursor w)))
    (is (= 6 (:window/state-id w)))
    (is (= {} (:player/inventory w)) "grid items are in the grid, not double-counted")
    (testing "cursor and open windows"
      (let [[w _] (run w [(packet {:packet/name :set-cursor-item :item nil})
                          (packet {:packet/name :open-screen :window-id 3 :menu-type 12 :title (byte-array 0)})])]
        (is (nil? (:window/cursor w)))
        (is (= {:window/id 3 :window/menu-type 12 :window/slots {}} (:window/open w)))
        (is (nil? (:window/open (first (run w [(packet {:packet/name :container-close :window-id 3})])))))))))

(deftest a-login-or-respawn-gives-fresh-menus
  (let [w (assoc (in-play) :window/open {:window/id 3 :window/menu-type 12 :window/slots {}}
                 :window/cursor {:item 36 :count 2} :window/grid {1 {:item 36 :count 1}})]
    (doseq [p [{:packet/name :respawn} {:packet/name :login :entity-id 9}]]
      (let [[w _] (run w [(packet p)])]
        (is (nil? (:window/open w)) (str (:packet/name p)))
        (is (nil? (:window/cursor w)))
        (is (= {} (:window/grid w)))))))

(deftest chunks-blocks-entities-and-memory
  (let [col (world/column-bytes {[3 64 0] 136 [3 65 0] 136})
        [w _] (run (in-play) [(packet {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col})
                              (packet {:packet/name :block-update :pos [3 65 0] :state 0})
                              (packet {:packet/name :section-blocks-update :section (bit-or (bit-shift-left 1 42) 8)
                                       :blocks [(bit-or (bit-shift-left 2 12) (bit-shift-left 1 8) 5)]})
                              (packet {:packet/name :add-entity :entity-id 50 :uuid nil :type 71 :x 1.0 :y 64.0 :z 1.0})
                              (packet {:packet/name :add-entity :entity-id 51 :uuid nil :type 5 :x 1.0 :y 64.0 :z 1.0})
                              (packet {:packet/name :move-entity-pos :entity-id 50 :dx 4096 :dy 0 :dz -2048 :on-ground true})])]
    (is (= 136 (game/block-at w [3 64 0])))
    (is (= 0 (game/block-at w [3 65 0])) "overlay wins")
    (is (= 1 (game/block-at w [0 63 0])) "stone floor")
    (is (nil? (game/block-at w [16 64 0])) "unloaded")
    (is (= 2 (game/block-at w [17 133 0])) "section update: chunk x=1 z=0 section y=8, local (1,5,0)")
    (is (= {50 {:entity/type 71 :entity/pos [2.0 64.0 0.5] :entity/seen-at 0}} (:world/entities w)) "only items are tracked")
    (testing "sightings remember logs and their later states"
      (is (= {[3 64 0] {:block/state 136 :block/seen-at 0} [3 65 0] {:block/state 0 :block/seen-at 0}}
             (memory/latest w)))
      (is (= [{:block/state 136 :block/seen-at 0} {:block/state 0 :block/seen-at 0}] (memory/history w [3 65 0]))
          "facts are appended, never overwritten: the log, then the air that replaced it")
      (let [[w2 _] (run w [(packet {:packet/name :forget-level-chunk :pos 0})])]
        (is (nil? (game/block-at w2 [3 64 0])) "chunk gone")
        (is (= 136 (memory/remembered w2 [3 64 0])) "memory stays")))
    (testing "a corrupt chunk is logged, not thrown"
      (let [[w2 fx] (run w [(packet {:packet/name :level-chunk-with-light :x 1 :z 1 :heightmaps [] :data (byte-array 3)})])]
        (is (= 1 (count (:world/chunks w2))))
        (is (= :log (:effect/kind (second (first fx)))))))
    (testing "physics runs once loaded; status-only once a second when idle"
      (let [w (assoc w :player/pos [0.5 64.0 0.5] :player/loaded? true)
            [w fx] (run w (ticks 50 1500))]
        (is (= [0.5 64.0 0.5] (:player/pos w)))
        (is (:player/on-ground? w))
        (is (= [:move-player-pos-rot :move-player-status-only] (names fx)))))))

(deftest unknown-packets-are-counted
  (let [[w _] (run (in-play) [(packet {:packet/name :unknown :packet/id 83}) (packet {:packet/name :unknown :packet/id 83})])]
    (is (= {[:play 83] 2} (:stats/unknown w)))))
