(require '[clojurecraft.simrace :as sr] '[clojure.string :as str])
(def done (promise))
(def worker
  (Thread. (fn []
             (try (sr/judge! "test/issues/344/b10210757-R4-16-2.edn.gz")
                  (finally (deliver done true))))))
(.start worker)
(def tops (atom {}))
(def deep (atom {}))
(loop [n 0]
  (when (and (not (realized? done)) (< n 1000))
    (Thread/sleep 100)
    (let [fs (->> (.getStackTrace worker)
                  (map str)
                  (filter #(str/starts-with? % "clojurecraft")))]
      (when-let [f (first fs)] (swap! tops update f (fnil inc 0)))
      (doseq [f (distinct (map #(str/replace % #"\(.*" "") fs))]
        (swap! deep update f (fnil inc 0))))
    (recur (inc n))))
(println "PROF-TOP")
(doseq [[k v] (take 15 (sort-by (comp - val) @tops))] (println v k))
(println "PROF-INCLUSIVE")
(doseq [[k v] (take 30 (sort-by (comp - val) @deep))] (println v k))
(shutdown-agents)
(System/exit 0)
