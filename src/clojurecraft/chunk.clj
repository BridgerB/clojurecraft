(ns clojurecraft.chunk
  "Chunk columns as values. A column is {:sections [section ...]} with 24 sections for the
   overworld (y -64..319). A section is the paletted container straight off the wire:
   {:single id} or {:bits n :palette [ids] :longs long[]} (palette nil when direct). Nothing is
   expanded to 4096 entries; block-at indexes the bits on demand. The long[] is never mutated
   after decode, so a section is a value."
  (:require [clojurecraft.bytes :as b])
  (:import [java.nio ByteBuffer]))

(def min-y -64)
(def section-count 24)

(defn read-container [^ByteBuffer buf ^long max-bits ^long entries]
  (let [bits (b/read-u8 buf)]
    (if (zero? bits)
      {:single (b/read-varint buf)}
      (let [palette (when (<= bits max-bits)
                      (let [n (b/read-varint buf)]
                        (vec (repeatedly n #(b/read-varint buf)))))
            per-long (quot 64 bits)
            n (long (Math/ceil (/ (double entries) per-long)))
            longs (long-array n)]
        (dotimes [i n] (aset longs i (.getLong buf)))
        {:bits bits :palette palette :longs longs}))))

(defn read-section
  "One section: block count, fluid count (26.1+), block container, biome container."
  [^ByteBuffer buf]
  (b/read-i16 buf)
  (b/read-i16 buf)
  (let [blocks (read-container buf 8 4096)]
    (read-container buf 3 64)
    blocks))

(defn decode
  "The chunkData bytes of level_chunk_with_light → column."
  [^bytes data]
  (let [buf (b/buffer data)]
    {:sections (vec (repeatedly section-count #(read-section buf)))}))

(defn section-get ^long [section ^long idx]
  (if-let [s (:single section)]
    s
    (let [bits (long (:bits section))
          per-long (quot 64 bits)
          ^longs longs (:longs section)
          v (bit-and (unsigned-bit-shift-right (aget longs (quot idx per-long))
                                               (* bits (rem idx per-long)))
                     (dec (bit-shift-left 1 bits)))]
      (if-let [p (:palette section)] (nth p v) v))))

(defn block-index ^long [^long lx ^long ly ^long lz]
  (bit-or (bit-shift-left ly 8) (bit-shift-left lz 4) lx))

(defn section-index ^long [^long y] (bit-shift-right (- y min-y) 4))

(defn block-at
  "State id at world [x y z] from a {[cx cz] column} map, or nil when the chunk is not loaded."
  [chunks [x y z]]
  (let [x (long x) y (long y) z (long z)]
    (when-let [col (get chunks [(bit-shift-right x 4) (bit-shift-right z 4)])]
      (let [si (section-index y)]
        (when (< -1 si section-count)
          (section-get (nth (:sections col) si)
                       (block-index (bit-and x 15) (bit-and y 15) (bit-and z 15))))))))

(defn section-may-contain?
  "Cheap palette test before scanning 4096 cells."
  [section pred]
  (if-let [s (:single section)]
    (boolean (pred s))
    (if-let [p (:palette section)] (boolean (some pred p)) true)))

(defn section-find
  "Seq of [lx ly lz id] for cells whose id satisfies pred."
  [section pred]
  (when (section-may-contain? section pred)
    (for [idx (range 4096)
          :let [id (section-get section idx)]
          :when (pred id)]
      [(bit-and idx 15) (bit-shift-right idx 8) (bit-and (bit-shift-right idx 4) 15) id])))

(defn find-blocks
  "Seq of [x y z id] over loaded chunks for cells satisfying pred."
  [chunks pred]
  (for [[[cx cz] col] chunks
        [si section] (map-indexed vector (:sections col))
        [lx ly lz id] (section-find section pred)]
    [(+ (* 16 cx) lx) (+ min-y (* 16 si) ly) (+ (* 16 cz) lz) id]))
