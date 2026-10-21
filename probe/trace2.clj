(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan])

(def start (Long/parseLong (or (System/getenv "START") "0")))

(defn child-brief
  [c]
  (select-keys c
               [:intent/kind :intent/target :intent/cell :intent/stage :intent/status :intent/reason
                :intent/rejections :intent/confirm-left :intent/item]))

(doseq [p *command-line-args*]
  (try (let [snap (capture/read-world p)
             w0 (or (simrace/own-world snap)
                    (simrace/prepared (capture/read-world (:snapshot/file snap))))
             m (simrace/moment-of snap w0)
             n (atom 0)
             prev (atom nil)
             observe (fn [world]
                       (let [i (:plan/intent world)
                             cur [(:intent/id i) (:intent/status i) (:intent/rejections i)
                                  (child-brief (:intent/child i))]]
                         (when (and (>= (:time/now world) start) (< @n 300) (not= cur @prev))
                           (swap! n inc)
                           (println (:time/now world)
                                    (mapv double (:player/pos world))
                                    (pr-str (select-keys i
                                                         [:intent/id :intent/kind :intent/status
                                                          :intent/reason :intent/rejections]))
                                    (pr-str (child-brief (:intent/child i))))
                           (reset! prev cur))
                         (or (plan/failed? world) (plan/done? world))))]
         (println "==" p)
         (sim/play-from simrace/step
                        m
                        observe
                        (simrace/window-of snap)
                        (simrace/go-of w0 (:snapshot/until snap))
                        nil))
       (catch Exception e (println "==" p "THREW" (ex-message e)))))
(shutdown-agents)
