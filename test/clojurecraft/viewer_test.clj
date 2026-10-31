(ns clojurecraft.viewer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.viewer :as viewer]
            [clojurecraft.world :as world]))

(def assets
  "A few of the jar's files, as read-assets! keeps them."
  {"blockstates/oak_log" {"variants" {"axis=x" {"model" "minecraft:block/oak_log_horizontal"}
                                      "axis=y" {"model" "minecraft:block/oak_log"}}}
   "blockstates/oak_leaves" {"variants" {"" {"model" "minecraft:block/oak_leaves"}}}
   "blockstates/oak_slab" {"variants" {"type=bottom" {"model" "minecraft:block/oak_slab"}}}
   "blockstates/glass" {"variants" {"" {"model" "minecraft:block/glass"}}}
   "models/block/oak_log" {"parent" "minecraft:block/cube_column"
                           "textures" {"end" "minecraft:block/oak_log_top"
                                       "side" "minecraft:block/oak_log"}}
   "models/block/oak_leaves" {"parent" "minecraft:block/cube_column"}
   "models/block/cube_column" {"parent" "block/cube"
                               "textures" {"particle" "#side" "up" "#end" "north" "#side"}}
   "models/block/cube" {"elements" [{"from" [0 0 0]
                                     "to" [16 16 16]
                                     "faces" {"up" {"texture" "#up"}
                                              "north" {"texture" "#north"}}}]}
   "models/block/oak_slab" {"elements" [{"from" [0 0 0] "to" [16 8 16] "faces" {}}]}
   "models/block/glass" {"parent" "block/cube"
                         "textures" {"all" {"force_translucent" true
                                            "sprite" "minecraft:block/glass"}}}
   "items/oak_log" {"model" {"type" "minecraft:model" "model" "minecraft:block/oak_log"}}
   "items/stick" {"model" {"type" "minecraft:model" "model" "minecraft:item/stick"}}
   "models/item/stick" {"parent" "minecraft:item/generated"
                        "textures" {"layer0" "minecraft:item/stick"}}
   "biome/plains" {"effects" {"water_color" "#3f76e4"}}})

(deftest what-hides-a-neighbour
  (is (viewer/opaque-cube? assets "oak_log") "a full cube through its parents")
  (is (not (viewer/opaque-cube? assets "oak_leaves")) "leaves have holes")
  (is (not (viewer/opaque-cube? assets "oak_slab")) "half a block hides nothing")
  (is (not (viewer/opaque-cube? assets "air")) "no model, no faces"))

(deftest the-renderer-gets-steves-assets
  (let [m (viewer/assets-message assets {"oak_log" "AAAA"})
        log (first (filter #(= "oak_log" (:name %)) (:blocks m)))]
    (is (= (count blocks/table) (count (:blocks m))) "every block, in state order")
    (is (= {:name "axis" :values ["x" "y" "z"]} (first (:states log))) "a state's properties")
    (is (false? (:transparent log)))
    (is (= "block" (:boundingBox log)))
    (is (= "minecraft:block/glass" (get-in m [:blockModels "glass" "textures" "all"]))
        "26.x's sprite objects become plain references")
    (is (= [(/ 0x3f 255.0) (/ 0x76 255.0) (/ 0xe4 255.0)] (get-in m [:tints :water "plains"])))
    (is (= ["oak_log"] (:textureNames m)))))

(deftest an-icon-is-the-items-own-texture-or-its-blocks
  (is (= "item/stick" (viewer/icon-texture assets "stick")))
  (is (= "block/oak_log" (viewer/icon-texture assets "oak_log")) "a block item: its particle"))

(deftest a-column-goes-out-in-the-network-format
  (let [log (blocks/default-state :oak_log)
        w {:world/chunks {[0 0] (world/column {[1 64 2] log [5 70 5] 9})}
           :world/blocks {[3 64 3] log [1 63 1] blocks/air}
           :player/pos [8.5 64.0 8.5]}
        back (chunk/decode (viewer/column-bytes w [0 0] {:single 7}))]
    (testing "it reads back as the same blocks, the overlay applied"
      (is (= log (chunk/block-at {[0 0] back} [1 64 2])))
      (is (= log (chunk/block-at {[0 0] back} [3 64 3])))
      (is (= blocks/air (chunk/block-at {[0 0] back} [1 63 1])))
      (is (= 9 (chunk/block-at {[0 0] back} [5 70 5])))
      (is (= 1 (chunk/block-at {[0 0] back} [0 0 0])) "the stone floor")
      (is (= blocks/air (chunk/block-at {[0 0] back} [0 200 0])))
      (is (= {:single 0} (get-in back [:chunk/biomes 8])) "the column's own biomes go back out"))
    (is (= {:single 7}
           (-> (viewer/column-bytes (update-in w [:world/chunks [0 0]] dissoc :chunk/biomes)
                                    [0 0]
                                    {:single 7})
               chunk/decode
               (get-in [:chunk/biomes 3])))
        "a column without biomes gets the fallback")
    (is (= 0 (viewer/biome-at w [1.5 64.0 2.5])) "the biome under the feet")
    (is (= {:x 0 :z 0 :minY -64 :height 384}
           (dissoc (viewer/column-view w [0 0] {:single 7}) :buf)))
    (is (nil? (viewer/column-view w [5 5] {:single 7})) "a column not loaded is nothing")))

(deftest a-section-of-many-states-goes-out-direct
  (let [ids (long-array (range 4096))
        bytes (b/with-out #(viewer/write-section! % ids {:single 3}))
        back (chunk/read-section (java.nio.ByteBuffer/wrap bytes))]
    (is (= 15 (:bits back)))
    (is (= [0 1 4095] (mapv #(chunk/section-get back %) [0 1 4095])))))

(deftest the-frame-shows-what-the-world-holds
  (let [w {:bot/name "V"
           :player/pos [8.5 64.0 8.5]
           :player/look [90.0 10.0]
           :player/inventory {0 {:item (:oak_log blocks/items) :count 3}}
           :player/held-slot 0
           :plan/intent {:intent/kind :dig
                         :intent/goal :wood
                         :intent/child {:intent/kind :walk :intent/target [1 64 2] :intent/path []}}
           :world/chunks {[0 0] (world/column {})}}
        f (viewer/frame w)]
    (is (= {:item :oak_log :count 3} (first (:hotbar f))))
    (is (= #{:intent/kind :intent/target} (set (keys (:intent/child (:intent f)))))
        "an intent shows its shown keys only, its child too")
    (is (= ["0,0"] (keys (:columns f))) "only loaded columns have a version")
    (is (not (contains? f :health)) "an absent attribute stays absent")
    (is (= (:columns f) (:columns (viewer/frame (assoc w :player/look [0.0 0.0]))))
        "a look does not move a column's version")
    (is (not= (:columns f) (:columns (viewer/frame (assoc-in w [:world/blocks [1 64 1]] 1))))
        "a block over it does")))

(deftest a-biome-is-coloured-as-vanilla-colours-it
  (let [m (int-array (range 65536))
        cells (assoc assets "colormap/grass" m "colormap/foliage" m)]
    (testing "the colormap pixel at the climate: x from the temperature, y from the downfall"
      (is (= (bit-or (bit-shift-left 191 8) 127) (viewer/colormap-colour m 0.5 0.5))))
    (is (= 0x90814d (viewer/grass-colour cells {"effects" {"grass_color" "#90814d"}}))
        "its own colour")
    (is (= 0x6a7039
           (viewer/grass-colour
            cells
            {"temperature" 0.8 "downfall" 0.9 "effects" {"grass_color_modifier" "swamp"}})))
    (is (= ["plains"] (viewer/biome-names cells)) "biome ids are the sorted biome files")))

(deftest the-first-person-view-is-the-bots-own-data
  (let [log (blocks/default-state :oak_log)
        w {:time/now 1500
           :player/pos [0.5 64.0 0.5]
           :player/look [0.0 0.0]
           :world/chunks {[0 0] (world/column {[0 65 3] log})}
           :plan/intent {:intent/kind :stairs-down
                         :intent/child {:intent/kind :dig
                                        :intent/stage :digging
                                        :intent/target [0 65 3]
                                        :intent/started 1000
                                        :intent/dig-ms 2000}}}]
    (is (= {:pos [0 65 3] :progress 0.25} (viewer/dig-view w))
        "elapsed over the dig's own expected time, found down the intent chain")
    (is (nil? (viewer/dig-view (assoc-in w [:plan/intent :intent/child :intent/stage] :confirm))))
    (is (= [0 65 3] (viewer/looked-at w)) "the eyes' ray meets the log three blocks south")
    (is (nil? (viewer/looked-at (assoc w :player/look [180.0 0.0]))) "nothing within reach north")
    (is (= 2
           (viewer/arm-moves [{:effect/kind :send :effect/packet {:packet/name :swing}}
                              {:effect/kind :send :effect/packet {:packet/name :player-action}}
                              {:effect/kind :send :effect/packet {:packet/name :attack}}
                              {:effect/kind :log}]))
        "a swing and an attack move the arm; a dig action and a log line do not")))

(deftest the-panel-gets-the-bots-own-state
  (let [w {:player/inventory {0 {:item (:stone_pickaxe blocks/items) :count 1}
                              39 {:item (:iron_helmet blocks/items) :count 1}
                              5 {}}}]
    (is (= {0 {:item :stone_pickaxe :count 1} 39 {:item :iron_helmet :count 1}}
           (viewer/inventory-view w))
        "every slot it holds, the armour too; an empty slot is left out"))
  (let [f1 {:now 10 :intent {:intent/goal :wood :intent/kind :walk :intent/target [1 2 3]}}
        f2 {:now 20
            :intent {:intent/goal :wood :intent/kind :walk :intent/target [1 2 3] :intent/stage :x}}
        f3 {:now 30 :intent {:intent/goal :wood :intent/kind :walk :intent/target [4 5 6]}}
        h (reduce viewer/note-focus [] [f1 f2 f3 {:now 40}])]
    (is (= [[10 [1 2 3]] [30 [4 5 6]]] (mapv (juxt :at :target) h))
        "a change of goal, kind, target or recipe is noted; a stage or no intent is not")
    (is (= viewer/focus-kept
           (count (reduce viewer/note-focus
                          []
                          (for [k (range 100)]
                            {:now k :intent {:intent/goal :wood :intent/target [k 0 0]}}))))
        "only the newest are kept"))
  (is
   (= {:n 2 :at "t" :note "stuck" :pos [1 2 3] :health 20 :food 5 :held 0 :inventory {} :intent nil}
      (viewer/note 2 "t" "stuck" {:pos [1 2 3] :health 20 :food 5 :held 0 :inventory {}}))))

(deftest an-item-is-held-as-its-model-says
  (let [a (assoc assets
                 "models/item/generated"
                 {"display" {"firstperson_righthand" {"rotation" [0 -90 25]}}}
                 "models/item/stick"
                 {"parent" "minecraft:item/generated" "textures" {"layer0" "minecraft:item/stick"}}
                 "models/block/cube"
                 (assoc (get assets "models/block/cube")
                        "display"
                        {"firstperson_righthand" {"rotation" [0 45 0]}}))]
    (is (= {:kind "flat" :display {"rotation" [0 -90 25]}} (viewer/held-view a "stick"))
        "a sprite, with its parent's first-person transform")
    (is (= {:kind "block" :display {"rotation" [0 45 0]}} (viewer/held-view a "oak_log"))
        "a block item: its block model's")))
