(.mkdirs (clojure.java.io/file "out"))
(require '[clojurecraft.capture :as capture] '[clojurecraft.simrace :as simrace])
(doseq [path *command-line-args*]
  (let [snap (capture/read-world path)
        w (simrace/prepared (capture/read-world (:snapshot/file snap)))
        r (simrace/play-snapshot snap w simrace/snapshot-budget-ms)]
    (println (last (clojure.string/split path #"/"))
             (:snapshot/signature snap)
             "->" (:run/signature r)
             "recurs?" (simrace/recurs? snap r)
             "status" (get-in r [:plan :plan/status])
             "pos" (mapv long (:pos r))
             "ms" (:run/ms r)
             "last"
             (select-keys (get-in r [:plan :plan/last])
                          [:intent/kind :intent/for :intent/status :intent/reason]))))
(spit "out/done" "ok")
(shutdown-agents)
