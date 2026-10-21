(ns probe.pkplay
  "Portal-kit batch: each snapshot played as the check judges it, with the intents and the bot's
   log lines (capped), the feet every ten seconds and the plan's failure memory at the end."
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn play
  ([path cap] (play path cap (fn [_ _] nil) (fn [_] nil)))
  ([path cap on-intent on-sample]
   (let [snap (capture/read-world path)
         w (sr/world-for! snap)
         m (sr/moment-of snap w)
         world (:world m)
         until (:snapshot/until snap)
         last-id (volatile! nil)
         n (volatile! 0)
         last-pos (volatile! 0)
         say (fn [& xs] (when (< @n cap) (vswap! n inc) (apply println xs)))
         step (fn [wd ev]
                (let [n0 (count (:bot/effects wd))
                      wd' (sr/step wd ev)
                      i (:plan/intent wd')
                      now (:time/now wd')]
                  (when (and i (not= (:intent/id i) @last-id))
                    (vreset! last-id (:intent/id i))
                    (on-intent wd' i)
                    (say "INTENT"
                         now
                         (select-keys i
                                      [:intent/kind :intent/goal :intent/for :intent/target
                                       :intent/stage :intent/reason])
                         (some-> (:player/pos wd')
                                 (->> (mapv #(math/round (double %)))))))
                  (when (> (- now @last-pos) 10000)
                    (vreset! last-pos now)
                    (on-sample wd')
                    (say "POS"
                         now
                         (some-> (:player/pos wd')
                                 (->> (mapv #(/ (math/round (* 10 (double %))) 10.0))))
                         "health"
                         (:player/health wd')))
                  (doseq [fx (drop n0 (:bot/effects wd'))
                          :when (= :log (:effect/kind fx))]
                    (say "LOG" now (:effect/message fx)))
                  wd'))
         stop? #(or (plan/done? %) (plan/failed? %))
         r (sim/play-from step m stop? (#'sr/window-of snap) (sr/go-of w until) nil)
         w2 (:world r)]
     (println "END"
              path
              (:time/now world)
              "->"
              (:time/now w2)
              {:status (:plan/status w2)
               :reason (:plan/reason w2)
               :attempts (:plan/attempts w2)
               :blacklist (:plan/blacklist w2)
               :feet (:player/pos w2)
               :intent (select-keys (:plan/intent w2) [:intent/kind :intent/goal :intent/for])})
     w2)))

(defn -main
  [& paths]
  (doseq [p paths]
    (println "==" p)
    (try (play p 140) (catch Exception e (println "THREW" p (ex-message e)))))
  (shutdown-agents))
