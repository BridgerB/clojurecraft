(ns clojurecraft.nbt
  "Network NBT as values, enough to read the server's text components and to write them for the
   model: one tag-type byte, then the payload, no root name (the form every component takes on the
   775 wire). A compound is a map with string keys kept as it arrived, a list a vector, a string a
   string, every integer a long and every float a double (the width a value had is not kept: the
   writer picks the narrowest of int and long, and double for a fraction). Strings are modified
   UTF-8 under a u16 length (Java's readUTF). The reader is bounded and total: a malformed tag
   reads as nil with the buffer where it began, never an exception."
  (:require [clojurecraft.bytes :as b])
  (:import [java.io ByteArrayInputStream DataInputStream DataOutputStream]
           [java.nio ByteBuffer]))

(set! *warn-on-reflection* true)

(def max-depth 32)                     ; nesting a component may have before it reads as malformed (vanilla allows 512; no real component nears this)

(def tag-end 0)
(def tag-byte 1)
(def tag-int 3)
(def tag-long 4)
(def tag-double 6)
(def tag-string 8)
(def tag-list 9)
(def tag-compound 10)

(defn malformed
  "Throws the reader's own failure, caught in read-component: what the buffer held was no NBT."
  [why]
  (throw (ex-info "malformed nbt" {:nbt/why why})))

(defn read-utf
  "A modified-UTF-8 string under a u16 length from buf."
  ^String [^ByteBuffer buf]
  (let [n (b/read-u16 buf)
        body (b/read-bytes buf n)
        framed (byte-array (+ 2 n))]
    (aset-byte framed 0 (unchecked-byte (bit-shift-right n 8)))
    (aset-byte framed 1 (unchecked-byte n))
    (System/arraycopy body 0 framed 2 n)
    (.readUTF (DataInputStream. (ByteArrayInputStream. framed)))))

(declare read-payload)

(defn read-array
  "n numbers of reader f from buf as a vector; n must fit in what is left (width bytes each)."
  [^ByteBuffer buf f width]
  (let [n (b/read-i32 buf)]
    (when (or (neg? n) (> (* n width) (.remaining buf))) (malformed :array-length))
    (vec (repeatedly n #(f buf)))))

(defn read-list
  "A list payload: an element tag type, a count, then that many payloads, as a vector. The count
   must fit in what is left (an element is at least a byte, unless the type is the end tag)."
  [^ByteBuffer buf depth]
  (let [t (b/read-u8 buf)
        n (b/read-i32 buf)]
    (cond (and (pos? n) (= t tag-end)) (malformed :list-of-end)
          (or (neg? n) (> n (.remaining buf))) (malformed :list-length)
          :else (vec (repeatedly n #(read-payload t buf (inc depth)))))))

(defn read-compound
  "A compound payload: (type, name, payload) entries up to the end tag, as a map with string keys.
   Walks until the end tag, a condition, so it is a loop."
  [^ByteBuffer buf depth]
  (loop [m {}]
    (let [t (b/read-u8 buf)]
      (if (= t tag-end)
        m
        (let [k (read-utf buf)] (recur (assoc m k (read-payload t buf (inc depth)))))))))

(defn read-payload
  "The payload of tag type t from buf: a string, a long, a double, a vector or a map. Throws
   (malformed) for a type that is not one, or nesting past max-depth."
  [t ^ByteBuffer buf depth]
  (when (> depth max-depth) (malformed :depth))
  (case (long t)
    1 (b/read-i8 buf)
    2 (b/read-i16 buf)
    3 (b/read-i32 buf)
    4 (b/read-i64 buf)
    5 (b/read-f32 buf)
    6 (b/read-f64 buf)
    7 (read-array buf b/read-i8 1)
    8 (read-utf buf)
    9 (read-list buf depth)
    10 (read-compound buf depth)
    11 (read-array buf b/read-i32 4)
    12 (read-array buf b/read-i64 8)
    (malformed :tag-type)))

(defn read-component
  "One nameless network NBT tag from buf as a value: a string, a map, a vector or a number. A
   malformed one (a bad tag type, a length past the frame, nesting too deep, a short buffer)
   reads as nil and leaves buf where the tag began, so what follows is still located."
  [^ByteBuffer buf]
  (let [start (.position buf)]
    (try (let [t (b/read-u8 buf)] (read-payload t buf 0))
         (catch Exception _ (.position buf start) nil))))

;; ---------------------------------------------------------------- writing

(defn tag-of
  "The tag type that writes value v: string, compound (map), list (vector), int or long by size,
   double for a fraction, byte for a boolean. Throws for any other value."
  [v]
  (cond (string? v) tag-string
        (map? v) tag-compound
        (vector? v) tag-list
        (boolean? v) tag-byte
        (integer? v) (if (<= Integer/MIN_VALUE v Integer/MAX_VALUE) tag-int tag-long)
        (number? v) tag-double
        :else (throw (ex-info "no nbt tag for value" {:value v}))))

(declare write-payload)

(defn write-list
  "A list payload of vector v: its first element's tag type (the end tag when empty), the count,
   then each payload; throws when the elements do not share one tag type."
  [^DataOutputStream out v]
  (let [t (if (seq v) (tag-of (first v)) tag-end)]
    (when-not (every? #(= t (tag-of %)) v) (throw (ex-info "mixed nbt list" {:list v})))
    (b/write-u8 out t)
    (b/write-i32 out (count v))
    (run! #(write-payload out t %) v)))

(defn write-compound
  "A compound payload of map m: each entry's tag type, key and payload in key order, then the end tag."
  [^DataOutputStream out m]
  (run! (fn [[k v]]
          (let [t (tag-of v)]
            (b/write-u8 out t)
            (.writeUTF out ^String k)
            (write-payload out t v)))
        (sort-by key m))
  (b/write-u8 out tag-end))

(defn write-payload
  "The payload of v as tag type t to out."
  [^DataOutputStream out t v]
  (case (long t)
    1
    (b/write-i8 out
                (cond (true? v) 1
                      (false? v) 0
                      :else v))
    3 (b/write-i32 out v)
    4 (b/write-i64 out v)
    6 (b/write-f64 out (double v))
    8 (.writeUTF out ^String v)
    9 (write-list out v)
    10 (write-compound out v)))

(defn write-component
  "One nameless network NBT tag of value v to out: the tag type, then the payload."
  [^DataOutputStream out v]
  (let [t (tag-of v)]
    (b/write-u8 out t)
    (write-payload out t v)))
