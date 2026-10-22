;; Issue #279, real-2-s2-Clj_r18: which :build fails :rejected, and its failing child.
;; Plays the snapshot as simrace/judged does and prints each change of the running intent and
;; its child from the first build after the craft (ms 1792690078846 on).
;;   clojure -M probe/p279_build.clj <snapshot.edn.gz>
(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.scene :as scene]
         '[clojurecraft.plan :as plan])

(let [snap (capture/read-world (first *command-line-args*))
      w (simrace/own-world snap)
      {:keys [world] :as m} (simrace/moment-of snap w)
      stage (simrace/stage-of snap)
      seen (atom nil)
      stop? (fn [wd]
              (let [i (:plan/intent wd)
                    c (:intent/child i)
                    k [(:intent/kind i) (:intent/status i) (:intent/kind c) (:intent/status c)
                       (:intent/reason c) (:intent/reason i)]]
                (when (and (>= (:time/now wd) 1792690078846) (not= k @seen))
                  (reset! seen k)
                  (println "AT" (:time/now wd) "feet" (mapv long (:player/pos wd)))
                  (println "  INTENT" (pr-str (scene/brief-map i)))
                  (println "  CHILD" (pr-str (scene/brief-map c)))
                  (println "  KEYS" (pr-str (keys i)))))
              (or (plan/done? wd) (plan/failed? wd) (contains? (:plan/reached wd) stage)))]
  (sim/play-from simrace/step
                 m
                 stop?
                 (simrace/window-of snap)
                 (simrace/go-of w (:snapshot/until snap))
                 nil)
  (println "END"))
