(ns clojurecraft.chunk-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]))

(defn single-section [o id]
  (b/write-i16 o 0) (b/write-i16 o 0)
  (b/write-u8 o 0) (b/write-varint o id)
  (b/write-u8 o 0) (b/write-varint o 0))

(defn paletted-section
  "4-bit palette [0 136 2] with palette index 1 at local (3,5,7)."
  [o]
  (b/write-i16 o 1) (b/write-i16 o 0)
  (b/write-u8 o 4) (b/write-varint o 3) (b/write-varint o 0) (b/write-varint o 136) (b/write-varint o 2)
  (let [idx (chunk/block-index 3 5 7)]
    (dotimes [i 256]
      (b/write-i64 o (if (= i (quot idx 16)) (bit-shift-left 1 (* 4 (rem idx 16))) 0))))
  (b/write-u8 o 0) (b/write-varint o 0))

(defn direct-section
  "15-bit direct ids with 139 (spruce log) at local (0,0,1)."
  [o]
  (b/write-i16 o 1) (b/write-i16 o 0)
  (b/write-u8 o 15)
  (let [idx (chunk/block-index 0 0 1) per 4]
    (dotimes [i 1024]
      (b/write-i64 o (if (= i (quot idx per)) (bit-shift-left 139 (* 15 (rem idx per))) 0))))
  (b/write-u8 o 0) (b/write-varint o 0))

(def column-bytes
  (b/with-out (fn [o]
                (dotimes [si 24]
                  (case si
                    3 (single-section o 1)          ; y -16..-1 stone
                    4 (paletted-section o)          ; y 0..15
                    5 (direct-section o)            ; y 16..31
                    (single-section o 0))))))

(deftest decode-and-index
  (let [col (chunk/decode column-bytes)
        chunks {[0 0] col}]
    (is (= 24 (count (:sections col))))
    (is (= 1 (chunk/block-at chunks [5 -1 5])) "stone below y=0")
    (is (= 136 (chunk/block-at chunks [3 5 7])) "paletted log")
    (is (= 0 (chunk/block-at chunks [4 5 7])))
    (is (= 139 (chunk/block-at chunks [0 16 1])) "direct spruce log")
    (is (= 0 (chunk/block-at chunks [0 16 0])))
    (is (nil? (chunk/block-at chunks [16 5 7])) "unloaded chunk")
    (is (nil? (chunk/block-at chunks [0 -65 0])) "below the world")
    (is (= [[3 5 7 136] [0 16 1 139]] (vec (chunk/find-blocks chunks blocks/log?))))))

(deftest negative-coordinates
  (let [chunks {[-1 -1] (chunk/decode column-bytes)}]
    (is (= 136 (chunk/block-at chunks [-13 5 -9])) "x=-16+3, z=-16+7")
    (is (= [[-13 5 -9 136] [-16 16 -15 139]] (vec (chunk/find-blocks chunks blocks/log?))))))

(deftest palette-shortcut
  (testing "sections whose palette cannot hold a log are skipped"
    (is (nil? (chunk/section-find {:single 1} blocks/log?)))
    (is (false? (chunk/section-may-contain? {:bits 4 :palette [0 1 2] :longs (long-array 256)} blocks/log?)))
    (is (true? (chunk/section-may-contain? {:bits 15 :palette nil :longs (long-array 1024)} blocks/log?)))))

(deftest attach-decodes-chunk-packets-once
  (let [data (b/with-out (fn [o] (dotimes [_ 24] (single-section o 1))))
        pkt {:packet/name :level-chunk-with-light :x 0 :z 0 :data data}
        once (chunk/attach pkt)]
    (is (= 1 (chunk/block-at {[0 0] (:chunk/column once)} [0 0 0])))
    (is (identical? once (chunk/attach once)) "an attached column is kept, not decoded again")
    (is (= {:packet/name :keep-alive :id 1} (chunk/attach {:packet/name :keep-alive :id 1})))
    (is (:chunk/error (:chunk/column (chunk/attach (assoc pkt :data (byte-array 3))))) "bad bytes become an error value")))
