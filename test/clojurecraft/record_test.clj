(ns clojurecraft.record-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.record :as record]))

(def events
  [{:event/kind :start :start/host "h" :start/port 25571 :start/name "Clj_rec"}
   (fx/packet {:packet/name :login-finished})
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
