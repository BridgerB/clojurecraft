(require '[clojure.string :as str]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace])

(defn out [& xs] (println (str/join " " xs)))

(def since (parse-long (first *command-line-args*)))

(doseq [p (rest *command-line-args*)]
  (try (out "==" p)
       (let [snap (capture/read-world p)
             w (simrace/world-for! snap)
             {:keys [world] :as m} (simrace/moment-of snap w)
             n (volatile! 0)
             last-i (volatile! nil)
             step (fn [wd e]
                    (let [wd' (simrace/step wd e)
                          t (:time/now wd')]
                      (when (and (>= t since) (< @n 200))
                        (doseq [{:effect/keys [kind message]} (:bot/effects wd')
                                :when (= :log kind)]
                          (vswap! n inc)
                          (out "LOG" t message))
                        (let [i (:plan/intent wd')
                              k (select-keys i [:intent/kind :intent/stage :intent/turns])]
                          (when (not= k @last-i)
                            (vreset! last-i k)
                            (vswap! n inc)
                            (out "INTENT"
                                 t
                                 (pr-str k)
                                 "feet" (pr-str (intent/feet wd'))
                                 "boxed-at" (pr-str (:plan/boxed-at wd'))))))
                      wd'))
             stop? #(or (plan/failed? %) (plan/done? %))]
         (out "START feet" (pr-str (intent/feet world)))
         (sim/play-from step
                        m
                        stop?
                        (simrace/window-of snap)
                        (simrace/go-of w (:snapshot/until snap))
                        nil))
       (catch Exception e (out "THREW" (ex-message e)))))

(shutdown-agents)
