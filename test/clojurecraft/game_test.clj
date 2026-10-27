(ns clojurecraft.game-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx :refer [fold names packet packets ticks]]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.sim :as sim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn run [world events] (fold game/step world events))

(deftest login-to-play
  (let [[w fx] (run (game/init fx/opts)
                    [{:event/kind :start} (packet {:packet/name :login-compression :threshold 256})
                     (packet fx/login-finished) (packet {:packet/name :select-known-packs})
                     (packet {:packet/name :keep-alive :id 5})
                     (packet {:packet/name :finish-configuration})
                     (packet {:packet/name :login :entity-id 7})])]
    (is (= [:intention :hello :login-acknowledged :select-known-packs :keep-alive
            :finish-configuration :client-information]
           (names fx)))
    (is (= :play (:bot/phase w)))
    (is (= 7 (:player/entity-id w)))
    (is (= [] (:packs (nth (packets fx) 3))))
    (is (= 5 (:id (nth (packets fx) 4))))
    (is (= 775 (:protocol-version (first (packets fx)))) "from version.edn, not a constant")
    (is (= 775 (:version/protocol game/version)))))

(defn in-play
  []
  (first (run (game/init fx/opts)
              [{:event/kind :start} (packet fx/login-finished)
               (packet {:packet/name :finish-configuration})
               (packet {:packet/name :login :entity-id 7})])))

(deftest teleports
  (let [[w fx] (run (in-play)
                    [(packet {:packet/name :player-position
                              :teleport-id 3
                              :x 1.5
                              :y 64.0
                              :z 2.5
                              :dx 0.0
                              :dy 0.0
                              :dz 0.0
                              :yaw 90.0
                              :pitch 10.0
                              :flags 0})])]
    (is (= [:accept-teleportation :move-player-pos-rot :player-loaded] (names fx)))
    (is (= 3 (:teleport-id (first (packets fx)))))
    (is (= {:packet/name :move-player-pos-rot :x 1.5 :y 64.0 :z 2.5 :yaw 90.0 :pitch 10.0 :flags 0}
           (second (packets fx))))
    (is (:player/loaded? w))
    (testing "relative flags add; player-loaded only once"
      (let [[w2 fx2] (run w
                          [(packet {:packet/name :player-position
                                    :teleport-id 4
                                    :x 1.0
                                    :y -1.0
                                    :z 0.0
                                    :dx 0.0
                                    :dy 0.0
                                    :dz 0.0
                                    :yaw 0.0
                                    :pitch 0.0
                                    :flags 7})])]
        (is (= [2.5 63.0 2.5] (:player/pos w2)))
        (is (= [0.0 0.0] (:player/look w2)))
        (is (= [:accept-teleportation :move-player-pos-rot] (names fx2)))))))

(deftest keep-alive-and-friends
  (let [[_ fx] (run (in-play)
                    [(packet {:packet/name :keep-alive :id 99}) (packet {:packet/name :ping :id 4})
                     (packet {:packet/name :chunk-batch-finished :batch-size 3})
                     (packet {:packet/name :start-configuration})])]
    (is (= [{:packet/name :keep-alive :id 99} {:packet/name :pong :id 4}
            {:packet/name :chunk-batch-received :chunks-per-tick 20.0}
            {:packet/name :configuration-acknowledged}]
           (packets fx))))
  (testing "start-configuration flips the phase back"
    (let [[w _] (run (in-play) [(packet {:packet/name :start-configuration})])]
      (is (= :configuration (:bot/phase w))))))

(deftest inventory-one-key-space
  (let [log {:item (blocks/items :oak_log) :count 1}
        items (assoc (vec (repeat (inventory/slot-count (inventory/layouts :inventory)) nil))
                     (inventory/slot [:hotbar 0])
                     log)
        [w _]
        (run
         (in-play)
         [(packet
           {:packet/name :container-set-content :window-id 0 :state-id 1 :items items :carried nil})
          (packet {:packet/name :set-player-inventory
                   :slot 5
                   :item {:item (blocks/items :oak_log) :count 2}})
          (packet {:packet/name :container-set-slot
                   :window-id 0
                   :state-id 2
                   :slot (inventory/slot [:main 0])
                   :item {:item (blocks/items :stone) :count 3}})
          (packet {:packet/name :container-set-slot
                   :window-id 3
                   :state-id 2
                   :slot 9
                   :item {:item (blocks/items :oak_log) :count 64}})])]
    (is (=
         {0 log 5 {:item (blocks/items :oak_log) :count 2} 9 {:item (blocks/items :stone) :count 3}}
         (:player/inventory w)))
    (is (= 2 (:window/state-id w)) "window 0's latest state id is kept")
    (is (= 3 (inventory/logs-held w)))
    (is (= 0
           (inventory/window->player-slot (inventory/layouts :inventory)
                                          (inventory/slot [:hotbar 0]))))
    (is (= inventory/offhand-slot
           (inventory/window->player-slot (inventory/layouts :inventory)
                                          (inventory/slot [:offhand]))))
    (is (= inventory/head-slot
           (inventory/window->player-slot (inventory/layouts :inventory)
                                          (inventory/slot [:armour 0])))
        "window 5 is the helmet, player slot 39")
    (is (= 36 (inventory/window->player-slot (inventory/layouts :inventory) 8))
        "window 8 is the boots, player slot 36")
    (is (nil? (inventory/window->player-slot (inventory/layouts :inventory) 2)))))

(deftest window-layouts-map-player-slots-both-ways
  (doseq [[kind l] inventory/layouts
          p (range 36)]
    (is (= p (inventory/window->player-slot l (inventory/player->window-slot l p)))
        (str kind " " p)))
  (is (= 37 (inventory/player->window-slot (inventory/layouts :table) 0)) "the table's hotbar")
  (is (= 10 (inventory/player->window-slot (inventory/layouts :table) 9)) "the table's main")
  (is (= 45 (inventory/player->window-slot (inventory/layouts :inventory) 40)) "window 0's offhand")
  (is (nil? (inventory/player->window-slot (inventory/layouts :table) 40)) "no offhand in a table")
  (is (nil? (inventory/player->window-slot (inventory/layouts :inventory) 39)) "armour not offered")
  (is (nil? (inventory/window->player-slot (inventory/layouts :table) 9)) "the table's own grid")
  (is (= (inventory/layouts :table) (inventory/layout-of-menu 12)))
  (is (nil? (inventory/layout-of-menu 99))))

(deftest a-furnace-window-keeps-its-slots-its-data-and-the-players-items-apart
  (let [coal {:item (get blocks/items :coal) :count 4}
        ore {:item (get blocks/items :raw_iron) :count 3}
        log {:item (blocks/items :oak_log) :count 1}
        [w _] (run
               (in-play)
               [(packet {:packet/name :open-screen
                         :window-id 2
                         :menu-type (blocks/menus :furnace)
                         :title (byte-array 0)})
                (packet {:packet/name :container-set-content
                         :window-id 2
                         :state-id 1
                         :items (vec (concat [ore coal nil] (repeat 27 nil) [log] (repeat 8 nil)))
                         :carried nil})
                (packet {:packet/name :container-set-data :window-id 2 :property 2 :value 40})
                (packet {:packet/name :container-set-data :window-id 7 :property 2 :value 99})])]
    (is (= {0 ore 1 coal} (get-in w [:window/open :window/slots]))
        "input and fuel are the furnace's")
    (is (= {0 log} (:player/inventory w)) "window slot 30 is hotbar slot 0")
    (is (= {2 40} (get-in w [:window/open :window/data])) "a property of another window is ignored")
    (is (= (inventory/layouts :furnace) (inventory/layout-of-menu (blocks/menus :furnace))))))

(deftest the-crafting-grid-is-never-invisible
  (let [items (vec (concat [{:item (blocks/items :podzol) :count 1}
                            {:item (blocks/items :oak_planks) :count 1} nil
                            {:item (blocks/items :oak_planks) :count 1} nil]
                           (repeat 41 nil)))
        [w _] (run
               (in-play)
               [(packet {:packet/name :container-set-content
                         :window-id 0
                         :state-id 4
                         :items items
                         :carried {:item (blocks/items :oak_planks) :count 6}})
                (packet {:packet/name :container-set-slot
                         :window-id 0
                         :state-id 5
                         :slot 3
                         :item {:item (blocks/items :oak_planks) :count 1}})
                (packet
                 {:packet/name :container-set-slot :window-id 0 :state-id 6 :slot 1 :item nil})])]
    (is (= {0 {:item (blocks/items :podzol) :count 1} 3 {:item (blocks/items :oak_planks) :count 1}}
           (:window/grid w)))
    (is (= {:item (blocks/items :oak_planks) :count 6} (:window/cursor w)))
    (is (= 6 (:window/state-id w)))
    (is (= {} (:player/inventory w)) "grid items are in the grid, not double-counted")
    (testing "cursor and open windows"
      (let [[w _] (run w
                       [(packet {:packet/name :set-cursor-item :item nil})
                        (packet {:packet/name :open-screen
                                 :window-id 3
                                 :menu-type (blocks/menus :crafting)
                                 :title (byte-array 0)})])]
        (is (nil? (:window/cursor w)))
        (is (= {:window/id 3 :window/menu-type (blocks/menus :crafting) :window/slots {}}
               (:window/open w)))
        (is (nil? (:window/open
                   (first (run w [(packet {:packet/name :container-close :window-id 3})])))))))))

(deftest a-login-or-respawn-gives-fresh-menus
  (let [w (assoc (in-play)
                 :window/open
                 {:window/id 3 :window/menu-type (blocks/menus :crafting) :window/slots {}}
                 :window/cursor {:item (blocks/items :oak_planks) :count 2}
                 :window/grid {1 {:item (blocks/items :oak_planks) :count 1}})]
    (doseq [p [fx/respawn {:packet/name :login :entity-id 9}]]
      (let [[w _] (run w [(packet p)])]
        (is (nil? (:window/open w)) (str (:packet/name p)))
        (is (nil? (:window/cursor w)))
        (is (= {} (:window/grid w)))))))

(deftest a-login-says-the-dimension-the-bot-joins-in
  (let [[w _] (run (in-play) [(packet (sim/login "minecraft:the_nether"))])]
    (is (= "minecraft:the_nether" (:player/dimension w))))
  (testing "a login recorded before it carried one leaves the overworld"
    (let [[w _] (run (in-play)
                     [{:event/kind :packet :event/packet {:packet/name :login :entity-id 1}}])]
      (is (nil? (:player/dimension w))))))

(deftest a-nether-chunk-loads-at-the-nether-height
  ;; the server writes its dimension's sections; a Nether column is 16 from y 0, not 24 from -64
  (let [col (world/column-bytes {[3 70 3] (blocks/default-state :nether_bricks)}
                                "minecraft:the_nether")
        [w _] (run (in-play)
                   [(packet (assoc fx/respawn :dimension "minecraft:the_nether"))
                    (packet
                     {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col})])]
    (is (= :nether_bricks (blocks/name-of (terrain/block-at w [3 70 3]))))
    (is (= [3 70 3] (first (memory/positions-now w [(blocks/default-state :nether_bricks)])))
        "remembered, in the Nether")))

(deftest a-respawn-into-another-dimension-forgets-the-old-one
  ;; the dimension change arrives as a respawn (#8: the portal; #11 owns the Nether itself): the
  ;; columns, block overlay and entities were the old dimension's, and the bot is loaded again
  ;; only when the server has placed it
  (let [col (world/column-bytes {})
        [w _] (run (in-play)
                   [(packet
                     {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col})
                    (packet (assoc fx/respawn :dimension "minecraft:the_nether"))])]
    (is (= "minecraft:the_nether" (:player/dimension w)))
    (is (= {} (:world/chunks w)))
    (is (false? (:player/loaded? w)))
    (let [[w2 _] (run w [(packet fx/respawn)])]
      (is (= "minecraft:overworld" (:player/dimension w2)) "and back"))
    (let [[w3 _] (run (in-play)
                      [(packet
                        {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col})
                       (packet fx/respawn)])]
      (is (seq (:world/chunks w3)) "a respawn in the same dimension (a death) keeps the terrain"))))

(deftest chunks-blocks-entities-and-memory
  (let [col (world/column-bytes {[3 64 0] 136 [3 65 0] 136})
        [w _]
        (run
         (in-play)
         [(packet {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data col})
          (packet {:packet/name :block-update :pos [3 65 0] :state 0})
          (packet {:packet/name :section-blocks-update
                   :section (bit-or (bit-shift-left 1 42) 8)
                   :blocks [(bit-or (bit-shift-left 2 12) (bit-shift-left 1 8) 5)]})
          (packet {:packet/name :add-entity :entity-id 50 :uuid nil :type 71 :x 1.0 :y 64.0 :z 1.0})
          (packet {:packet/name :add-entity :entity-id 51 :uuid nil :type 5 :x 1.0 :y 64.0 :z 1.0})
          (packet {:packet/name :move-entity-pos
                   :entity-id 50
                   :dx 4096
                   :dy 0
                   :dz -2048
                   :on-ground true})])]
    (is (= 136 (terrain/block-at w [3 64 0])))
    (is (= 0 (terrain/block-at w [3 65 0])) "overlay wins")
    (is (= 1 (terrain/block-at w [0 63 0])) "stone floor")
    (is (nil? (terrain/block-at w [16 64 0])) "unloaded")
    (is (= 2 (terrain/block-at w [17 133 0]))
        "section update: chunk x=1 z=0 section y=8, local (1,5,0)")
    (is (= {50 {:entity/type 71 :entity/pos [2.0 64.0 0.5] :entity/seen-at 0}
            51 {:entity/type 5 :entity/pos [1.0 64.0 1.0] :entity/seen-at 0}}
           (:world/entities w))
        "every entity is tracked, not only items (issue #258)")
    (testing "sightings remember logs and their later states"
      (is (= {[3 64 0] {:block/state 136 :block/seen-at 0}
              [3 65 0] {:block/state 0 :block/seen-at 0}}
             (memory/latest w)))
      (is (= [{:block/state 136 :block/seen-at 0} {:block/state 0 :block/seen-at 0}]
             (memory/history w [3 65 0]))
          "facts are appended, never overwritten: the log, then the air that replaced it")
      (let [[w2 _] (run w [(packet {:packet/name :forget-level-chunk :pos 0})])]
        (is (nil? (terrain/block-at w2 [3 64 0])) "chunk gone")
        (is (= 136 (memory/remembered w2 [3 64 0])) "memory stays")))
    (testing "a corrupt chunk is logged, not thrown"
      (let [[w2 fx] (run w
                         [(packet {:packet/name :level-chunk-with-light
                                   :x 1
                                   :z 1
                                   :heightmaps []
                                   :data (byte-array 3)})])]
        (is (= 1 (count (:world/chunks w2))))
        (is (= :log (:effect/kind (second (first fx)))))))
    (testing "physics runs once loaded; status-only once a second when idle"
      (let [w (assoc w :player/pos [0.5 64.0 0.5] :player/loaded? true)
            [w fx] (run w (ticks 50 1500))]
        (is (= [0.5 64.0 0.5] (:player/pos w)))
        (is (:player/on-ground? w))
        (is (= [:move-player-pos-rot :move-player-status-only] (names fx)))))))

(deftest unknown-packets-are-counted
  (let [[w _] (run (in-play)
                   [(packet {:packet/name :unknown :packet/id 83})
                    (packet {:packet/name :unknown :packet/id 83})])]
    (is (= {[:play 83] 2} (:stats/unknown w)))))

(deftest a-mob-is-tracked-with-its-motion-health-and-death
  ;; issue #258: every entity is kept, with its velocity, a living one's health and flags from its
  ;; metadata, and its death as the server states it; an interaction's float at the health index
  ;; is not health
  (let [pig (blocks/entity-types :pig)
        interaction (blocks/entity-types :interaction)
        [w _] (run (in-play)
                   [(packet {:packet/name :add-entity
                             :entity-id 60
                             :uuid nil
                             :type pig
                             :x 4.5
                             :y 64.0
                             :z 4.5
                             :vel [0.0 -0.0784 0.0]
                             :pitch 0
                             :yaw 0
                             :head-yaw 0
                             :data 0})
                    (packet {:packet/name :add-entity
                             :entity-id 61
                             :uuid nil
                             :type interaction
                             :x 0.5
                             :y 64.0
                             :z 0.5})
                    (packet
                     {:packet/name :set-entity-data :entity-id 60 :metadata {0 1 1 300 9 7.5}})
                    (packet {:packet/name :set-entity-data :entity-id 61 :metadata {8 1.0 9 2.0}})
                    (packet {:packet/name :set-entity-data :entity-id 99 :metadata {9 3.0}})
                    (packet {:packet/name :set-entity-motion :entity-id 60 :vel [0.25 0.0 0.0]})])]
    (is (= {:entity/type pig
            :entity/pos [4.5 64.0 4.5]
            :entity/seen-at 0
            :entity/vel [0.25 0.0 0.0]
            :entity/yaw 0.0
            :entity/health 7.5
            :entity/flags 1}
           (get-in w [:world/entities 60])))
    (is (not (contains? (get-in w [:world/entities 61]) :entity/health))
        "an interaction's height is not health")
    (is (not (contains? (:world/entities w) 99)) "metadata for an untracked entity adds nothing")
    (testing "a teleport moves it, relative axes added"
      (let [[w2 _] (run w
                        [(packet {:packet/name :teleport-entity
                                  :entity-id 60
                                  :x 1.0
                                  :y 70.0
                                  :z 0.0
                                  :dx 0.0
                                  :dy 0.0
                                  :dz 0.0
                                  :yaw 0.0
                                  :pitch 0.0
                                  :flags 5
                                  :on-ground false})])]
        (is (= [5.5 70.0 4.5] (get-in w2 [:world/entities 60 :entity/pos])))))
    (testing "a death is a fact, then the removal"
      (let [[w2 _] (run w
                        [{:event/kind :tick :event/now 500 :event/rand 0.5}
                         (packet {:packet/name :entity-event :entity-id 60 :status 3})])
            [w3 _] (run w2 [(packet {:packet/name :remove-entities :ids [60]})])]
        (is (= 500 (get-in w2 [:world/entities 60 :entity/died-at])))
        (is (nil? (get-in w3 [:world/entities 60])))))))

(deftest damage-event-stores-actual-entity-id-from-source-direct-and-cause
  ;; Minecraft protocol [:play :damage-event] encodes entity IDs as 1 + Entity ID (0 = none).
  ;; The handler decrements source-direct and source-cause to record the real entity ID in :player/attacker.
  (let [w (assoc (in-play) :time/tick 120)
        [w2 _] (run
                w
                [(packet
                  {:packet/name :damage-event :entity-id 7 :source-direct 91 :source-cause 91})])
        [w3 _] (run
                w
                [(packet
                  {:packet/name :damage-event :entity-id 7 :source-direct 0 :source-cause 169})])]
    (is (= 90 (:player/attacker w2)))
    (is (= 120 (:player/hurt-at w2)))
    (is (= 168 (:player/attacker w3)))))

