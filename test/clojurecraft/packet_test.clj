(ns clojurecraft.packet-test
  (:require [clojure.edn :as edn]
            [clojure.math :as math]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.packet :as p]))

(def a-uuid (java.util.UUID/fromString "5627dd98-e6be-3c21-b8a8-e92344183641"))

(defn sample
  [t]
  (cond (and (vector? t) (vector? (first t))) (into {} (map (fn [[k ft]] [k (sample ft)]) t))
        (vector? t) [(sample (second t)) (sample (second t))]
        :else
        (case t
          :bool true
          :i8 -5
          :u8 200
          :i16 -300
          :u16 60000
          :i32 -70000
          :u32 4000000000
          :i64 -1234567890123
          :f32 1.5
          :f64 -2.25
          :varint -1
          :varlong 1234567890123
          :string "héllo"
          :uuid a-uuid
          :position [-17 -60 42]
          :hashed-slot {:item 134 :count 3})))

(deftest every-c2s-spec-roundtrips
  (doseq [[[state dir pkt-name] fields] p/specs
          :when (= dir :c2s)]
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
    (let [frame (b/with-out (fn [o]
                              (b/write-varint o 0x48)
                              (b/write-varint o 7)
                              (b/write-f64 o 1.5)
                              (b/write-f64 o 64.0)
                              (b/write-f64 o -3.5)
                              (b/write-f64 o 0.0)
                              (b/write-f64 o 0.0)
                              (b/write-f64 o 0.0)
                              (b/write-f32 o 90.0)
                              (b/write-f32 o 0.0)
                              (b/write-u32 o 0)))]
      (is (= {:packet/name :player-position
              :teleport-id 7
              :x 1.5
              :y 64.0
              :z -3.5
              :dx 0.0
              :dy 0.0
              :dz 0.0
              :yaw 90.0
              :pitch 0.0
              :flags 0}
             (p/decode :play :s2c frame)))))
  (testing "the tail of a frame is ignored"
    (let [frame
          (b/with-out
           (fn [o] (b/write-varint o 0x2c) (b/write-i64 o 99) (b/write-bytes o (byte-array 10))))]
      (is (= {:packet/name :keep-alive :id 99} (p/decode :play :s2c frame)))))
  (testing "unknown and unmodelled ids"
    (is (= :unknown (:packet/name (p/decode :play :s2c (b/with-out #(b/write-varint % 999))))))
    (let [d (p/decode :play :s2c (b/with-out #(b/write-varint % 127)))]
      (is (= :unknown (:packet/name d)))
      (is (= :ticking-state (:packet/known d)))))
  (testing "slots"
    (let [frame (b/with-out (fn [o]
                              (b/write-varint o 0x14)
                              (b/write-varint o 0)
                              (b/write-varint o 3)
                              (b/write-i16 o 36)
                              (b/write-varint o 1)
                              (b/write-varint o 134)
                              (b/write-varint o 0)
                              (b/write-varint o 0)))]
      (is (= {:packet/name :container-set-slot
              :window-id 0
              :state-id 3
              :slot 36
              :item {:item 134 :count 1}}
             (p/decode :play :s2c frame))))
    (let [frame (b/with-out (fn [o]
                              (b/write-varint o 0x14)
                              (b/write-varint o 0)
                              (b/write-varint o 3)
                              (b/write-i16 o 36)
                              (b/write-varint o 1)
                              (b/write-varint o 134)
                              (b/write-varint o 2)
                              (b/write-varint o 0)
                              (b/write-bytes o (byte-array 5))))
          d (p/decode :play :s2c frame)]
      (is (:truncated d))
      (is (= {:item 134 :count 1 :components? true} (:item d))))
    (let [empty-slot (b/with-out
                      (fn [o] (b/write-varint o 0x6c) (b/write-varint o 5) (b/write-varint o 0)))]
      (is (= {:packet/name :set-player-inventory :slot 5 :item nil}
             (p/decode :play :s2c empty-slot))))))

(deftest a-worn-tool-does-not-hide-the-slots-after-it
  ;; gym batch iron-a2 run 3: the server filled the bucket and answered with the whole inventory,
  ;; but a worn stone pickaxe (a damage component) came first and every slot after it was lost,
  ;; so the bot never saw the water bucket and failed :no-scoop three times
  (let [pickaxe (blocks/items :stone_pickaxe)
        water-bucket (blocks/items :water_bucket)
        frame (b/with-out
               (fn [o]
                 (b/write-varint o (get-in p/ids [:play :s2c :container-set-content]))
                 (b/write-varint o 0)
                 (b/write-varint o 341)
                 (b/write-varint o 46)
                 (dotimes [i 46]
                   (case (long i)
                     36
                     (doto o ; a worn pickaxe: one component added (damage 17), none removed
                       (b/write-varint 1)
                       (b/write-varint pickaxe)
                       (b/write-varint 1)
                       (b/write-varint 0)
                       (b/write-varint (p/component-ids :damage))
                       (b/write-varint 17))
                     37
                     (doto o
                       (b/write-varint 1)
                       (b/write-varint water-bucket)
                       (b/write-varint 0)
                       (b/write-varint 0))
                     (b/write-varint o 0)))
                 (b/write-varint o 0)))
        d (p/decode :play :s2c frame)]
    (is (not (:truncated d)))
    (is (= 46 (count (:items d))))
    (is (= {:item pickaxe :count 1 :components {:damage 17}} (nth (:items d) 36)))
    (is (= {:item water-bucket :count 1} (nth (:items d) 37)) "the slot after it is read")))

(deftest container-click-wire-format
  (testing "775 layout: window, state id, slot, button, mode, changed slots, cursor"
    (let [bytes (p/encode :play
                          {:packet/name :container-click
                           :window-id 0
                           :state-id 7
                           :slot 36
                           :button 1
                           :mode 0
                           :changed []
                           :cursor nil})]
      (is (= [0x12 0 7 0 36 1 0 0 0] (map #(bit-and % 0xFF) bytes))))))

(deftest use-item-on-wire-format
  (testing
    "775 layout: hand, position, face varint, cursor x y z f32, inside-block, world-border-hit, sequence"
    (let [bytes (p/encode :play
                          {:packet/name :use-item-on
                           :hand 0
                           :pos [7 64 5]
                           :face 1
                           :cursor-x 0.5
                           :cursor-y 1.0
                           :cursor-z 0.5
                           :inside-block false
                           :world-border-hit false
                           :sequence 3})]
      (is (= [0x42 0x00 0x00 0x00 0x01 0xC0 0x00 0x00 0x50 0x40 0x01 0x3F 0x00 0x00 0x00 0x3F 0x80
              0x00 0x00 0x3F 0x00 0x00 0x00 0x00 0x00 0x03]
             (map #(bit-and % 0xFF) bytes))))))

(deftest transitions
  (is (= :login (p/next-state :handshake :intention)))
  (is (= :configuration (p/next-state :login :login-acknowledged)))
  (is (= :play (p/next-state :configuration :finish-configuration)))
  (is (= :play (p/next-state :play :swing))))

(def ever
  "Every packet spec as it has ever been committed (test/clojurecraft/packet_specs_ever.edn)."
  (edn/read-string (slurp "test/clojurecraft/packet_specs_ever.edn")))

(deftest packet-specs-only-grow
  ;; docs/RICH-HICKEY.md, Accrete: "A packet spec only ever gains fields." The decoder reads a
  ;; packet's fields in wire order, so growing means appending: what was there stays a prefix.
  (testing "every spec ever committed still exists, its fields an unchanged prefix of today's"
    (is (= []
           (vec (for [[k fields] ever
                      :let [now (get p/specs k)]
                      :when (not= fields (vec (take (count fields) now)))]
                  k)))))
  (testing "every spec today is in the history, so the next change is held to it too"
    (is (= [] (vec (remove #(contains? ever %) (keys p/specs)))))))

(defn lp-roundtrip [v] (b/read-lpvec3 (b/buffer (b/with-out #(b/write-lpvec3 % v)))))

(deftest an-lpvec3-reads-back-what-the-game-writes
  ;; issue #258: 26.1.2 sends velocities as the game's LpVec3 (probe run 38876275250), not three
  ;; shorts in 1/8000 blocks; 15 bits a component over the largest magnitude rounded up
  (is (= [0.0 0.0 0.0] (lp-roundtrip [0.0 0.0 0.0])))
  (is (= [0] (map #(bit-and % 0xFF) (b/with-out #(b/write-lpvec3 % [0.0 0.0 0.0]))))
      "the zero vector is one zero byte")
  (doseq [v [[0.25 -0.0784 0.0] [-1.0 1.0 0.5] [3.9 -2.0 0.1] [12.5 0.0 -7.25] [0.0 -40.0 0.0]]]
    (let [[x y z] (lp-roundtrip v)
          scale (math/ceil (apply max (map abs v)))]
      (is (every? #(< (abs %) (/ (* 2.0 scale) 32766.0)) (map - [x y z] v))
          (str v " within one step of " scale "/16383")))))

(defn entity-frame
  "A play s2c frame of packet name, its fields written by f."
  [name f]
  (b/with-out (fn [o] (b/write-varint o (get-in p/ids [:play :s2c name])) (f o))))

(deftest entity-packets-decode
  (testing "add-entity carries the velocity, the angles and the data"
    (is
     (= {:packet/name :add-entity
         :entity-id 60
         :uuid a-uuid
         :type 95
         :x 4.5
         :y 64.0
         :z -4.5
         :vel [0.0 0.0 0.0]
         :pitch 0
         :yaw -64
         :head-yaw -64
         :data 0}
        (p/decode :play
                  :s2c
                  (entity-frame :add-entity
                                (fn [o]
                                  (doto o
                                    (b/write-varint 60)
                                    (b/write-uuid a-uuid)
                                    (b/write-varint 95)
                                    (b/write-f64 4.5)
                                    (b/write-f64 64.0)
                                    (b/write-f64 -4.5)
                                    (b/write-lpvec3 [0.0 0.0 0.0])
                                    (b/write-i8 0)
                                    (b/write-i8 -64)
                                    (b/write-i8 -64)
                                    (b/write-varint 0))))))))
  (testing "entity-event: an int id and a status byte"
    (is (= {:packet/name :entity-event :entity-id 60 :status 3}
           (p/decode :play
                     :s2c
                     (entity-frame :entity-event (fn [o] (b/write-i32 o 60) (b/write-i8 o 3)))))))
  (testing "set-entity-data: flags, air, health, then a stop at the first serializer not modelled"
    (let [entry
          (fn [o i serializer write] (b/write-u8 o i) (b/write-varint o serializer) (write o))]
      (is (= {:packet/name :set-entity-data :entity-id 60 :metadata {0 1 1 300 9 7.5}}
             (p/decode :play
                       :s2c
                       (entity-frame :set-entity-data
                                     (fn [o]
                                       (b/write-varint o 60)
                                       (entry o 0 0 #(b/write-u8 % 1))
                                       (entry o 1 1 #(b/write-varint % 300))
                                       (entry o 9 3 #(b/write-f32 % 7.5))
                                       (b/write-u8 o 255))))))
      (is (= {:packet/name :set-entity-data :entity-id 61 :metadata {0 0}}
             (p/decode :play
                       :s2c
                       (entity-frame :set-entity-data
                                     (fn [o]
                                       (b/write-varint o 61)
                                       (entry o 0 0 #(b/write-u8 % 0))
                                       (entry o 2 6 #(b/write-bool % true)) ; a custom name: a component
                                       (b/write-bytes o (byte-array 12))))))
          "a custom name ends the skim before the health"))))
