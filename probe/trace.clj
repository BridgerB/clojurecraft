(require '[clojure.math :as math]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan])

(defn brief
  [i]
  (select-keys i
               [:intent/id :intent/kind :intent/for :intent/stage :intent/target :intent/to-y
                :intent/status :intent/reason :intent/bank :intent/dig-when-down :intent/shore
                :intent/no-lay :intent/child :intent/niche :intent/origin :intent/item]))

(def every (Long/parseLong (or (System/getenv "EVERY") "40")))

(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (or (simrace/own-world snap)
                 (simrace/prepared (capture/read-world (:snapshot/file snap))))
          m (simrace/moment-of snap w0)
          n (atom 0)
          k (atom 0)
          prev (atom nil)
          observe (fn [world]
                    (let [i (:plan/intent world)
                          cur [(:intent/id i) (:intent/kind i) (:intent/stage i)]
                          tick (swap! k inc)]
                      (when (and (< @n 400) (or (not= cur @prev) (zero? (mod tick every))))
                        (swap! n inc)
                        (println (:time/now world)
                                 (mapv #(/ (math/round (* 10 (double %))) 10.0) (:player/pos world))
                                 "w"
                                 (:player/in-water? world)
                                 "g"
                                 (:player/on-ground? world)
                                 (pr-str (:player/controls world))
                                 (pr-str (brief i)))
                        (reset! prev cur))
                      (or (plan/failed? world) (plan/done? world))))]
      (println "==" p (pr-str (:snapshot/signature snap)))
      (sim/play-from simrace/step
                     m
                     observe
                     (simrace/window-of snap)
                     (simrace/go-of w0 (:snapshot/until snap))
                     nil))
    (catch Exception e (println "==" p "THREW" (.getName (class e)) (ex-message e)))))
(shutdown-agents)
