;; Per-tick planner trace of a recording, for a probe (never run on the Mac): the line for every tick
;; the running intent, the health or the chosen row changes, over the last SECONDS of the run.
;;   clojure -M scripts/flip_trace.clj run.edn.zst 12
;; Run it from a checkout of the commit that made the recording (its header names it).
(require 'clojure.math
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.record :as record])

(let [[path secs] *command-line-args*
      secs (Long/parseLong (or secs "12"))
      world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
      row (fn [w]
            (try (let [g (plan/choose w plan/goals)
                       i (:plan/intent w)]
                   [(:goal/id g) (when (and g i) (boolean (plan/interrupts? w g i)))])
                 (catch Exception t [(str "choose-threw " (ex-message t)) nil])))
      lines (volatile! [])
      end (volatile! 0)
      final (reduce (fn [[w prev] e]
                      (let [w (assoc (main/step w e) :bot/effects [])
                            i (:plan/intent w)
                            [chosen cut] (row w)
                            now (:time/now w 0)
                            k [(:intent/id i) (:intent/kind i)
                               (some-> w
                                       :player/health
                                       long) chosen]]
                        (vreset! end now)
                        (when (not= k prev)
                          (vswap! lines
                                  conj
                                  {:t now
                                   :hp (:player/health w)
                                   :pos (some->> (:player/pos w)
                                                 (mapv #(/ (clojure.math/round (* 10.0 (double %)))
                                                           10.0)))
                                   :intent (select-keys i
                                                        [:intent/id :intent/kind :intent/target
                                                         :intent/goal :intent/for])
                                   :chosen chosen
                                   :interrupts? cut
                                   :attacker (:player/attacker w)
                                   :status (:plan/status w)}))
                        [w k]))
                    [world0 nil]
                    (record/events path))
      cutoff (- @end (* 1000 secs))]
  (doseq [l @lines :when (>= (:t l) cutoff)] (prn l))
  (println "events end at" @end "ms; lines" (count @lines)))
(shutdown-agents)
