(require '[clojurecraft.simrace :as sr]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim])
(let [[path & mss] (clojure.string/split (System/getenv "PROBE_ARGS") #" ")
      snap (capture/read-world path)
      w (#'sr/world-for! snap)
      m (#'sr/moment-of snap w)
      cut #(dissoc % :intent/waypoints :intent/child)]
  (doseq [ms mss]
    (let [r (sim/play-from sr/step
                           m
                           plan/failed?
                           (parse-long ms)
                           (#'sr/go-of w (:snapshot/until snap))
                           nil)
          w' (:world r)
          feet (intent/feet w')
          trunks (->> (memory/positions-now w' blocks/log-states)
                      (memory/nearest-first (game/eye w') wood/far-radius)
                      (remove (wood/skipped w'))
                      (filter #(wood/standing-trunk? w' %))
                      (take wood/far-candidates))]
      (println "PROBE" ms "feet" feet "out?" (stairs/out? w' feet) "trunks" trunks)
      (println "PROBE intent" (cut (:plan/intent w')))
      (println "PROBE climb" (wood/trees-climb w' trunks))
      (println "PROBE bridge" (wood/trees-bridge w' trunks))
      (println "PROBE tunnel" (wood/trees-tunnel w'))
      (println "PROBE descend" (wood/trees-descend w' trunks))
      (println "PROBE opening" (wood/opening-up w'))
      (println "PROBE gather-next" (cut (wood/gather-next w'))))))
(shutdown-agents)
