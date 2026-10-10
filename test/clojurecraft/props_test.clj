(ns clojurecraft.props-test
  "Properties of the reducer over generated inputs."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.packet :as p]
            [clojurecraft.spec]
            [clojurecraft.world :as world]))

(defn in-play []
  (first (fx/fold game/step (game/init fx/opts)
                  [{:event/kind :start} (fx/packet fx/login-finished)
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
  "In play on a stone floor at [5.5 64 5.5], at rest, with block id at [7 64 5], inv as the
   player inventory, and a dig of it as the current intent."
  ([id] (standing-by id {}))
  ([id inv]
   (assoc (game/init fx/opts)
          :bot/phase :play :player/pos [5.5 64.0 5.5] :player/vel [0.0 -0.078 0.0] :player/loaded? true
          :player/on-ground? true :player/inventory inv :world/chunks {[0 0] (world/column {[7 64 5] id})}
          :plan/intent {:intent/kind :dig :intent/target [7 64 5] :intent/status :active})))

(defn dig-step
  "game/step, then one tick of the dig intent while it is active."
  [w e]
  (let [w (game/step w e)
        i (:plan/intent w)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status i))) (intent/run w i e) w)))

(defn uneven-ticks
  "Ticks at the given gaps (ms), then steady 50 ms ticks for horizon ms (10 s by default) so
   every dig can finish."
  ([gaps] (uneven-ticks gaps 10000))
  ([gaps horizon]
   (let [ts (reductions + 0 gaps)]
     (for [t (concat ts (range (+ (last ts) 50) (+ (last ts) horizon) 50))]
       {:event/kind :tick :event/now t :event/rand 0.5}))))

(deftest a-dig-never-finishes-before-its-deadline
  (check (prop/for-all [id (gen/elements [log-id leaves-id])
                        gaps (gen/vector (gen/choose 1 400) 0 60)]
                       (let [[_ fx] (fx/fold dig-step (standing-by id) (uneven-ticks gaps))
                             actions (for [[t p] (fx/sent fx) :when (= :player-action (:packet/name p))] [t (:status p)])
                             [[t0 s0] [t1 s1] :as all] actions]
                         (and (= 2 (count all)) (= [0 2] [s0 s1])
                              (>= (- t1 t0) (intent/finish-delay (intent/dig-time id)))
                              (>= (- t1 t0) (intent/dig-time id)))))))

(def breakable-states
  "Every state id of a block with a hardness above zero: what a dig may be asked for."
  (vec (for [[n _ lo _] blocks/table :let [h (blocks/hardness-by-name n)] :when (and h (pos? h))] lo)))

(def holdable-items
  "Every tool item id, and the hand."
  (into [nil] (keys blocks/tools)))

(deftest a-dig-with-any-tool-on-any-block-keeps-the-rules
  ;; issue #9: for any breakable block, any held item and any tick sequence, FINISH is never
  ;; before 1.35 x dig/ms + 200 after START; a block that needs a tier gets no START unless a
  ;; held tool of its own kind reaches the tier (the game drops by tier alone, but the dig
  ;; chooses only tools of the block's kind, so an off-kind tool is refused rather than spent on
  ;; a slow dig); and FINISH's sequence is START's plus one
  (check (prop/for-all [id (gen/elements breakable-states)
                        held (gen/elements holdable-items)
                        gaps (gen/vector (gen/choose 1 400) 0 40)]
                       (let [inv (if held {3 {:item held :count 1}} {})
                             tool (second (dig/best-tool id inv {}))             ; the dig's own choice: nil is the hand
                             ms (or (dig/ms id tool {}) intent/dig-ms)
                             [w fx] (fx/fold dig-step (standing-by id inv) (uneven-ticks gaps (+ (intent/finish-delay ms) 2000)))
                             actions (for [[t p] (fx/sent fx) :when (= :player-action (:packet/name p))] [t p])
                             [[t0 start] [t1 finish]] actions
                             unreachable? (and (blocks/needs-tier id) (nil? tool) (not (dig/harvest? id nil)))]
                         (cond
                           (not (blocks/solid? id)) (and (empty? actions) (= :target-gone (:intent/reason (:plan/intent w))))
                           unreachable? (and (empty? actions) (= :needs-tool (:intent/reason (:plan/intent w))))
                           :else (and (= 2 (count actions)) (= 0 (:status start)) (= 2 (:status finish))
                                      (>= (- t1 t0) (intent/finish-delay ms))
                                      (= (:sequence finish) (inc (:sequence start)))))))))
