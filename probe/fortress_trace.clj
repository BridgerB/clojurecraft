(ns probe.fortress-trace
  "Issue #338: a sim run's road to the fortress, sampled: where the bot is, its spiral leg, its food."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn why
  "The reasons the step from feet by move is refused, as fortress/safe? reads them."
  [world feet move]
  (let [cells (fortress/opens feet move)
        floor (fortress/plus (fortress/plus feet move) [0 -1 0])
        own (set (concat cells [floor feet (fortress/plus feet fortress/up)]))
        at #(terrain/block-at world %)
        nm #(some-> (at %)
                    blocks/name-of)
        lava? #(contains? blocks/lava-states (at %))]
    {:move move
     :safe? (fortress/safe? world feet move)
     :cells (mapv nm cells)
     :unloaded (into [] (remove at) (conj cells floor))
     :lava (filterv lava? cells)
     :unbreakable
     (filterv #(and (at %) (blocks/solid? (at %)) (contains? fortress/unbreakable (nm %))) cells)
     :lava-beside (filterv lava? (remove own (mapcat fortress/neighbours cells)))
     :falling (filterv #(or (intent/fluid-over-falling? world %) (intent/bed-over-fluid? world %))
                       cells)
     :floor [(nm floor) (path/floor? world floor)]}))

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
                      (when-let [sr (:fortress/search world)]
                        (let [feet (intent/feet world)]
                          (doseq [mv (take 6 (fortress/moves feet (fortress/leg-target sr) 3))]
                            (println "WHY" m (pr-str (why world feet mv))))))
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
    (println "END"
             (quot (:time/now (:world r)) 60000)
             "min"
             (:plan/status (:world r))
             (:plan/reason (:world r))
             (:plan/attempts (:world r))
             (pr-str (select-keys (:plan/last (:world r))
                                  [:intent/kind :intent/reason :intent/target]))
             (pr-str (:crashed r)))
    (shutdown-agents)))
