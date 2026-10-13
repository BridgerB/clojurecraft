(ns clojurecraft.world
  "Test fixture: build chunk-column bytes from a {[lx y lz] state-id} map on top of a stone
   floor (y < 64)."
  (:require [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]))

(defn direct-section
  [o cells]
  (b/write-i16 o (count cells))
  (b/write-i16 o 0)
  (b/write-u8 o 15)
  (let [longs (long-array 1024)]
    (doseq [[[lx ly lz] id] cells]
      (let [idx (chunk/block-index lx ly lz)
            li (quot idx 4)
            off (* 15 (rem idx 4))]
        (aset longs li (bit-or (aget longs li) (bit-shift-left (long id) off)))))
    (dotimes [i 1024] (b/write-i64 o (aget longs i))))
  (b/write-u8 o 0)
  (b/write-varint o 0))

(defn single-section
  [o id]
  (b/write-i16 o 0)
  (b/write-i16 o 0)
  (b/write-u8 o 0)
  (b/write-varint o id)
  (b/write-u8 o 0)
  (b/write-varint o 0))

(def floor-y 64)                      ; the stone floor fills every cell below this

(defn column-bytes
  "blocks: {[lx y lz] id} with world y; everything below y=64 is stone unless blocks says
   otherwise (a listed cell below the floor, e.g. air for a trench or water for a pond, wins).
   With a dimension name, a column of that dimension's height (16 sections from y 0 in the
   Nether), else the overworld's."
  ([blocks] (column-bytes blocks dimension/overworld))
  ([blocks dim]
   (let [{:dimension/keys [min-y sections]} (dimension/by-name dim)]
     (b/with-out
      (fn [o]
        (dotimes [si sections]
          (let [y0 (+ min-y (* 16 si))
                listed
                (into {}
                      (for [[[lx y lz] id] blocks :when (<= y0 y (+ y0 15))] [[lx (- y y0) lz] id]))
                stone (when (and (seq listed) (< y0 floor-y))
                        (into {}
                              (for [lx (range 16)
                                    ly (range 16)
                                    lz (range 16)
                                    :when (< (+ y0 ly) floor-y)]
                                [[lx ly lz] 1])))
                cells (merge stone listed)]
            (cond (seq cells) (direct-section o cells)
                  (< y0 64) (single-section o 1)
                  :else (single-section o 0)))))))))

(defn column [blocks] (chunk/decode (column-bytes blocks)))
