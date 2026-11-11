;; The bot's CPU per event by part, over a recording: the step (game+plan), the ledger's diffs
;; (senses/events, twice an event: the step's swap and the effects-clearing swap, as
;; main/apply-event! does), and the recording's printing (record/wire + effects). Folded once to
;; warm the JIT, then timed. clojure -M probe-data/cost.clj rec.edn
(require '[clojurecraft.record :as record] '[clojurecraft.game :as game]
         '[clojurecraft.main :as main] '[clojurecraft.senses :as senses])
(let [path (first *command-line-args*)
      es (record/events path)
      w0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
      now #(System/nanoTime)
      t (atom {:step 0 :ledger 0 :print 0})
      add! (fn [k t0] (swap! t update k + (- (now) t0)))
      kinds (atom {})]
  (reduce (fn [w e] (assoc (main/step w e) :bot/effects [])) w0 es)
  (reduce (fn [w e]
            (let [t0 (now) _ (pr-str (record/wire e)) _ (add! :print t0)
                  t1 (now) w1 (main/step w e) d (- (now) t1)
                  _ (swap! t update :step + d)
                  k (or (get-in e [:event/packet :packet/name]) (:event/kind e))
                  _ (swap! kinds update k (fnil (fn [[n s]] [(inc n) (+ s d)]) [0 0]))
                  t2 (now) _ (doall (senses/events w w1)) w2 (assoc w1 :bot/effects [])
                  _ (doall (senses/events w1 w2)) _ (add! :ledger t2)
                  t3 (now) _ (when (seq (:bot/effects w1)) (pr-str {:record/effects (:bot/effects w1)}))
                  _ (add! :print t3)]
              w2))
          w0 es)
  (let [span (- (:event/now (last (filter :event/now es))) (:event/now (first (filter :event/now es))))]
    (println "events" (count es) "span-s" (quot span 1000))
    (println "cpu-ms" (update-vals @t #(quot % 1000000))
             "share-of-one-core" (update-vals @t #(format "%.1f%%" (/ (* 100.0 (/ % 1e6)) span))))
    (println "step by kind, top 12 [n total-ms us-each]:")
    (doseq [[k [n s]] (take 12 (sort-by (comp - second val) @kinds))]
      (println " " k n (quot s 1000000) (quot s (* 1000 n))))))
(shutdown-agents)
