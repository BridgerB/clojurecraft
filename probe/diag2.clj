(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.intent :as intent])

(doseq [p *command-line-args*]
  (try (let [snap (capture/read-world p)
             w0 (or (simrace/own-world snap)
                    (simrace/prepared (capture/read-world (:snapshot/file snap))))
             m (simrace/moment-of snap w0)
             r (sim/play-from simrace/step
                              m
                              (fn [w]
                                (let [i (:plan/intent w)]
                                  (and (= :walk (:intent/kind i))
                                       (= :opening (:intent/for i))
                                       (= :stairs-down (:intent/kind (:plan/last w))))))
                              (simrace/window-of snap)
                              (simrace/go-of w0 (:snapshot/until snap))
                              nil)
             w (:world r)
             bare (dissoc w :plan/intent)]
         (println "==" p "pos" (:player/pos w))
         (println "intent"
                  (pr-str (select-keys (:plan/intent w)
                                       [:intent/kind :intent/for :intent/target :intent/goal])))
         (println "last"
                  (pr-str (select-keys (:plan/last w)
                                       [:intent/kind :intent/for :intent/status :intent/goal])))
         (println "descended?" (wood/descended? w) "out?" (stairs/out? w (intent/feet w)))
         (println "continue-gather" (pr-str (wood/continue-gather bare)))
         (println "near-walk" (pr-str (wood/near-walk bare)))
         (println "climb-out" (pr-str (wood/climb-out bare)))
         (println "gather-next" (pr-str (wood/gather-next bare)))
         (println "plan" (pr-str (select-keys w [:plan/goal :plan/status :plan/wait]))))
       (catch Exception e (println "==" p "THREW" (ex-message e)))))
(shutdown-agents)
