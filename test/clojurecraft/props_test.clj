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
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as p]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.spec]
            [clojurecraft.swim]
            [clojurecraft.trap :as trap]
            [clojurecraft.world :as world]))

(defn in-play
  []
  (first (fx/fold game/step
                  (game/init fx/opts)
                  [{:event/kind :start} (fx/packet fx/login-finished)
                   (fx/packet {:packet/name :finish-configuration})
                   (fx/packet {:packet/name :login :entity-id 7})])))

(defn field-gen
  [t]
  (cond (and (vector? t) (vector? (first t)))
        (apply gen/hash-map (mapcat (fn [[k ft]] [k (field-gen ft)]) t))
        (vector? t) (gen/vector (field-gen (second t)) 0 3)
        :else
        (case t
          :bool gen/boolean
          :i8 (gen/choose -128 127)
          :u8 (gen/choose 0 255)
          :i16 (gen/choose -32768 32767)
          :u16 (gen/choose 0 65535)
          :i32 (gen/choose -2147483648 2147483647)
          :u32 (gen/choose 0 4294967295)
          :i64 gen/large-integer
          :f32 (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6})
          :f64 (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6})
          :varint (gen/choose -2147483648 2147483647)
          :varlong gen/large-integer
          :string gen/string
          :uuid (gen/return nil)
          :position (gen/vector (gen/choose -1000 1000) 3)
          :bytes gen/bytes
          :rest gen/bytes
          :hashed-slot
          (gen/one-of [(gen/return nil)
                       (gen/hash-map :item (gen/choose 0 2000) :count (gen/choose 1 64))])
          :slot
          (gen/one-of [(gen/return nil)
                       (gen/hash-map :item (gen/choose 0 2000) :count (gen/choose 1 64))]))))

(def s2c-play-packet
  (gen/one-of (for [[[state dir pkt-name] fields] p/specs
                    :when (and (= state :play) (= dir :s2c))]
                (gen/fmap #(assoc % :packet/name pkt-name)
                          (apply gen/hash-map (mapcat (fn [[k t]] [k (field-gen t)]) fields))))))

(defn check [prop] (let [r (tc/quick-check 200 prop)] (is (:pass? r) (pr-str r))))

(deftest every-keep-alive-is-answered-once
  (check (prop/for-all [id gen/large-integer]
                       (let [[_ fx] (fx/fold game/step
                                             (in-play)
                                             [(fx/packet {:packet/name :keep-alive :id id})])]
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
          :bot/phase :play
          :player/pos [5.5 64.0 5.5]
          :player/vel [0.0 -0.078 0.0]
          :player/loaded? true
          :player/on-ground? true
          :player/inventory inv
          :world/chunks {[0 0] (world/column {[7 64 5] id})}
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
     (for [t (concat ts
                     (range (+ (last ts) physics/tick-ms) (+ (last ts) horizon) physics/tick-ms))]
       {:event/kind :tick :event/now t :event/rand 0.5}))))

(deftest a-dig-never-finishes-before-its-deadline
  (check (prop/for-all
          [id (gen/elements [log-id leaves-id]) gaps (gen/vector (gen/choose 1 400) 0 60)]
          (let [[_ fx] (fx/fold dig-step (standing-by id) (uneven-ticks gaps))
                actions
                (for [[t p] (fx/sent fx) :when (= :player-action (:packet/name p))] [t (:status p)])
                [[t0 s0] [t1 s1] :as all] actions]
            (and (= 2 (count all))
                 (= [0 2] [s0 s1])
                 (>= (- t1 t0) (intent/finish-delay (intent/dig-time id)))
                 (>= (- t1 t0) (intent/dig-time id)))))))

(def breakable-states
  "Every state id of a block with a hardness above zero: what a dig may be asked for."
  (vec
   (for [[n _ lo _] blocks/table :let [h (blocks/hardness-by-name n)] :when (and h (pos? h))] lo)))

(def holdable-items "Every tool item id, and the hand." (into [nil] (keys blocks/tools)))

(deftest a-dig-with-any-tool-on-any-block-keeps-the-rules
  ;; issue #9: for any breakable block, any held item and any tick sequence, FINISH is never
  ;; before 1.15 x dig/ms + 100 after START; a block that needs a tier gets no START unless a
  ;; held tool of its own kind reaches the tier (the game drops by tier alone, but the dig
  ;; chooses only tools of the block's kind, so an off-kind tool is refused rather than spent on
  ;; a slow dig); and FINISH's sequence is START's plus one
  (check
   (prop/for-all
    [id (gen/elements breakable-states) held (gen/elements holdable-items) gaps
     (gen/vector (gen/choose 1 400) 0 40)]
    (let [inv (if held {3 {:item held :count 1}} {})
          tool (second (dig/best-tool id inv {})) ; the dig's own choice: nil is the hand
          ms (or (dig/ms id tool {}) intent/dig-ms)
          [w fx] (fx/fold dig-step
                          (standing-by id inv)
                          (uneven-ticks gaps (+ (intent/finish-delay ms) 2000)))
          actions (for [[t p] (fx/sent fx) :when (= :player-action (:packet/name p))] [t p])
          [[t0 start] [t1 finish]] actions
          unreachable? (and (blocks/needs-tier id) (nil? tool) (not (dig/harvest? id nil)))]
      (cond (intent/open? id)
            (and (empty? actions) (= :target-gone (:intent/reason (:plan/intent w))))
            unreachable? (and (empty? actions) (= :needs-tool (:intent/reason (:plan/intent w))))
            :else
            (and (= 2 (count actions))
                 (= 0 (:status start))
                 (= 2 (:status finish))
                 (>= (- t1 t0) (intent/finish-delay ms))
                 (= (:sequence finish) (inc (:sequence start)))))))))

(deftest an-awkward-block-is-dug-not-taken-for-open
  ;; sim race 38665691522 world R3/43 (issue #91): the cast's way to its landing dug an awkward
  ;; block over the feet, and the dig failed :target-gone; one that is neither solid nor clear
  ;; is broken like rock
  (let [awkward (filter #(and (not (blocks/solid? %))
                              (= :awkward (path/classify-id %))
                              (not (and (blocks/needs-tier %) (not (dig/harvest? % nil))))
                              (not (contains? intent/never-dug (blocks/name-of %))))
                        breakable-states)]
    (is (seq awkward) "there are such blocks")
    (doseq [id (take 20 awkward)]
      (let [[w fx] (fx/fold dig-step (standing-by id) (uneven-ticks [] 2000))]
        (is (not= :target-gone (:intent/reason (:plan/intent w))) (blocks/name-of id))
        (is (some #(= :player-action (:packet/name (second %))) (fx/sent fx))
            (str (blocks/name-of id) " START sent"))))))

(deftest step-is-total-after-a-death
  ;; issue #5: a set-health of 0 followed by any packets never throws, and the intent that was
  ;; running is gone (a new life plans afresh)
  (check
   (prop/for-all
    [pkts (gen/vector s2c-play-packet 0 20)]
    (let [w0 (assoc (in-play)
                    :plan/intent {:intent/kind :walk :intent/target [1 64 1]}
                    :plan/last {:intent/kind :walk :intent/target [1 64 1]})
          [w fx] (fx/fold game/step
                          w0
                          (cons (fx/packet
                                 {:packet/name :set-health :health 0.0 :food 20 :saturation 5.0})
                                (map fx/packet pkts)))]
      (and (s/valid? :clojurecraft.spec/world w)
           (not (contains? w :plan/intent))
           (not (contains? w :plan/last))
           (some
            #(= % {:packet/name :client-command :action (p/value :client-command :perform-respawn)})
            (fx/packets fx)))))))

(def pond-column
  "A pond two deep around the player (x 4-6, z 4-6) with a bank all round at y 64."
  (world/column (into {}
                      (for [x (range 4 7) y [62 63] z (range 4 7)] [[x y z] blocks/water-base]))))

(defn afloat-with
  "Planning for :wood with intent i running, the eye under water for one tick short of a
   trap; physics is not running (not loaded), so what the physics would overwrite stays put."
  [i]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [5.5 62.0 5.5]
         :player/vel [0.0 0.0 0.0]
         :player/look [0.0 0.0]
         :player/on-ground? false
         :player/in-water? true
         :player/eye-in-water? true
         :world/chunks {[0 0] pond-column}
         :plan/status :active
         :plan/since 0
         :plan/blacklist #{}
         :plan/trunk-failures {}
         :plan/attempts 0
         :plan/goals #{:wood}
         :plan/wet-ticks (dec trap/trap-after-ticks)
         :plan/intent (assoc i :intent/status :active :intent/id 1 :intent/goal :wood)
         :plan/last {:intent/kind :walk :intent/target (:intent/target i)}
         :player/controls {:control/forward? true :control/look [33.0 1.0]}))

(def running-intent
  (gen/let [kind (gen/elements [:walk :dig])
            target (gen/tuple (gen/choose 0 15) (gen/choose 60 70) (gen/choose 0 15))]
    {:intent/kind kind :intent/target target}))

(deftest water-over-the-eye-preempts-whatever-runs
  ;; issue #5: a running walk or dig yields to :swim-out the tick the trap is called, the old
  ;; intent is abandoned :preempted, and its controls are gone
  (check (prop/for-all [i running-intent]
                       (let [[w _] (fx/fold plan/step
                                            (afloat-with i)
                                            [{:event/kind :tick :event/now 1000 :event/rand 0.5}])
                             abandoned (filter #(= :abandoned (:intention/event %))
                                               (memory/intentions w))]
                         (and (= :swim-out (get-in w [:plan/intent :intent/kind]))
                              (= :water (:plan/trap w))
                              (= [(:intent/kind i)] (map :intention/kind abandoned))
                              (= [:preempted] (map :intention/reason abandoned))
                              (not (contains? w :plan/last))
                              (not= [33.0 1.0] (get-in w [:player/controls :control/look])))))))

;; ---------------------------------------------------------------- the furnace window

(def furnace-window 3)                ; the open furnace's window id in these properties

(def window-item
  (gen/one-of [(gen/return nil)
               (gen/let [n (gen/elements [:raw_iron :coal :iron_ingot :oak_planks])
                         c (gen/choose 1 64)]
                 {:item (blocks/items n) :count c})]))

(def furnace-packet
  "Anything the server may say about the open furnace, in any order: the whole window, one slot,
   the cursor, a property."
  (gen/one-of
   [(gen/let [s (gen/choose 1 1000)
              items (gen/vector window-item 39)
              c window-item]
      {:packet/name :container-set-content
       :window-id furnace-window
       :state-id s
       :items items
       :carried c})
    (gen/let [s (gen/choose 1 1000)
              slot (gen/choose 0 38)
              it window-item]
      {:packet/name :container-set-slot :window-id furnace-window :state-id s :slot slot :item it})
    (gen/fmap (fn [it] {:packet/name :set-cursor-item :item it}) window-item)
    (gen/let [p (gen/choose 0 3)
              v (gen/choose 0 1600)]
      {:packet/name :container-set-data :window-id furnace-window :property p :value v})]))

(def furnace-events
  "Ticks 50 ms apart with furnace packets between them, then 20 s of ticks."
  (gen/fmap (fn [xs]
              (second (reduce (fn [[t evs] x]
                                (if (= :tick x)
                                  [(+ t physics/tick-ms)
                                   (conj evs {:event/kind :tick :event/now t :event/rand 0.5})]
                                  [t (conj evs (fx/packet x))]))
                              [1000 []]
                              (concat xs (repeat 400 :tick)))))
            (gen/vector (gen/one-of [(gen/return :tick) furnace-packet]) 0 120)))

(def held-for-smelting
  "Raw iron, coal and planks in the main inventory, any of them maybe absent."
  (gen/fmap (fn [counts]
              (into {}
                    (for [[slot [n c]] (map vector (range 9 12) counts)
                          :when (pos? c)]
                      [slot {:item (blocks/items n) :count c}])))
            (gen/tuple (gen/tuple (gen/return :raw_iron) (gen/choose 0 9))
                       (gen/tuple (gen/return :coal) (gen/choose 0 2))
                       (gen/tuple (gen/return :oak_planks) (gen/choose 0 9)))))

(defn at-a-furnace
  "In play with the furnace window open (empty, state id 1), inv held, and a smelt of n raw iron
   running."
  [inv n]
  (assoc (in-play)
         :player/inventory inv
         :window/open
         {:window/id furnace-window
          :window/menu-type (blocks/menus :furnace)
          :window/slots {}
          :window/state-id 1}
         :plan/intent
         {:intent/kind :smelt
          :intent/smelt :iron_ingot_from_smelting_raw_iron
          :intent/count n
          :intent/status :active}))

(defn smelt-ticks
  "Fold events through game/step and one tick of the smelt while it is active: for every tick it
   ran, the cursor it saw and the packets it sent."
  [w events]
  (second (reduce (fn [[w seen] e]
                    (let [w (assoc (game/step w e) :bot/effects [])
                          i (:plan/intent w)]
                      (if (and (= :tick (:event/kind e)) (= :active (:intent/status i)))
                        (let [w' (intent/run w i e)]
                          [(assoc w' :bot/effects [])
                           (conj seen
                                 [(:window/cursor w)
                                  (for [{:effect/keys [kind packet]} (:bot/effects w')
                                        :when (= :send kind)]
                                    packet)])])
                        [w seen])))
                  [w []]
                  events)))

(deftest the-smelt-never-closes-with-a-loaded-cursor-or-clicks-outside-the-window
  ;; issue #6: whatever the server says about the furnace, and in whatever order, the :smelt
  ;; intent closes it only with nothing on the cursor (vanilla would drop the item) and clicks
  ;; only slots the furnace window has
  (check (prop/for-all [inv held-for-smelting n (gen/choose 1 8) events furnace-events]
                       (let [slots (inventory/slot-count (inventory/layouts :furnace))]
                         (every? (fn [[cursor pkts]]
                                   (every? (fn [p]
                                             (case (:packet/name p)
                                               :container-close (nil? cursor)
                                               :container-click
                                               (and (= furnace-window (:window-id p))
                                                    (< -1 (:slot p) slots))
                                               true))
                                           pkts))
                                 (smelt-ticks (at-a-furnace inv n) events))))))
