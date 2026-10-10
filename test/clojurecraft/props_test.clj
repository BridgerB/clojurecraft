(ns clojurecraft.props-test
  "Properties of the reducer over generated inputs."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.packet :as p]
            [clojurecraft.spec]
            [clojurecraft.world :as world]))

(defn in-play []
  (first (fx/fold game/step (game/init fx/opts)
                  [{:event/kind :start} (fx/packet {:packet/name :login-finished})
                   (fx/packet {:packet/name :finish-configuration}) (fx/packet {:packet/name :login :entity-id 7})])))

(defn field-gen [t]
  (cond
    (and (vector? t) (vector? (first t))) (apply gen/hash-map (mapcat (fn [[k ft]] [k (field-gen ft)]) t))
    (vector? t) (gen/vector (field-gen (second t)) 0 3)
    :else (case t
            :bool gen/boolean :i8 (gen/choose -128 127) :u8 (gen/choose 0 255)
            :i16 (gen/choose -32768 32767) :u16 (gen/choose 0 65535)
            :i32 (gen/choose -2147483648 2147483647) :u32 (gen/choose 0 4294967295)
            :i64 gen/large-integer :f32 (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6})
            :f64 (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6})
            :varint (gen/choose -2147483648 2147483647) :varlong gen/large-integer
            :string gen/string :uuid (gen/return nil) :position (gen/vector (gen/choose -1000 1000) 3)
            :bytes gen/bytes :rest gen/bytes
            :hashed-slot (gen/one-of [(gen/return nil) (gen/hash-map :item (gen/choose 0 2000) :count (gen/choose 1 64))])
            :slot (gen/one-of [(gen/return nil) (gen/hash-map :item (gen/choose 0 2000) :count (gen/choose 1 64))]))))

(def s2c-play-packet
  (gen/one-of (for [[[state dir pkt-name] fields] p/specs :when (and (= state :play) (= dir :s2c))]
                (gen/fmap #(assoc % :packet/name pkt-name)
                          (apply gen/hash-map (mapcat (fn [[k t]] [k (field-gen t)]) fields))))))

(defn check [prop] (let [r (tc/quick-check 200 prop)] (is (:pass? r) (pr-str r))))

(deftest every-keep-alive-is-answered-once
  (check (prop/for-all [id gen/large-integer]
                       (let [[_ fx] (fx/fold game/step (in-play) [(fx/packet {:packet/name :keep-alive :id id})])]
                         (= [{:packet/name :keep-alive :id id}] (fx/packets fx))))))

(deftest step-is-total-over-decodable-play-packets
  (check (prop/for-all [pkts (gen/vector s2c-play-packet 1 20)]
                       (let [[w _] (fx/fold game/step (in-play) (map fx/packet pkts))]
                         (s/valid? :clojurecraft.spec/world w)))))

(deftest the-phase-only-moves-along-the-transition-table
  (check (prop/for-all [pkts (gen/vector s2c-play-packet 1 20)]
                       (let [[w fx] (fx/fold game/step (in-play) (map fx/packet pkts))
                             phases (reductions p/next-state :play (fx/names fx))]
                         (and (every? #{:play :configuration} phases)
                              (= (:bot/phase w) (last phases)))))))

(def log-id 136)
(def leaves-id 252)

(defn standing-by
  "In play on a stone floor at [5.5 64 5.5], at rest, with block id at [7 64 5] and a dig of it
   as the current intent."
  [id]
  (assoc (game/init fx/opts)
         :bot/phase :play :player/pos [5.5 64.0 5.5] :player/vel [0.0 -0.078 0.0] :player/loaded? true
         :player/on-ground? true :world/chunks {[0 0] (world/column {[7 64 5] id})}
         :plan/intent {:intent/kind :dig :intent/target [7 64 5] :intent/status :active}))

(defn dig-step
  "game/step, then one tick of the dig intent while it is active."
  [w e]
  (let [w (game/step w e)
        i (:plan/intent w)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status i))) (intent/run w i e) w)))

(defn uneven-ticks
  "Ticks at the given gaps (ms), then steady 50 ms ticks to 10 s so every dig can finish."
  [gaps]
  (let [ts (reductions + 0 gaps)]
    (for [t (concat ts (range (+ (last ts) 50) (+ (last ts) 10000) 50))]
      {:event/kind :tick :event/now t :event/rand 0.5})))

(deftest a-dig-never-finishes-before-its-deadline
  (check (prop/for-all [id (gen/elements [log-id leaves-id])
                        gaps (gen/vector (gen/choose 1 400) 0 60)]
                       (let [[_ fx] (fx/fold dig-step (standing-by id) (uneven-ticks gaps))
                             actions (for [[t p] (fx/sent fx) :when (= :player-action (:packet/name p))] [t (:status p)])
                             [[t0 s0] [t1 s1] :as all] actions]
                         (and (= 2 (count all)) (= [0 2] [s0 s1])
                              (>= (- t1 t0) (intent/finish-delay id))
                              (>= (- t1 t0) (intent/dig-time id)))))))
