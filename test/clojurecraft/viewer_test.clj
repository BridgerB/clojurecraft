(ns clojurecraft.viewer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.viewer :as viewer]
            [clojurecraft.world :as world]))

(def assets
  "A few of the jar's files, as read-assets! keeps them."
  {"blockstates/oak_log" {"variants" {"axis=x" {"model" "minecraft:block/oak_log_horizontal"}
                                      "axis=y" {"model" "minecraft:block/oak_log"}}}
   "blockstates/short_grass" {"variants" {"" {"model" "minecraft:block/short_grass"}}}
   "blockstates/oak_slab" {"variants" {"type=bottom" {"model" "minecraft:block/oak_slab"}}}
   "models/block/oak_log" {"parent" "minecraft:block/cube_column"
                           "textures" {"end" "minecraft:block/oak_log_top"
                                       "side" "minecraft:block/oak_log"}}
   "models/block/cube_column" {"parent" "block/cube"
                               "textures" {"up" "#end" "down" "#end" "north" "#side"}}
   "models/block/cube" {"elements" [{"from" [0 0 0]
                                     "to" [16 16 16]
                                     "faces" {"up" {"texture" "#up"}
                                              "down" {"texture" "#down"}
                                              "north" {"texture" "#north"}}}]}
   "models/block/short_grass" {"parent" "minecraft:block/tinted_cross"
                               "textures" {"cross" "minecraft:block/short_grass"}}
   "models/block/tinted_cross" {"elements" []}
   "models/block/oak_slab" {"elements"
                            [{"from" [0 0 0] "to" [16 8 16] "faces" {"up" {"texture" "#top"}}}]
                            "textures" {"top" "minecraft:block/oak_planks"}}
   "items/stick" {"model" {"type" "minecraft:model" "model" "minecraft:item/stick"}}
   "models/item/stick" {"parent" "minecraft:item/generated"
                        "textures" {"layer0" "minecraft:item/stick"}}})

(deftest a-block-is-drawn-from-its-first-model
  (testing "a log: the upright variant, its textures through the parent's references"
    (is (= {:kind "cube"
            :box [0.0 0.0 0.0 1.0 1.0 1.0]
            :faces {"up" "block/oak_log_top" "down" "block/oak_log_top" "north" "block/oak_log"}
            :tint {}
            :opaque true}
           (viewer/render-def assets "oak_log"))))
  (is (= {:kind "cross" :tex "block/short_grass" :tint (:grass viewer/tints)}
         (viewer/render-def assets "short_grass"))
      "a plant is a tinted cross")
  (is (false? (:opaque (viewer/render-def assets "oak_slab"))) "half a block hides nothing")
  (is (= {:kind "none"} (viewer/render-def assets "air")))
  (is (= "liquid" (:kind (viewer/render-def assets "water"))))
  (is (= "item/stick" (viewer/icon-texture assets "stick")) "a flat item is its layer0"))

(deftest a-column-is-its-blocks-with-the-overlay-over-them
  (let [log (blocks/default-state :oak_log)
        w {:world/chunks {[0 0] (world/column {[1 64 2] log})}
           :world/blocks {[3 64 3] log [1 63 1] blocks/air}
           :player/pos [8.5 64.0 8.5]}
        c (viewer/column-view w [0 0])
        s (first (filter #(= 48 (:y %)) (:sections c)))
        cells (.decode (java.util.Base64/getDecoder) ^String (:data s))
        at (fn [lx ly lz] (get (:palette s) (aget cells (+ (* 256 ly) (* 16 lz) lx))))
        top (first (filter #(= 64 (:y %)) (:sections c)))
        top-cells (.decode (java.util.Base64/getDecoder) ^String (:data top))
        top-at (fn [lx lz] (get (:palette top) (aget top-cells (+ (* 16 lz) lx))))]
    (is (= "stone" (at 0 15 0)) "the floor")
    (is (= "air" (at 1 15 1)) "the overlay's break is over the chunk's stone")
    (is (= "oak_log" (top-at 1 2)) "the chunk's log")
    (is (= "oak_log" (top-at 3 3)) "the overlay's log")
    (is (not-any? #(= 80 (:y %)) (:sections c)) "an all-air section is left out")
    (is (nil? (viewer/column-view w [5 5])) "a column not loaded is nothing")))

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
