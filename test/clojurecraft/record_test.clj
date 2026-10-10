(ns clojurecraft.record-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.record :as record]
            [clojurecraft.world :as world]))

(def events
  [{:event/kind :start :start/host "h" :start/port 25571 :start/name "Clj_rec"}
   (fx/packet fx/login-finished)
   (fx/packet {:packet/name :finish-configuration})
   (fx/packet {:packet/name :login :entity-id 7})
   (fx/packet {:packet/name :keep-alive :id 42})
   (fx/packet {:packet/name :disconnect :reason (byte-array [1 2 3])})
   {:event/kind :tick :event/now 50 :event/rand 0.25}])

(defn tmp [n] (str (System/getProperty "java.io.tmpdir") "/clojurecraft-" n ".edn"))

(defn record!
  "Write a recording the way the loop does: the event, then what applying it produced."
  [path evs]
  (let [tap (record/tap path)]
    (reduce (fn [w e] ((:write tap) e)
              (let [w (game/step w e)] ((:effects tap) (:bot/effects w)) (assoc w :bot/effects [])))
            (game/init fx/opts) evs)
    ((:close tap))))

(deftest a-result-may-gain-attributes-never-lose-or-change-them
  (let [r {:ok true :plan {:plan/status :done :plan/last {:intent/kind :dig}}}]
    (is (record/accretes? r r))
    (is (record/accretes? r (assoc-in r [:plan :plan/last :intent/trunk] [1 2 3])) "a new attribute is accretion")
    (is (not (record/accretes? r (assoc-in r [:plan :plan/status] :failed))) "a changed value is not")
    (is (not (record/accretes? r (update r :plan dissoc :plan/last))) "a lost attribute is not")))

(deftest a-recording-replays-to-the-same-world
  (let [path (tmp "record-test")]
    (record! path events)
    (let [[direct _] (fx/fold game/step (game/init fx/opts) events)
          replayed (record/replay game/step (game/init fx/opts) path)]
      (is (= 7 (count (record/events path))))
      (is (= (dissoc direct :bot/effects) (dissoc replayed :bot/effects)))
      (is (= "\u0001\u0002\u0003" (:bot/disconnected replayed))))))

(deftest effects-are-recorded-and-verified
  (let [path (tmp "record-effects")]
    (record! path events)
    (testing "effects line up with their events; events without effects get []"
      (let [fx (record/effects path)]
        (is (= (count (record/events path)) (count fx)))
        (is (= [:intention :hello] (mapv (comp :packet/name :effect/packet) (first fx))))
        (is (= [{:effect/kind :send :effect/packet {:packet/name :keep-alive :id 42}}] (nth fx 4)))
        (is (= [] (nth fx 5)))))
    (is (nil? (record/verify game/step (game/init fx/opts) path)) "the same events ask for the same effects")
    (is (nil? (record/verify game/step (game/init {}) path))
        "and the starting world needs no outside arguments: the :start event carries the connection")
    (testing "a different reducer is caught at the first differing event"
      (let [lazy-step (fn [w e] (if (= :keep-alive (get-in e [:event/packet :packet/name])) w (game/step w e)))
            m (:record/mismatch (record/verify lazy-step (game/init fx/opts) path))]
        (is (= 4 (:index m)))
        (is (= [] (:replayed m)))))))

(deftest an-old-recording-still-reads
  (let [path (tmp "record-old")]
    (spit path (apply str (map #(str (pr-str %) "\n") events)))
    (is (= 7 (count (record/events path))))
    (is (nil? (record/effects path)))
    (is (= :record/no-effects (record/verify game/step (game/init fx/opts) path)))))

(deftest a-chunk-decoded-on-the-reader-thread-is-recorded-as-wire-bytes
  (let [data (world/column-bytes {[3 64 3] 136})
        wire (fx/packet {:packet/name :level-chunk-with-light :x 0 :z 0 :heightmaps [] :data data})
        attached (update wire :event/packet chunk/attach)
        in-play (first (fx/fold game/step (game/init fx/opts)
                                (take 4 events)))
        path (tmp "record-chunk")]
    (testing "the reducer reaches the same world from the attached column or the raw bytes"
      (let [a (first (fx/fold game/step in-play [attached]))
            r (first (fx/fold game/step in-play [wire]))]
        (is (= (dissoc a :world/chunks) (dissoc r :world/chunks)) "same facts, stats and everything else")
        (is (= [136 136] [(terrain/block-at a [3 64 3]) (terrain/block-at r [3 64 3])]) "the same column")))
    (testing "the recording keeps the bytes and drops the derived column, and replays identically"
      (let [tap (record/tap path)]
        (reduce (fn [w e] ((:write tap) e)
                  (let [w (game/step w e)] ((:effects tap) (:bot/effects w)) (assoc w :bot/effects [])))
                (game/init fx/opts) (concat (take 4 events) [attached]))
        ((:close tap)))
      (let [recorded (:event/packet (last (record/events path)))]
        (is (not (contains? recorded :chunk/column)))
        (is (= (seq data) (seq (:data recorded)))))
      (is (nil? (record/verify game/step (game/init fx/opts) path))))))

(deftest a-recording-carries-its-result-and-a-replay-reaches-it
  (let [path (tmp "record-result")
        tap (record/tap path)
        w (reduce (fn [w e] ((:write tap) e)
                    (let [w (main/step w e)] ((:effects tap) (:bot/effects w)) (assoc w :bot/effects [])))
                  (game/init fx/opts) events)
        r (main/result w "play" (main/ok? w "play"))]
    ((:result tap) r)
    ((:close tap))
    (is (= r (record/recorded-result path)) "the file says what the run printed")
    (let [replayed (main/replayed path)]
      (is (= :identical (:replay/result replayed)) "folding the file reaches exactly that RESULT")
      (is (= :identical (:replay/effects replayed))))))
