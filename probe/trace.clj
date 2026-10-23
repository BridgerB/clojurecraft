(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.swim :as swim]
         '[clojurecraft.plan :as plan]
         '[clojure.java.io :as io])

(defn newest
  [dir n]
  (->> (.listFiles (io/file dir))
       (filter #(clojure.string/ends-with? (.getName %) ".edn.gz"))
       (sort-by #(.lastModified %) >)
       (take n)
       (map str)))

(defn trace-one
  [path]
  (println "=== TRACE" path)
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        until (:snapshot/until snap)
        go (simrace/go-of w until)
        lag (* 50 (get-in m [:sim :sim/lag-ticks] 0))]
    (loop [m m
           n 0
           last-key nil]
      (let [r (try (sim/tick-from simrace/step m (constantly false) (+ (:t m) 600000) go lag)
                   (catch Exception e {:crashed e}))]
        (cond (:crashed r) (println "CRASHED" (str (:crashed r)))
              (:stop r) (println "STOP plan" (get-in (:stop r) [:world :plan/status]))
              :else
              (let [m' (:next r)
                    world (:world m')
                    i (:plan/intent world)
                    k (:intent/kind i)
                    interesting (#{:swim-out :flee :cross} k)
                    key [k (:intent/status i) (:intent/bank i) (:intent/target i)
                         (get-in i [:intent/child :intent/kind])
                         (get-in i [:intent/child :intent/status]) (:plan/status world)]
                    show (and (< n 4000) (or interesting (not= key last-key) (:intent/child i)))]
                (when show
                  (println "T" (:t m')
                           "pos" (:player/pos world)
                           "feet" (intent/feet world)
                           "trap" (:plan/trap world)
                           "inw" (:player/in-water? world)
                           "inl" (:player/in-lava? world)
                           "ong" (:player/on-ground? world)
                           "eye" (:player/eye-in-water? world)
                           "hp" (:player/health world)
                           "plan" (:plan/status world)
                           "att" (:plan/attempts world)
                           "intent"
                           (pr-str (select-keys i
                                                [:intent/kind :intent/status :intent/goal
                                                 :intent/build :intent/bank :intent/target
                                                 :intent/best-tick :intent/no-lay :intent/dug
                                                 :intent/shore :intent/for :intent/reason]))
                           "child"
                           (pr-str (select-keys (:intent/child i)
                                                [:intent/kind :intent/target :intent/status
                                                 :intent/reason :intent/cell :intent/item])))
                  (when (= :flee k)
                    (println "   footing->" (pr-str (swim/footing world (intent/feet world)))))
                  (when (= :swim-out k)
                    (let [feet (intent/feet world)
                          surface
                          (swim/surface-y world (if (:player/in-lava? world) :lava :water) feet)]
                      (println
                       "   surf" surface
                       "bank?" (pr-str (:intent/bank i))
                       "obstacle" (pr-str (swim/obstacle world feet (:intent/bank i)))
                       "lift" (pr-str (swim/lift-bank world feet surface (:intent/no-lay i)))
                       "occ" (pr-str (swim/occupied-bank world feet surface))
                       "notch" (pr-str (swim/notch world feet surface))
                       "banks1"
                       (pr-str (swim/banks world feet surface (:plan/blacklist world #{})))))))
                (if (or (>= n 4000) (plan/done? world) (plan/failed? world))
                  (println "END n" n
                           "plan" (:plan/status world)
                           "pos" (:player/pos world)
                           "intent"
                           (pr-str (select-keys i
                                                [:intent/kind :intent/status :intent/reason
                                                 :intent/goal])))
                  (recur m' (inc n) key))))))))

(doseq [n ["222" "231" "162"]] (doseq [p (newest (str "test/issues/" n) 2)] (trace-one p)))
(println "TRACE DONE")
