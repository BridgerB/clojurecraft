(ns probe.fortress-trace
  "Issue #338: a sim run's road to the fortress, sampled: where the bot is, its spiral leg, its food."
  (:require [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  "file seed until every slices: print REACHED lines as stages are reached, an AT line every
   every simulated minutes and the blocks round the feet at the minutes of slices (comma separated)."
  [file seed until every slices]
  (let [w0 (assoc (sr/prepared (capture/read-world file)) :capture/file file)
        nf (sr/nether-file file)
        w (cond-> w0 (.exists (java.io.File. ^String nf)) (sr/with-nether (sr/read-nether nf) nf))
        seed (Long/parseLong seed)
        every (Long/parseLong every)
        slice-at (set (map #(Long/parseLong %) (str/split slices #",")))
        last-min (volatile! -1)
        last-reached (volatile! #{})
        stop? (fn [world]
                (let [m (quot (:time/now world) 60000)
                      reached (:plan/reached world)]
                  (when (not= reached @last-reached)
                    (vreset! last-reached reached)
                    (println "REACHED" m (sort reached)))
                  (when (and (not= m @last-min) (or (zero? (mod m every)) (contains? slice-at m)))
                    (vreset! last-min m)
                    (println "AT"
                             m
                             (mapv long (:player/pos world))
                             (:player/dimension world)
                             (select-keys world [:player/health :player/food])
                             (:fortress/search world))
                    (when (contains? slice-at m)
                      (println "FACTS" m (pr-str (scene/facts world)))
                      (doseq [l (scene/slice world)] (println "SLICE" m l))))
                  (or (plan/done? world) (plan/failed? world))))
        r (sim/run+ sr/step
                    (game/init {:host "sim" :port 0 :name "Clj_sim"})
                    (sim/init (cond-> {:columns (:capture/columns w)
                                       :decoded (:capture/decoded w)
                                       :spawn (sr/spawn-of w)
                                       :seed seed
                                       :tick-seed seed
                                       :difficulty sr/difficulty
                                       :day-time (sr/day-time-of seed)}
                                (:capture/nether w) (assoc :nether (:capture/nether w))))
                    stop?
                    (* 60000 330)
                    (sr/go-of w until))]
    (println "END" (quot (:time/now (:world r)) 60000) "min" (:plan/reached (:world r)))
    (shutdown-agents)))
