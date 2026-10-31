;; Per-step trace of :tunnel-to over a recording, for a probe (never run on the Mac): every time the running
;; :tunnel-to chooses a move, its feet, target, move, retreats, visited cells and steps taken; every
;; time a new :tunnel-to starts, its target and search leg.
;;   clojure -M scripts/flip_tunnel.clj run.edn.zst 240
(require '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.main :as main]
         '[clojurecraft.record :as record])

(let [[path secs] *command-line-args*
      secs (Long/parseLong (or secs "240"))
      events (record/events path)
      end (or (some :event/now (reverse events)) 0)
      cutoff (- end (* 1000 secs))
      world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})]
  (reduce (fn [[w prev-move prev-id] e]
            (let [w (assoc (main/step w e) :bot/effects [])
                  i (:plan/intent w)
                  tunnel? (= :tunnel-to (:intent/kind i))
                  now (:time/now w 0)
                  move (when tunnel? (:intent/move i))
                  id (:intent/id i)]
              (when (and tunnel? (>= now cutoff))
                (cond (not= id prev-id)
                      (prn {:new-tunnel id
                            :t (- now cutoff)
                            :feet (intent/feet w)
                            :target (:intent/target i)
                            :search (:intent/search i)
                            :steps (:intent/steps i)
                            :hp (:player/health w)})
                      (and move (not= move prev-move))
                      (prn {:id id
                            :t (- now cutoff)
                            :feet (intent/feet w)
                            :target (:intent/target i)
                            :move move
                            :taken (:intent/taken i)
                            :retreats (:intent/retreats i)
                            :visited (count (:intent/tunnel-visited i))})))
              [w move id]))
          [world0 nil nil]
          events)
  (println "events end at" end "ms"))
(shutdown-agents)
