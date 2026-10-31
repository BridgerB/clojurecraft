;; Like flip_trace.clj, for a probe, with the end of each intent: on every change of the running
;; intent, how the previous one ended (its last intention fact: event and reason), whether the
;; pocket could be sealed (pocket/can-seal?), the site's own flags and the filler held.
;;   clojure -M scripts/flip_trace2.clj run.edn.zst 40
;; Run it from a checkout of the commit that made the recording (its header names it).
(require 'clojure.math
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.record :as record])

(let [[path secs] *command-line-args*
      secs (Long/parseLong (or secs "40"))
      events (record/events path)
      end (or (some :event/now (reverse events)) 0)
      cutoff (- end (* 1000 secs))
      can-seal? (try (requiring-resolve 'clojurecraft.pocket/can-seal?) (catch Exception _ nil))
      world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
      row (fn [w]
            (try (let [g (plan/choose w plan/goals)
                       i (:plan/intent w)]
                   [(:goal/id g) (when (and g i) (boolean (plan/interrupts? w g i)))])
                 (catch Exception t [(str "choose-threw " (ex-message t)) nil])))
      ended (fn [w id]
              (when id
                (some->> (memory/intentions w)
                         (filter #(= id (:intention/id %)))
                         last
                         (#(select-keys % [:intention/kind :intention/event :intention/reason])))))
      site-info (fn [w]
                  (when-let [site (:pocket/site w)]
                    {:can-seal? (when can-seal?
                                  (try (boolean (can-seal? w site)) (catch Exception e (str e))))
                     :placed (count (:pocket/placed site))
                     :unsealable? (:pocket/unsealable? site)
                     :seal-failed-at (:pocket/seal-failed-at w)
                     :held
                     (into {} (map (fn [[_ s]] [(:item s) (:count s)])) (:player/inventory w))}))
      lines (volatile! [])]
  (reduce (fn [[w prev] e]
            (let [w (assoc (main/step w e) :bot/effects [])
                  i (:plan/intent w)
                  now (:time/now w 0)
                  [chosen cut] (when (>= now cutoff) (row w))
                  k [(:intent/id i) chosen
                     (some-> w
                             :player/health
                             long)]]
              (when (and (>= now cutoff) (not= k prev))
                (vswap! lines
                        conj
                        (cond-> {:t (- now cutoff)
                                 :hp (:player/health w)
                                 :intent (select-keys i
                                                      [:intent/id :intent/kind :intent/target
                                                       :intent/goal :intent/for])
                                 :chosen chosen
                                 :interrupts? cut
                                 :prev-ended (when (not= (first k) (first prev))
                                               (ended w (first prev)))
                                 :attacker (:player/attacker w)
                                 :fire (:player/on-fire? w)}
                          (site-info w) (assoc :site (site-info w)))))
              [w k]))
          [world0 nil]
          events)
  (doseq [l @lines] (prn l))
  (println "events end at" end "ms; lines" (count @lines)))
(shutdown-agents)
