(require '[clojure.string :as str]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace])

(defn out [& xs] (println (str/join " " xs)))

(doseq [p *command-line-args*]
  (try (out "==" p)
       (let [snap (capture/read-world p)
             w (simrace/world-for! snap)
             {:keys [world] :as m} (simrace/moment-of snap w)
             n (volatile! 0)
             last-i (volatile! nil)
             step (fn [wd e]
                    (let [wd' (simrace/step wd e)]
                      (doseq [{:effect/keys [kind message]} (:bot/effects wd')
                              :when (and (= :log kind) (< @n 150))]
                        (vswap! n inc)
                        (out "LOG" (:time/now wd') message))
                      (let [i (:plan/intent wd')
                            k (select-keys i
                                           [:intent/kind :intent/id :intent/stage :intent/turns
                                            :intent/level-steps])]
                        (when (and (not= k @last-i) (< @n 150))
                          (vreset! last-i k)
                          (vswap! n inc)
                          (out "INTENT"
                               (:time/now wd')
                               (pr-str k)
                               "feet" (pr-str (intent/feet wd'))
                               "boxed-at" (pr-str (:plan/boxed-at wd')))))
                      wd'))
             stop? #(or (clojurecraft.plan/failed? %) (clojurecraft.plan/done? %))]
         (out "START feet" (pr-str (intent/feet world)) "inv" (pr-str (:player/inventory world)))
         (out "boxed-at" (pr-str (:plan/boxed-at world)) "last" (pr-str (:plan/last world)))
         (sim/play-from step
                        m
                        stop?
                        (simrace/window-of snap)
                        (simrace/go-of w (:snapshot/until snap))
                        nil))
       (catch Exception e (out "THREW" (ex-message e)))))
(shutdown-agents)
