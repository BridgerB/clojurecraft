(ns clojurecraft.fortress2-trace
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn trace
  "Plays the snapshot at path on for minutes, printing a sample every 30 simulated seconds."
  [path minutes]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        t0 (:t m)
        last-s (volatile! -1)
        stop? (fn [world]
                (let [s (quot (- (:time/now world) t0) 30000)]
                  (when (> s @last-s)
                    (vreset! last-s s)
                    (let [{:search/keys [heading leg tunnels from]} (:fortress/search world)
                          i (:plan/intent world)]
                      (println "TRACE"
                               path
                               (format "%.1f" (/ (- (:time/now world) t0) 60000.0))
                               "feet" (intent/feet world)
                               "leg" leg
                               "heading" heading
                               "tunnels" tunnels
                               "from" from
                               "bricks" (count (fortress/bricks world))
                               "kind" (:intent/kind i)
                               "taken" (:intent/taken i)
                               "dig-ms" (:intent/dig-ms i)
                               "tool" (:intent/tool i)
                               "chunks" (count (:world/chunks world))
                               "sticks" (get (make/counts world) :stick 0)
                               "picks"
                               (reduce +
                                       (for [[k n] (make/counts world)
                                             :when (clojure.string/ends-with? (name k) "_pickaxe")]
                                         n)))))
                  false))]
    (sim/play-from simrace/step
                   m
                   stop?
                   (* minutes 60000)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    nil))

(defn -main
  "Traces each snapshot of the comma-separated first argument."
  [paths minutes]
  (doseq [p (clojure.string/split paths #",")] (trace p (Long/parseLong minutes))))
