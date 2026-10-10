(ns clojurecraft.bytes
  "Wire primitives of the Minecraft protocol.

  Readers take a java.nio.ByteBuffer whose position is the cursor; writers take a
  java.io.DataOutputStream. Neither escapes the decode/encode call that owns it, so
  every public function here is bytes-in, value-out."
  (:refer-clojure :exclude [read-string])
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util UUID]))

;; ---------------------------------------------------------------- reading

(defn read-varint
  "LEB128 varint, sign-extended to int32 like the vanilla client."
  ^long [^ByteBuffer b]
  (loop [result 0 shift 0]
    (let [x (bit-and (.get b) 0xFF)
          result (bit-or result (bit-shift-left (bit-and x 0x7F) shift))]
      (cond
        (zero? (bit-and x 0x80)) (long (unchecked-int result))
        (>= shift 28) (throw (ex-info "varint longer than 5 bytes" {}))
        :else (recur result (+ shift 7))))))

(defn read-varlong "LEB128 varlong, up to 10 bytes, as a signed 64-bit long." ^long [^ByteBuffer b]
  (loop [result 0 shift 0]
    (let [x (bit-and (.get b) 0xFF)
          result (bit-or result (bit-shift-left (bit-and x 0x7F) shift))]
      (cond
        (zero? (bit-and x 0x80)) result
        (>= shift 63) (throw (ex-info "varlong longer than 10 bytes" {}))
        :else (recur result (+ shift 7))))))

(defn read-bool "One byte; any nonzero value is true." [^ByteBuffer b] (not (zero? (.get b))))
(defn read-i8 "One signed byte." ^long [^ByteBuffer b] (long (.get b)))
(defn read-u8 "One unsigned byte, 0-255." ^long [^ByteBuffer b] (bit-and (.get b) 0xFF))
(defn read-i16 "Big-endian signed short." ^long [^ByteBuffer b] (long (.getShort b)))
(defn read-u16
  "Big-endian unsigned short, 0-65535."
  ^long [^ByteBuffer b] (bit-and (.getShort b) 0xFFFF))
(defn read-i32 "Big-endian signed int." ^long [^ByteBuffer b] (long (.getInt b)))
(defn read-u32
  "Big-endian unsigned int, as a non-negative long."
  ^long [^ByteBuffer b] (bit-and (.getInt b) 0xFFFFFFFF))
(defn read-i64 "Big-endian signed long." ^long [^ByteBuffer b] (.getLong b))
(defn read-f32
  "Big-endian float, widened to double."
  ^double [^ByteBuffer b] (double (.getFloat b)))
(defn read-f64 "Big-endian double." ^double [^ByteBuffer b] (.getDouble b))

(defn read-bytes
  "The next n bytes as a fresh array; throws when fewer remain."
  ^bytes [^ByteBuffer b ^long n]
  (let [a (byte-array n)] (.get b a) a))

(defn read-string "A varint byte length, then that many bytes of UTF-8." ^String [^ByteBuffer b]
  (String. (read-bytes b (read-varint b)) StandardCharsets/UTF_8))

(defn read-uuid "16 raw bytes: most significant long, then least." ^UUID [^ByteBuffer b]
  (let [msb (.getLong b) lsb (.getLong b)] (UUID. msb lsb)))

(defn unpack-position
  "i64 bitfield x:26 | z:26 | y:12, each signed."
  [^long v]
  [(bit-shift-right v 38)
   (bit-shift-right (bit-shift-left v 52) 52)
   (bit-shift-right (bit-shift-left v 26) 38)])

(defn read-position
  "[x y z] from the packed i64 block position (see unpack-position)."
  [^ByteBuffer b] (unpack-position (.getLong b)))

(defn remaining "Bytes left after the cursor." ^long [^ByteBuffer b] (.remaining b))

(defn read-rest
  "Every byte left after the cursor; the buffer is then exhausted."
  ^bytes [^ByteBuffer b] (read-bytes b (.remaining b)))

;; ---------------------------------------------------------------- writing

(defn write-varint
  "Negative ints go out as their unsigned 32-bit form (5 bytes), like vanilla."
  [^DataOutputStream out ^long v]
  (loop [v (bit-and v 0xFFFFFFFF)]
    (if (zero? (bit-and v (bit-not 0x7F)))
      (.writeByte out (int v))
      (do (.writeByte out (int (bit-or (bit-and v 0x7F) 0x80)))
          (recur (unsigned-bit-shift-right v 7))))))

(defn write-varlong
  "LEB128 varlong; a negative long goes out as 10 bytes."
  [^DataOutputStream out ^long v]
  (loop [v v]
    (if (zero? (bit-and v (bit-not 0x7F)))
      (.writeByte out (int v))
      (do (.writeByte out (int (bit-or (bit-and v 0x7F) 0x80)))
          (recur (unsigned-bit-shift-right v 7))))))

(defn write-bool
  "One byte: 1 for truthy, 0 otherwise."
  [^DataOutputStream out v] (.writeByte out (if v 1 0)))
(defn write-i8 "The low byte of v." [^DataOutputStream out ^long v] (.writeByte out (int v)))
(defn write-u8
  "v masked to 0-255, as one byte."
  [^DataOutputStream out ^long v] (.writeByte out (int (bit-and v 0xFF))))
(defn write-i16
  "The low 16 bits of v, big-endian."
  [^DataOutputStream out ^long v] (.writeShort out (int v)))
(defn write-u16
  "v masked to 0-65535, big-endian."
  [^DataOutputStream out ^long v] (.writeShort out (int (bit-and v 0xFFFF))))
(defn write-i32
  "v as a big-endian int; v must fit in 32 signed bits."
  [^DataOutputStream out ^long v] (.writeInt out (int v)))
(defn write-u32
  "The low 32 bits of v, big-endian (0-4294967295 round-trips with read-u32)."
  [^DataOutputStream out ^long v] (.writeInt out (unchecked-int v)))
(defn write-i64 "v as a big-endian long." [^DataOutputStream out ^long v] (.writeLong out v))
(defn write-f32
  "v narrowed to a float, big-endian."
  [^DataOutputStream out ^double v] (.writeFloat out (float v)))
(defn write-f64 "v as a big-endian double." [^DataOutputStream out ^double v] (.writeDouble out v))
(defn write-bytes
  "Every byte of a, with no length prefix."
  [^DataOutputStream out ^bytes a] (.write out a 0 (alength a)))

(defn write-string
  "Varint UTF-8 byte length (not char count), then the bytes."
  [^DataOutputStream out ^String s]
  (let [a (.getBytes s StandardCharsets/UTF_8)]
    (write-varint out (alength a))
    (write-bytes out a)))

(defn write-uuid "16 raw bytes: most significant long, then least." [^DataOutputStream out ^UUID u]
  (.writeLong out (.getMostSignificantBits u))
  (.writeLong out (.getLeastSignificantBits u)))

(defn pack-position
  "[x y z] into the i64 bitfield x:26 | z:26 | y:12; coordinates are
   truncated to longs and masked, so negatives wrap as the server expects."
  ^long [[x y z]]
  (bit-or (bit-shift-left (bit-and (long x) 0x3FFFFFF) 38)
          (bit-shift-left (bit-and (long z) 0x3FFFFFF) 12)
          (bit-and (long y) 0xFFF)))

(defn write-position
  "[x y z] as the packed i64 block position."
  [^DataOutputStream out pos] (.writeLong out (pack-position pos)))

(defn with-out
  "Run f with a DataOutputStream; return the bytes written."
  ^bytes [f]
  (let [baos (ByteArrayOutputStream.)
        out (DataOutputStream. baos)]
    (f out)
    (.flush out)
    (.toByteArray baos)))

(defn buffer
  "A big-endian ByteBuffer over a, cursor at 0; it shares a, it does not copy it."
  ^ByteBuffer [^bytes a] (ByteBuffer/wrap a))

;; ---------------------------------------------------------------- identity

(defn offline-uuid
  "The UUID an online-mode=false server assigns: MD5 name-UUID (v3) of OfflinePlayer:<name>."
  ^UUID [^String name]
  (UUID/nameUUIDFromBytes (.getBytes (str "OfflinePlayer:" name) StandardCharsets/UTF_8)))
