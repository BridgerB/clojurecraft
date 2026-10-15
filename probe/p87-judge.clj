(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(.mkdirs (io/file "out"))
(defn out
  "One line on stdout and into out/probe.log."
  [& xs]
  (let [s (str/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/prepared (capture/read-world (:snapshot/file snap)))
        played (simrace/judged snap w)]
    (out "==" p
         "signature" (:snapshot/signature snap)
         "played" (:run/signature played)
         "recurs?" (simrace/recurs? snap played)
         "ms" (:run/ms played)
         "pos" (:pos played)
         "reason" (:reason played)))
  (out ""))
(shutdown-agents)
