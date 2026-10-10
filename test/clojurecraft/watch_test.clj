(ns clojurecraft.watch-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.watch :as watch]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(deftest changes-are-a-pure-diff
  (let [a {:time/now 0 :player/pos [0.0 64.0 0.0] :player/held-slot 0 :plan/waiting :no-log
           :stats/unknown {[:play 1] 3 [:play 2] 1}}
        b {:time/now 50 :player/pos [0.5 64.0 0.0] :player/held-slot 0 :stats/unknown {[:play 1] 4 [:play 2] 1}}]
    (is (= {:telemetry/changed {:player/pos [0.5 64.0 0.0] :plan/waiting nil}
            :telemetry/patched {:stats/unknown {[:play 1] 4}}}
           (watch/changes a b))
        "what moved, what vanished (nil), only the map entry that ticked; never the clock")
    (is (nil? (watch/line a a)))
    (is (= 50 (:telemetry/at (watch/line a b))))))

(deftest telemetry-watches-a-whole-run-without-touching-it
  (let [path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-telemetry.edn")
        step (game/compose game/step plan/step)
        column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 252})
        world* (atom (game/init fx/opts))
        watcher (watch/telemetry! world* path)
        ;; drive the atom the way main does: one swap per event
        driven (fn [w e] (reset! world* (step w e)))
        [w _] (sim/run driven @world* (sim/init {:column column :spawn [0.5 64.0 0.5]})
                       #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})
        _ ((:close watcher))
        lines (with-open [r (io/reader path)] (mapv edn/read-string (line-seq r)))]
    (is (plan/done? w))
    (is (zero? ((:dropped watcher))))
    (testing "the file tells the run's story"
      (is (some #(= :done (get-in % [:telemetry/changed :plan/status])) lines))
      (is (some #(contains? (:telemetry/patched %) :player/inventory) lines) "the log arriving in the inventory")
      (is (every? #(or (seq (:telemetry/changed %)) (seq (:telemetry/patched %))) lines)))
    (testing "the same run without a watcher reaches the same world"
      (let [[w' _] (sim/run step (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
                            #(or (plan/done? %) (plan/failed? %)) 60000 {:event/kind :go :go/goals [:wood]})]
        (is (= (dissoc w :world/chunks) (dissoc w' :world/chunks)))))))
