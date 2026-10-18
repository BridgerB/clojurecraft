(ns probe.flint-long
  "Issue #105: do the snapshots go on to the flint and steel when judged over fifteen simulated
   minutes, not the window the moment was kept with? Prints the ending and the story's tail."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.simrace :as simrace]))

(defn long-judge
  [path]
  (let [t0 (System/currentTimeMillis)
        snap (-> (capture/read-world path)
                 (assoc-in [:snapshot/moment :window-ms] 900000))
        w (simrace/world-for! snap)
        r (simrace/judged snap w)]
    (println "LONG"
             path
             (pr-str {:signature (:run/signature r)
                      :ok (:ok r)
                      :reason (:reason r)
                      :sim-ms (- (:run/ms r) (:run/from r))
                      :wall-ms (- (System/currentTimeMillis) t0)
                      :held (:held r)}))
    (doseq [line (take-last 40 (:run/story r))] (println "STORY" (pr-str line)))))

(doseq [p ["test/issues/105/b10180130-R1-31-1.edn.gz" "test/issues/105/b10180130-R3-44-2.edn.gz"]]
  (long-judge p))
