(ns clojurecraft.ear-test
  "What the bot hears (the observability plan, hearing): the sound, sound-entity and level-event
   packets decoded from hand-built bytes, the handlers that keep the near ones, and the ledger
   events a reader of a case file sees."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.ear :as ear]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.packet :as p]
            [clojurecraft.registry :as registry]
            [clojurecraft.senses :as senses]))

(use-fixtures :once fx/instrumented)

(def shoot (get registry/sound-ids :entity.blaze.shoot))

(defn frame
  "The bytes of a play s2c packet named n: its id, then f writing the fields."
  [n f]
  (b/with-out (fn [o] (b/write-varint o (get-in p/ids [:play :s2c n])) (f o))))

(defn decoded "The packet map of a frame." [bytes] (p/decode :play :s2c bytes))

(deftest a-sound-packet-decodes-by-registry-id-or-inline
  (testing "by registry id: the varint is the id plus one, the position in eighths of a block"
    (let [pkt (decoded (frame :sound
                              (fn [o]
                                (b/write-varint o (inc shoot))
                                (b/write-varint o 5)
                                (b/write-i32 o 84)
                                (b/write-i32 o -520)
                                (b/write-i32 o 12)
                                (b/write-f32 o 1.0)
                                (b/write-f32 o 0.5)
                                (b/write-i64 o 99))))]
      (is (= {:packet/name :sound
              :sound {:id shoot}
              :source 5
              :x 84
              :y -520
              :z 12
              :volume 1.0
              :pitch 0.5
              :seed 99}
             pkt))
      (is (= :entity.blaze.shoot (registry/sound-name (:sound pkt))))))
  (testing "inline: zero, an identifier, an optional range"
    (let [pkt (decoded (frame :sound
                              (fn [o]
                                (b/write-varint o 0)
                                (b/write-string o "minecraft:entity.ghast.warn")
                                (b/write-bool o true)
                                (b/write-f32 o 16.0)
                                (b/write-varint o 5)
                                (b/write-i32 o 0)
                                (b/write-i32 o 0)
                                (b/write-i32 o 0)
                                (b/write-f32 o 1.0)
                                (b/write-f32 o 1.0)
                                (b/write-i64 o 0))))]
      (is (= {:name "minecraft:entity.ghast.warn" :range 16.0} (:sound pkt)))
      (is (= :entity.ghast.warn (registry/sound-name (:sound pkt))))
      (is (= 1.0 (:pitch pkt)) "what follows the range is still in step"))
    (let [pkt (decoded (frame :sound
                              (fn [o]
                                (b/write-varint o 0)
                                (b/write-string o "custom:thing")
                                (b/write-bool o false)
                                (b/write-varint o 5)
                                (b/write-i32 o 8)
                                (b/write-i32 o 16)
                                (b/write-i32 o 24)
                                (b/write-f32 o 1.0)
                                (b/write-f32 o 1.0)
                                (b/write-i64 o 0))))]
      (is (= {:name "custom:thing"} (:sound pkt)) "no range")
      (is (= [8 16 24] ((juxt :x :y :z) pkt)))))
  (testing "a holder written is the holder read"
    (doseq [h [{:id 0} {:id shoot} {:name "a:b"} {:name "a:b" :range 2.5}]]
      (is (= h (p/read-sound-holder (b/buffer (b/with-out #(p/write-sound-holder % h)))))))))

(deftest a-sound-on-an-entity-and-a-level-event-decode
  (let [pkt (decoded (frame :sound-entity
                            (fn [o]
                              (b/write-varint o (inc shoot))
                              (b/write-varint o 5)
                              (b/write-varint o 4242)
                              (b/write-f32 o 1.0)
                              (b/write-f32 o 1.0)
                              (b/write-i64 o 7))))]
    (is (= 4242 (:entity-id pkt)))
    (is (= {:id shoot} (:sound pkt)))
    (is (= 7 (:seed pkt))))
  (let [pkt (decoded (frame :level-event
                            (fn [o]
                              (b/write-i32 o 1018)
                              (b/write-position o [10 70 -3])
                              (b/write-i32 o 0)
                              (b/write-bool o false))))]
    (is (= {:packet/name :level-event :type 1018 :pos [10 70 -3] :data 0 :global false} pkt))))

(def blaze-eid 90)

(defn playing
  "A bot in play at [0.5 64 0.5] at time 1000, a blaze tracked as entity 90 at [20.5 64 0.5]."
  []
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/entity-id 5
         :player/pos [0.5 64.0 0.5]
         :time/now 1000
         :world/entities
         {blaze-eid {:entity/type (blocks/entity-types :blaze) :entity/pos [20.5 64.0 0.5]}}))

(defn heard "w after packet p." [w p] (game/on-packet w p))

(defn sound-at
  "A sound packet for name at block point [x y z]."
  [n [x y z]]
  {:packet/name :sound
   :sound {:id (get registry/sound-ids n)}
   :source 5
   :x (* 8 x)
   :y (* 8 y)
   :z (* 8 z)
   :volume 1.0
   :pitch 1.0
   :seed 0})

(deftest a-sound-near-is-kept-by-name-and-place
  (let [w (heard (playing) (sound-at :entity.enderman.stare [6 64 0]))]
    (is (= [{:sound :entity.enderman.stare :pos [6.0 64.0 0.0] :at 1000}] (:world/sounds w)))
    (testing "one past the radius is not kept"
      (is (nil? (:world/sounds (heard (playing) (sound-at :entity.enderman.stare [60 64 0]))))))
    (testing "only the last kept-heard are kept"
      (let [w (reduce (fn [w i]
                        (heard (assoc w :time/now i) (sound-at :entity.blaze.ambient [1 64 0])))
                      (playing)
                      (range 30))]
        (is (= ear/kept-heard (count (:world/sounds w))))
        (is (= 29 (:at (peek (:world/sounds w)))))))))

(deftest a-sound-on-an-entity-is-placed-where-the-entity-is-tracked
  (let [pkt {:packet/name :sound-entity
             :sound {:name "minecraft:entity.blaze.hurt"}
             :source 5
             :entity-id blaze-eid
             :volume 1.0
             :pitch 1.0
             :seed 0}
        w (heard (playing) pkt)]
    (is (= [{:sound :entity.blaze.hurt :pos [20.5 64.0 0.5] :at 1000 :eid blaze-eid}]
           (:world/sounds w)))
    (testing "an entity not tracked cannot be placed, and is kept"
      (is (= [{:sound :entity.blaze.hurt :at 1000 :eid 7}]
             (:world/sounds (heard (playing) (assoc pkt :entity-id 7))))))))

(deftest a-level-event-is-named-and-kept-near-or-global
  (let [w (heard (playing)
                 {:packet/name :level-event :type 1018 :pos [12 66 0] :data 0 :global false})]
    (is (= [{:event :blaze-shoot :pos [12 66 0] :data 0 :at 1000}] (:world/level-events w)))
    (is (= 9999
           (:event
            (peek (:world/level-events
                   (heard
                    w
                    {:packet/name :level-event :type 9999 :pos [1 64 0] :data 1 :global false})))))
        "no name, no word: the number")
    (is (= 2001 (get (set (keys ear/level-events)) 2001)) "a block broken is named"))
  (is (nil? (:world/level-events
             (heard (playing)
                    {:packet/name :level-event :type 1018 :pos [200 66 0] :data 0 :global false}))))
  (is (= :dragon-death
         (:event (peek
                  (:world/level-events
                   (heard
                    (playing)
                    {:packet/name :level-event :type 1028 :pos [200 66 0] :data 0 :global true})))))
      "a global one is heard from anywhere"))

(deftest the-ledger-notes-what-was-heard-and-not-the-ambient-far-off
  (let [w0 (playing)
        shot (heard w0 {:packet/name :level-event :type 1018 :pos [30 66 0] :data 0 :global false})
        stare (heard w0 (sound-at :entity.enderman.stare [40 64 0]))
        far-ambient (heard w0 (sound-at :entity.blaze.ambient [40 64 0]))
        near-ambient (heard w0 (sound-at :entity.blaze.ambient [4 64 0]))
        broken (heard w0
                      {:packet/name :level-event :type 2001 :pos [30 64 0] :data 1 :global false})]
    (is (= [{:ev :level-event :event :blaze-shoot :d 29.6 :t 1000}] (senses/events w0 shot))
        "a shot is noted from afar")
    (is (= [{:ev :sound :sound :entity.enderman.stare :d 39.5 :t 1000}] (senses/events w0 stare)))
    (is (empty? (senses/events w0 far-ambient)) "far ambient noise would flood it")
    (is (= [{:ev :sound :sound :entity.blaze.ambient :d 3.5 :t 1000}]
           (senses/events w0 near-ambient)))
    (is (empty? (senses/events w0 broken)) "a block broken is only noted near")
    (testing "only the new ones: what the world already held is not told again"
      (is (empty? (senses/events shot (assoc shot :time/now 1050)))))
    (testing "a line names what was heard in the second it stands for"
      (is (= [:blaze-shoot] (senses/heard-since shot)))
      (is (empty? (senses/heard-since (assoc shot :time/now 5000)))))))
