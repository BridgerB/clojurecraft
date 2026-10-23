(ns probe.castone2-trace
  "Probe: play a snapshot and print the bot's position, health, controls and intent every 200 ms
   inside a window of simulated time: path:from:to."
  (:require [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn trace!
  [spec]
  (let [[path from to] (str/split spec #":")
        from (parse-long from)
        to (parse-long to)
        snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        last-t (volatile! 0)
        step (fn [world ev]
               (let [w' (sr/step world ev)
                     t (:time/now w')]
                 (when (and (<= from t to) (>= (- t @last-t) 200))
                   (vreset! last-t t)
                   (println "T"
                            t
                            (pr-str (mapv #(long (* 100.0 %)) (:player/pos w')))
                            "hp"
                            (:player/health w')
                            (pr-str (select-keys (:player/controls w')
                                                 [:control/forward? :control/jump? :control/yaw]))
                            (pr-str (select-keys (:plan/intent w')
                                                 [:intent/kind :intent/target :intent/stage]))
                            (when (:player/in-lava? w') "IN-LAVA")))
                 w'))
        stage (sr/stage-of snap)
        stop? #(or (> (:time/now %) to) (plan/done? %) (contains? (:plan/reached %) stage))]
    (println "==" path)
    (sim/play-from step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)))

(doseq [s *command-line-args*] (trace! s))
(shutdown-agents)
