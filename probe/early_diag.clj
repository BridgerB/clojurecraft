(ns probe.early-diag
  "Prints the bot's own log lines over each snapshot's judged window."
  (:require [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.simrace :as sr]))

(defn -main
  [& paths]
  (doseq [path paths]
    (let [snap (capture/read-world path)
          w (sr/world-for! snap)
          lines (atom [])
          logged (fn [world event]
                   (let [w' (sr/step world event)]
                     (doseq [e (:bot/effects w')
                             :when (= :log (:effect/kind e))]
                       (swap! lines conj [(:time/now w') (:effect/message e)]))
                     w'))
          r (with-redefs [sr/step logged] (sr/judged snap w))]
      (println "DIAG" path (pr-str (:run/signature r)))
      (doseq [[t m] (distinct @lines)] (println "LOG" path t m))))
  (shutdown-agents))
