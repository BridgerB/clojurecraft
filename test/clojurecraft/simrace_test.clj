(ns clojurecraft.simrace-test
  "The sim race: a run over a world of many columns crosses from one to the next as the game's
   do, a run is the same run every time, and failures group by what they were doing and why."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def oak-log 137)
(def oak-leaves 252)

(def two-columns
  "A world of two columns: bare stone floor where the bot lands (chunk [0 0]), and the only tree
   one column east (chunk [1 0], its trunk at x 20)."
  (simrace/prepared {:capture/set "test"
                     :capture/landing [8 8 64]
                     :capture/radius 1
                     :capture/columns {[0 0] (world/column-bytes {})
                                       [1 0] (world/column-bytes (merge (into {}
                                                                              (for [y (range 64 68)]
                                                                                [[4 y 8] oak-log]))
                                                                        {[4 68 8] oak-leaves}))}}))

(deftest a-run-walks-into-the-next-column-for-its-log
  (let [r (simrace/run two-columns 1 "wood" 120000)]
    (is (:ok r) (pr-str (dissoc r :plan :stats)))
    (is (= 1 (get-in r [:held :oak_log])))
    (is (= 2 (:chunks r)) "the whole world, both columns, sent at login and kept")
    (is (empty? (:run/violations r)))))

(deftest a-run-is-the-same-run-every-time
  (is (= (simrace/run two-columns 7 "wood" 120000) (simrace/run two-columns 7 "wood" 120000))))

(deftest a-failure-is-signed-by-its-target-its-reason-and-its-intent
  (let [r {:until "nether"
           :reason :no-pickaxe
           :plan {:plan/reached #{:wood :kit :pickaxe :cobblestone :stone-pickaxe :furnace}
                  :plan/last {:intent/kind :mine}}}]
    (is (= :ingots (simrace/stage "nether" (get-in r [:plan :plan/reached]))))
    (is (= [:ingots :no-pickaxe :mine] (simrace/signature r)))
    (is (= #:simrace{:runs 3 :ok 1 :failures [[[:ingots :no-pickaxe :mine] 2]]}
           (simrace/tally [r r {:ok true}])))))
