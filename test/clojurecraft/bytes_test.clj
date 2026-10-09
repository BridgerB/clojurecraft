(ns clojurecraft.bytes-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.bytes :as b]))

(defn roundtrip [write read v]
  (read (b/buffer (b/with-out #(write % v)))))

(deftest varint
  (doseq [v [0 1 127 128 255 300 2147483647 -1 -2147483648]]
    (is (= v (roundtrip b/write-varint b/read-varint v)) (str "varint " v)))
  (testing "known encodings"
    (is (= [0x80 0x01] (map #(bit-and % 0xFF) (b/with-out #(b/write-varint % 128)))))
    (is (= [0xff 0xff 0xff 0xff 0x0f] (map #(bit-and % 0xFF) (b/with-out #(b/write-varint % -1)))))))

(deftest varlong
  (doseq [v [0 1 127 128 2147483648 -1 Long/MAX_VALUE Long/MIN_VALUE]]
    (is (= v (roundtrip b/write-varlong b/read-varlong v)) (str "varlong " v))))

(deftest primitives
  (is (= 65535 (roundtrip b/write-u16 b/read-u16 65535)))
  (is (= -1 (roundtrip b/write-i16 b/read-i16 -1)))
  (is (= 4294967295 (roundtrip b/write-u32 b/read-u32 4294967295)))
  (is (= 255 (roundtrip b/write-u8 b/read-u8 255)))
  (is (= -128 (roundtrip b/write-i8 b/read-i8 -128)))
  (is (= 1.5 (roundtrip b/write-f32 b/read-f32 1.5)))
  (is (= 1.0E10 (roundtrip b/write-f64 b/read-f64 1.0E10)))
  (is (true? (roundtrip b/write-bool b/read-bool true))))

(deftest strings-and-uuids
  (is (= "héllo wörld" (roundtrip b/write-string b/read-string "héllo wörld")))
  (is (= "" (roundtrip b/write-string b/read-string "")))
  (let [u (java.util.UUID/randomUUID)]
    (is (= u (roundtrip b/write-uuid b/read-uuid u)))))

(deftest position
  (doseq [p [[0 0 0] [1 -60 0] [-1 -64 -1] [33554431 2047 -33554432] [19336 64 18752] [-20000 319 20000]]]
    (is (= p (roundtrip b/write-position b/read-position p)) (str "position " p)))
  (testing "wiki example: x=18357644 y=831 z=-20882616 encodes as 0x4607632C15B4833F"
    (is (= 0x4607632C15B4833F (b/pack-position [18357644 831 -20882616])))))

(deftest offline-uuid
  ;; MD5 name-uuid of "OfflinePlayer:Steve"
  (is (= "5627dd98-e6be-3c21-b8a8-e92344183641" (str (b/offline-uuid "Steve")))))
