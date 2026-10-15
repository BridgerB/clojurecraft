;; probe: issue #57, the moment the plan dies. Fold each recording tick by tick and
;; print every change of the facts that decide the wood goal: chunks held, wet-ticks,
;; the trap, the hunt's targets and the route their first walk would take, and what
;; gather-next answers. Runs with the recording's own code (the -f run= checks it out).
(require '[clojure.string :as str]
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.record :as record]
         '[clojurecraft.path :as path]
         '[clojurecraft.intent :as intent])

(.mkdirs (java.io.File. "out"))

(defn out
  [& xs]
  (let [s (str/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))

(defn route-of
  "The route a walk to target would take, as [status waypoints] on one line."
  [world target]
  (let [i {:intent/kind :walk :intent/target target :intent/for :log-hunt}
        r (path/plan world (path/feet-cell (:player/pos world)) (intent/goal-of i))]
    [(name (:path/status r)) (count (:path/waypoints r))]))

(defn watch
  "The facts to watch at one tick while the plan runs and no intent does, or nil."
  [world]
  (when (and (:player/pos world) (not= :failed (:plan/status world)) (not (:plan/intent world)))
    (let [ts (scoop/hunt-targets world)
          route (when (seq ts) (route-of world (first ts)))]
      [(count ts) route
       (some-> (wood/hunt world)
               :intent/target)])))

(doseq [p *command-line-args*]
  (out "==" p)
  (loop [w (game/init {:host "replay" :port 0 :name "Clj_replay"})
         es (record/events p)
         prev nil]
    (if-let [e (first es)]
      (let [w (assoc (main/step w e) :bot/effects [])
            i (:plan/intent w)
            cur [(:time/now w) (count (:world/chunks w)) (:plan/wet-ticks w) (:plan/trap w)
                 (:plan/status w) (:plan/waiting w) (when i [(:intent/kind i) (:intent/for i)])
                 (watch w)]]
        (when (not= (rest cur) prev)
          (out (str/join " " (map (fn [v] (if (coll? v) (pr-str v) (str v))) cur))))
        (recur w (next es) (rest cur)))
      (out "end" (:plan/status w) (:plan/reason w)))))

(shutdown-agents)
