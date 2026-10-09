(ns clojurecraft.record-test
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.record :as record]))

(deftest a-recording-replays-to-the-same-world
  (let [path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-record-test.edn")
        events [{:event/kind :start}
                (fx/packet {:packet/name :login-finished})
                (fx/packet {:packet/name :finish-configuration})
                (fx/packet {:packet/name :login :entity-id 7})
                (fx/packet {:packet/name :disconnect :reason (byte-array [1 2 3])})
                {:event/kind :tick :event/now 50 :event/rand 0.25}]
        tap (record/tap path)]
    (doseq [e events] ((:write tap) e))
    ((:close tap))
    (let [[direct _] (fx/fold game/step (game/init fx/opts) events)
          replayed (record/replay game/step (game/init fx/opts) path)]
      (is (= 6 (count (record/events path))))
      (is (= (dissoc direct :bot/effects) (dissoc replayed :bot/effects)))
      (is (= "\u0001\u0002\u0003" (:bot/disconnected replayed))))))
