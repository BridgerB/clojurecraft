;; Issue #563 (pearls11): issue #563's snapshot judged over five minutes on the branch's code,
;; printing every hurt the player takes, every failed intent and every abandoned one, to see why the
;; walk to a log and its dig repeat with no log taken.
;;
;;   clojure -M p563_trace.clj <snapshot.edn.gz>
(require '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojure.string :as str])

(defn out [& xs] (locking *out* (println (str/join " " xs)) (flush)))

(let [hurt sim/hurt]
  (alter-var-root #'sim/hurt
                  (constantly (fn [s amount exhausting?]
                                (out "HURT t" (:sim/now s)
                                     "amount" amount
                                     "health" (:sim/health s)
                                     "pos" (pr-str (mapv long (:sim/player-pos s))))
                                (hurt s amount exhausting?)))))
(let [fail plan/fail-intent]
  (alter-var-root #'plan/fail-intent
                  (constantly (fn [w i]
                                (out "FAILED t"
                                     (:time/now w)
                                     (pr-str (select-keys i
                                                          [:intent/kind :intent/for :intent/target
                                                           :intent/reason :intent/stage])))
                                (fail w i)))))
(let [abandon plan/abandon-intent]
  (alter-var-root #'plan/abandon-intent
                  (constantly (fn [w i why]
                                (out "ABANDONED t"
                                     (:time/now w)
                                     (name why)
                                     (pr-str (select-keys i
                                                          [:intent/kind :intent/for :intent/target
                                                           :intent/stage])))
                                (abandon w i why)))))

(with-redefs [simrace/window-of (constantly 300000)] (simrace/judge! (first *command-line-args*)))
(shutdown-agents)
