;; Clj_r51's real snapshot (a 15-minute stall of heading legs failing :blocked, each followed by a
;; :retreat walk to the lava sea's shore) stepped a quarter second at a time with main's code:
;; every change of feet, intent and child; at each failure the failed intent, the retry decision
;; and the terrain round the feet. clojure -M:dev probe/r51step.clj snap.edn.gz
(require '[clojurecraft.capture :as capture] '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim] '[clojurecraft.plan :as plan] '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path] '[clojurecraft.pearls.move :as pmove] '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)) (flush))
(defn ask [label f] (try (out label (pr-str (f))) (catch Throwable e (out label "THREW" (ex-message e)))))
(defn terrain [world]
  (let [[fx fy fz] (intent/feet world) r 6]
    (doseq [y (range (+ fy 3) (- fy 3) -1)]
      (out "  y" y)
      (doseq [z (range (- fz r) (inc (+ fz r)))]
        (out "  " (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                              (if (= [x y z] [fx fy fz]) "@"
                                  (case (path/classify world [x y z])
                                    :solid "#" :water "W" :clear "." :unknown "?" :lava "L" "~")))))))))
(let [snap (capture/read-world (first *command-line-args*))
      [world0 sim0] (simrace/moment snap (simrace/world-for! snap))]
  (out "START" (pr-str (intent/feet world0)) (pr-str (:pearls/heading world0)))
  (loop [world world0 sim sim0 k 0 prev nil fails 0]
    (when (and (< k 2400) (< fails 3) (not (plan/failed? world)) (not (plan/done? world)))
      (let [{world :world sim :sim} (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
            i (:plan/intent world)
            c (:intent/child i)
            cur [(intent/feet world)
                 (select-keys i [:intent/kind :intent/for :intent/target :intent/steps :intent/taken :intent/retreats])
                 (select-keys c [:intent/kind :intent/target :intent/status :intent/reason])]
            failed (:plan/failed world)
            new-fail? (and failed (not= (:intent/id failed) (:failed-id (meta prev))))]
        (when (not= (take 2 cur) (take 2 prev)) (out (:time/now world) (pr-str cur)))
        (when new-fail?
          (out "FAILED" (pr-str (select-keys failed [:intent/kind :intent/target :intent/reason :intent/from-feet :intent/for
                                                     :intent/taken :intent/retreats :intent/steps :intent/tunnel-visited])))
          (ask " heading" #(:pearls/heading world))
          (ask " trail-tail" #(vec (take-last 6 (:plan/trail world))))
          (ask " retreat-cell" #(plan/retreat-cell world failed (intent/feet world)))
          (ask " next-intent" #(select-keys (plan/next-intent world (plan/choose world plan/goals) plan/goals)
                                            [:intent/kind :intent/target :intent/for]))
          (terrain world))
        (recur world sim (inc k) (with-meta cur {:failed-id (:intent/id failed)})
               (if new-fail? (inc fails) fails))))))
(shutdown-agents)
