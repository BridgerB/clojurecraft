(ns probe.fortress5-watch
  "Probe: play snapshots as judged does, printing every PERIOD ms (first argument) the feet, the
   tunnel's target, move, op and child, and every game/say line, to see where a tunnel goes."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn run!*
  [period path]
  (println "== " path)
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        last-say (volatile! nil)
        last-t (volatile! 0)
        n (volatile! 0)
        step (fn [world ev]
               (let [w' (sr/step world ev)
                     t (:time/now w')
                     i (:plan/intent w')
                     c (:intent/child i)]
                 (doseq [e (:bot/effects w')
                         :when (and (= :log (:effect/kind e))
                                    (< @n 400)
                                    (not= @last-say (:effect/message e)))]
                   (vreset! last-say (:effect/message e))
                   (vswap! n inc)
                   (println "SAY" t (:effect/message e)))
                 (when (and (< @n 400) (> (- t @last-t) period))
                   (vreset! last-t t)
                   (vswap! n inc)
                   (println "INT"
                            t
                            (pr-str (:player/pos w'))
                            (pr-str (select-keys i
                                                 [:intent/kind :intent/target :intent/move
                                                  :intent/taken :intent/retreats :intent/stage
                                                  :intent/reason :intent/status]))
                            (pr-str (:build/step (:intent/op i)))
                            (pr-str (select-keys c
                                                 [:intent/kind :intent/status :intent/reason
                                                  :intent/target :intent/for :intent/item
                                                  :intent/stage]))
                            (pr-str (select-keys (:plan/last w') [:intent/status :intent/reason]))))
                 w'))
        stage (sr/stage-of snap)
        stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))]
    (sim/play-from step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (println "== end")))

(let [[period & paths] *command-line-args*] (doseq [p paths] (run!* (Long/parseLong period) p)))
(shutdown-agents)
