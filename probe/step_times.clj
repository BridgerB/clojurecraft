(require '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.record :as record])

(let [path (first *command-line-args*)
      slow (atom [])
      acc (atom {:w (game/init {:host "replay" :port 0 :name "Clj_replay"}) :i 0})
      by-kind (atom {})]
  (record/reduce-entries
   (fn [_ e]
     (when (:event/kind e)
       (let [w (:w @acc)
             t0 (System/nanoTime)
             w2 (assoc (main/step w e) :bot/effects [])
             ms (/ (- (System/nanoTime) t0) 1.0e6)
             k [(:event/kind e) (get-in e [:event/packet :packet/name])]]
         (swap! by-kind update k (fn [[n t]] [(inc (or n 0)) (+ (or t 0.0) ms)]))
         (when (> ms 80.0)
           (swap! slow conj
             {:i (:i @acc)
              :ms (long ms)
              :kind k
              :intent (select-keys (:plan/intent w2) [:intent/kind :intent/stage])
              :now (:event/now e)}))
         (swap! acc assoc :w w2 :i (inc (:i @acc)))))
     nil)
   nil
   path)
  (println "events" (:i @acc))
  (println "SLOW steps (>80 ms):" (count @slow))
  (doseq [s (take 40 (reverse (sort-by :ms @slow)))] (println s))
  (println "TOTAL ms by event kind:")
  (doseq [[k [n t]] (take 12 (reverse (sort-by (comp second val) @by-kind)))]
    (println k "n" n "total-ms" (long t) "mean-ms" (format "%.2f" (/ t n)))))
