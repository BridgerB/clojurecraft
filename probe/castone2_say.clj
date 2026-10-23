(ns probe.castone2-say
  "Probe: play snapshots as judged does, printing the bot's log lines (game/say) and the running
   intent every 20 s, to see why a build idles."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn run!*
  [path]
  (println "== " path)
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        last-say (volatile! nil)
        last-t (volatile! 0)
        n (volatile! 0)
        step (fn [world ev]
               (let [w' (sr/step world ev)
                     t (:time/now w')]
                 (doseq [e (:bot/effects w')
                         :when (and (= :log (:effect/kind e))
                                    (< @n 150)
                                    (not= @last-say (:effect/message e)))]
                   (vreset! last-say (:effect/message e))
                   (vswap! n inc)
                   (println "SAY" t (:effect/message e)))
                 (when (and (< @n 150) (> (- t @last-t) 20000))
                   (vreset! last-t t)
                   (vswap! n inc)
                   (println "INT"
                            t
                            (pr-str (select-keys (:plan/intent w')
                                                 [:intent/kind :intent/op :intent/stage
                                                  :intent/origin :intent/reason]))
                            (pr-str (select-keys (:intent/child (:plan/intent w'))
                                                 [:intent/kind :intent/status :intent/reason
                                                  :intent/target :intent/for]))
                            (pr-str (:player/pos w'))))
                 w'))
        stage (sr/stage-of snap)
        stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))]
    (sim/play-from step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (println "== end")))

(doseq [p *command-line-args*] (run!* p))
(shutdown-agents)
