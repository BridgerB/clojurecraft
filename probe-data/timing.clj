;; plan/choose and fexit/near-fortress? timed on Clj_r51's world at three fortress-heavy moments,
;; folded with the code of the checkout it runs in. clojure -M:dev probe-data/timing.clj rec.edn
(require '[clojurecraft.record :as record] '[clojurecraft.game :as game] '[clojurecraft.main :as main]
         '[clojurecraft.plan :as plan] '[clojurecraft.fortress-exit :as fexit])
(def moments [1794403400000 1794403900000 1794404400000])
(defn time-us [f n] (dotimes [_ 50] (f)) (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) 1e3 n)))
(defn report [w t]
  (println "AT" t "points" (count (fexit/fortress-points w)) "near?" (fexit/near-fortress? w)
           "choose-us" (format "%.0f" (time-us #(plan/choose w plan/goals) 300))
           "near-us" (format "%.1f" (time-us #(fexit/near-fortress? w) 3000))
           "escape-near-us" (format "%.1f" (time-us #(fexit/escape-near? w) 3000))))
(record/reduce-entries
 (fn [[w left] e]
   (cond (empty? left) (reduced [w left])
         (not (:event/kind e)) [w left]
         (> (:time/now w 0) (first left)) (do (report w (first left)) [w (rest left)])
         :else [(assoc (main/step w e) :bot/effects []) left]))
 [(game/init {:host "replay" :port 0 :name "Clj_why"}) moments]
 (first *command-line-args*))
(shutdown-agents)
