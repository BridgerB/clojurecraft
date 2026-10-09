(ns clojurecraft.packet-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.bytes :as b]
            [clojurecraft.packet :as p]))

(def a-uuid (java.util.UUID/fromString "5627dd98-e6be-3c21-b8a8-e92344183641"))

(defn sample [t]
  (cond
    (and (vector? t) (vector? (first t))) (into {} (map (fn [[k ft]] [k (sample ft)]) t))
    (vector? t) [(sample (second t)) (sample (second t))]
    :else (case t
            :bool true :i8 -5 :u8 200 :i16 -300 :u16 60000 :i32 -70000 :u32 4000000000
            :i64 -1234567890123 :f32 1.5 :f64 -2.25 :varint -1 :varlong 1234567890123
            :string "héllo" :uuid a-uuid :position [-17 -60 42]
            :hashed-slot {:item 134 :count 3})))

(deftest every-c2s-spec-roundtrips
  (doseq [[[state dir pkt-name] fields] p/specs :when (= dir :c2s)]
    (let [m (assoc (into {} (map (fn [[k t]] [k (sample t)]) fields)) :packet/name pkt-name)]
      (is (= m (p/decode state :c2s (p/encode state m))) (str state " " pkt-name)))))

(deftest ids-match-the-vanilla-report
  (is (= 0x2c (get-in p/ids [:play :s2c :keep-alive])))
  (is (= 0x48 (get-in p/ids [:play :s2c :player-position])))
  (is (= 0x2d (get-in p/ids [:play :s2c :level-chunk-with-light])))
  (is (= 0x1f (get-in p/ids [:play :c2s :move-player-pos-rot])))
  (is (= 0x29 (get-in p/ids [:play :c2s :player-action])))
  (is (= 0x0e (get-in p/ids [:configuration :s2c :select-known-packs])))
  (testing "every spec names a real packet"
    (doseq [[[state dir pkt-name]] p/specs]
      (is (get-in p/ids [state dir pkt-name]) (str state " " dir " " pkt-name)))))

(deftest decode-server-packets
  (testing "player-position"
    (let [frame (b/with-out (fn [o] (b/write-varint o 0x48) (b/write-varint o 7)
                              (b/write-f64 o 1.5) (b/write-f64 o 64.0) (b/write-f64 o -3.5)
                              (b/write-f64 o 0.0) (b/write-f64 o 0.0) (b/write-f64 o 0.0)
                              (b/write-f32 o 90.0) (b/write-f32 o 0.0) (b/write-u32 o 0)))]
      (is (= {:packet/name :player-position :teleport-id 7 :x 1.5 :y 64.0 :z -3.5 :dx 0.0 :dy 0.0 :dz 0.0
              :yaw 90.0 :pitch 0.0 :flags 0}
             (p/decode :play :s2c frame)))))
  (testing "the tail of a frame is ignored"
    (let [frame (b/with-out (fn [o] (b/write-varint o 0x2c) (b/write-i64 o 99) (b/write-bytes o (byte-array 10))))]
      (is (= {:packet/name :keep-alive :id 99} (p/decode :play :s2c frame)))))
  (testing "unknown and unmodelled ids"
    (is (= :unknown (:packet/name (p/decode :play :s2c (b/with-out #(b/write-varint % 999))))))
    (let [d (p/decode :play :s2c (b/with-out #(b/write-varint % 113)))]
      (is (= :unknown (:packet/name d)))
      (is (= :set-time (:packet/known d)))))
  (testing "slots"
    (let [frame (b/with-out (fn [o] (b/write-varint o 0x14) (b/write-varint o 0) (b/write-varint o 3)
                              (b/write-i16 o 36) (b/write-varint o 1) (b/write-varint o 134)
                              (b/write-varint o 0) (b/write-varint o 0)))]
      (is (= {:packet/name :container-set-slot :window-id 0 :state-id 3 :slot 36 :item {:item 134 :count 1}}
             (p/decode :play :s2c frame))))
    (let [frame (b/with-out (fn [o] (b/write-varint o 0x14) (b/write-varint o 0) (b/write-varint o 3)
                              (b/write-i16 o 36) (b/write-varint o 1) (b/write-varint o 134)
                              (b/write-varint o 2) (b/write-varint o 0) (b/write-bytes o (byte-array 5))))
          d (p/decode :play :s2c frame)]
      (is (:truncated d))
      (is (= {:item 134 :count 1 :components? true} (:item d))))
    (let [empty-slot (b/with-out (fn [o] (b/write-varint o 0x6c) (b/write-varint o 5) (b/write-varint o 0)))]
      (is (= {:packet/name :set-player-inventory :slot 5 :item nil} (p/decode :play :s2c empty-slot))))))

(deftest container-click-wire-format
  (testing "775 layout: window, state id, slot, button, mode, changed slots, cursor"
    (let [bytes (p/encode :play {:packet/name :container-click :window-id 0 :state-id 7 :slot 36 :button 1
                                 :mode 0 :changed [] :cursor nil})]
      (is (= [0x12 0 7 0 36 1 0 0 0] (map #(bit-and % 0xFF) bytes))))))

(deftest transitions
  (is (= :login (p/next-state :handshake :intention)))
  (is (= :configuration (p/next-state :login :login-acknowledged)))
  (is (= :play (p/next-state :configuration :finish-configuration)))
  (is (= :play (p/next-state :play :swing))))
