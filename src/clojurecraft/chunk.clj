(ns clojurecraft.chunk
  "Chunk columns as values. A column is {:sections [section ...] :chunk/min-y y}: 24 sections
   from y -64 in the overworld, 16 from y 0 in the Nether and the End, as many as the server sent
   (a column without :chunk/min-y is an overworld one). A section is the paletted container straight off the wire:
   {:single id} or {:bits n :palette [ids] :longs long[]} (palette nil when direct). Nothing is
   expanded to 4096 entries; block-at indexes the bits on demand. The long[] is never mutated
   after decode, so a section is a value."
  (:require [clojure.math :as math]
            [clojurecraft.bytes :as b]
            [clojurecraft.dimension :as dimension])
  (:import [java.nio ByteBuffer]))

(set! *warn-on-reflection* true)

(def min-y -64)                       ; the lowest block y of an overworld column
(def section-count 24)                ; 16-block sections per overworld column (y -64..319)

(defn read-container
  "One paletted container: {:single id} for 0 bits, else {:bits :palette :longs}
   with palette nil above max-bits (direct ids). 26.1 sends no long count; it is
   ceil(entries / floor(64 / bits))."
  [^ByteBuffer buf ^long max-bits ^long entries]
  (let [bits (b/read-u8 buf)]
    (if (zero? bits)
      {:single (b/read-varint buf)}
      (let [palette (when (<= bits max-bits)
                      (let [n (b/read-varint buf)] (vec (repeatedly n #(b/read-varint buf)))))
            per-long (quot 64 bits)
            n (long (math/ceil (/ (double entries) per-long)))
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
  "The chunkData bytes of level_chunk_with_light → column: every section the bytes hold (the
   server writes exactly its dimension's count, 16 in the Nether), and the lowest y that many
   sections start at (dimension/min-y-of-sections). Throws when no dimension is that tall."
  [^bytes data]
  (let [^ByteBuffer buf (b/buffer data)
        sections (loop [acc []] (if (.hasRemaining buf) (recur (conj acc (read-section buf))) acc))
        min-y (dimension/min-y-of-sections (count sections))]
    (when-not min-y (throw (ex-info "no dimension is this tall" {:sections (count sections)})))
    {:sections sections :chunk/min-y min-y}))

(defn section-get
  "The id at index idx (0-4095 for blocks) of a section, through the palette when
   there is one. Entries never straddle longs."
  ^long [section ^long idx]
  (if-let [s (:single section)]
    s
    (let [bits (long (:bits section))
          per-long (quot 64 bits)
          ^longs longs (:longs section)
          v (bit-and (unsigned-bit-shift-right (aget longs (quot idx per-long))
                                               (* bits (rem idx per-long)))
                     (dec (bit-shift-left 1 bits)))]
      (if-let [p (:palette section)]
        (nth p v)
        v))))

(defn block-index
  "Index of local cell (lx ly lz), each 0-15, inside a section: y, then z, then x."
  ^long [^long lx ^long ly ^long lz]
  (bit-or (bit-shift-left ly 8) (bit-shift-left lz 4) lx))

(defn column-min-y
  "The lowest block y of col: its :chunk/min-y, the overworld's for a column without one."
  ^long [col]
  (long (:chunk/min-y col min-y)))

(defn block-at
  "State id at world [x y z] from a {[cx cz] column} map, or nil when the chunk is not loaded."
  [chunks [x y z]]
  (let [x (long x)
        y (long y)
        z (long z)]
    (when-let [col (get chunks [(bit-shift-right x 4) (bit-shift-right z 4)])]
      (let [si (bit-shift-right (- y (column-min-y col)) 4)]
        (when (< -1 si (count (:sections col)))
          (section-get (nth (:sections col) si)
                       (block-index (bit-and x 15) (bit-and y 15) (bit-and z 15))))))))

(defn section-may-contain?
  "Cheap palette test before scanning 4096 cells."
  [section pred]
  (let [{:keys [single palette]} section]
    (cond single (boolean (pred single))
          palette (boolean (some pred palette))
          :else true)))

(defn cell-of-index
  "The local cell [lx ly lz] of index idx (0-4095) in a section, with id: [lx ly lz id]."
  [^long idx id]
  [(bit-and idx 15) (bit-shift-right idx 8) (bit-and (bit-shift-right idx 4) 15) id])

(defn section-find
  "[lx ly lz id] for every cell whose id satisfies pred, in index order (nil when none can). A
   section of one id asks pred once; a paletted one asks it once per palette entry and tests each
   cell's index against those answers; the cells are walked over the packed longs unboxed. Every
   load scans a column's sections, and the lazy walk, pred asked of all 4096 cells, was a tenth of
   a race's CPU."
  [section pred]
  (let [{:keys [single bits palette longs]} section]
    (cond (some? single) (when (pred single) (into [] (map #(cell-of-index % single)) (range 4096)))
          (and palette (not-any? pred palette)) nil
          :else
          (let [bits (long bits)
                ^longs longs longs
                per-long (quot 64 bits)
                mask (dec (bit-shift-left 1 bits))
                ^booleans want (when palette (boolean-array (map (comp boolean pred) palette)))]
            (loop [idx 0
                   acc (transient [])]
              (if (== idx 4096)
                (not-empty (persistent! acc))
                (let [v (bit-and (unsigned-bit-shift-right (aget longs (quot idx per-long))
                                                           (* bits (rem idx per-long)))
                                 mask)
                      hit (if want (aget want (int v)) (pred v))]
                  (recur (inc idx)
                         (if hit
                           (conj! acc (cell-of-index idx (if palette (nth palette v) v)))
                           acc)))))))))

(defn find-blocks
  "Seq of [x y z id] over loaded chunks for cells satisfying pred."
  [chunks pred]
  (for [[[cx cz] col] chunks
        [si section] (map-indexed vector (:sections col))
        [lx ly lz id] (section-find section pred)]
    [(+ (* 16 cx) lx) (+ (column-min-y col) (* 16 si) ly) (+ (* 16 cz) lz) id]))

(defn attach
  "The packet with its column decoded under :chunk/column (or {:chunk/error msg}), when it is a
   level-chunk-with-light that has none yet; any other packet unchanged. Pure, so it can run on
   the socket's reader thread (decoding is about bytes, not the game) and again on replay of the
   wire bytes with the same result."
  [pkt]
  (if (or (not= :level-chunk-with-light (:packet/name pkt)) (contains? pkt :chunk/column))
    pkt
    (assoc pkt
           :chunk/column
           (try (decode (:data pkt)) (catch Exception e {:chunk/error (str e)})))))
