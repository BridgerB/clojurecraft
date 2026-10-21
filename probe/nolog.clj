(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.game :as game]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.wood :as wood])

(def st (atom 0))

(defn ask
  [label f]
  (try (println label (pr-str (f))) (catch Exception e (println label "THREW" (ex-message e)))))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        observe (fn [world]
                  (when (and (= [2041 106 2826] (:intent/target (:plan/intent world))) (zero? @st))
                    (reset! st 1)
                    (let [feet (intent/feet world)
                          trunks (->> (memory/positions-now world blocks/log-states)
                                      (memory/nearest-first (game/eye world) wood/far-radius)
                                      (remove (wood/skipped world))
                                      (filter #(wood/standing-trunk? world %))
                                      (take 6))]
                      (println "LOOP at" (:time/now world)
                               "feet" feet
                               "eye" (game/eye world)
                               "waiting" (:plan/waiting world))
                      (println " trunks" (pr-str trunks))
                      (println " out?" (stairs/out? world feet)
                               "boxed-at" (:plan/boxed-at world)
                               "blacklist" (count (:plan/blacklist world))
                               "unreached" (:plan/unreached world))
                      (ask " near-walk" #(wood/near-walk world))
                      (ask " trees-climb" #(wood/trees-climb world trunks))
                      (ask " trees-descend" #(wood/trees-descend world trunks))
                      (ask " trees-bridge" #(wood/trees-bridge world trunks))
                      (ask " hunt" #(wood/hunt world))
                      (ask " return-walk" #(wood/return-walk world))
                      (ask " opening-up" #(wood/opening-up world))
                      (ask " intent"
                           #(select-keys (:plan/intent world)
                                         [:intent/kind :intent/for :intent/target]))
                      (ask " last"
                           #(select-keys (:plan/last world)
                                         [:intent/kind :intent/for :intent/target :intent/to-y]))
                      (doseq [t trunks]
                        (ask (str " log-walk " t)
                             #(let [i (wood/log-walk world t)] [i (intent/routable? world i)])))))
                  (or (plan/failed? world) (plan/done? world) (pos? @st)))]
    (println "==" p)
    (sim/play-from simrace/step m observe 300000 (simrace/go-of w0 (:snapshot/until snap)) nil)))
(shutdown-agents)
