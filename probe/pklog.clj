(ns probe.pklog
  "Portal-kit batch (issues #161 #94 #75 #71): a snapshot judged as the check does, with the bot's
   log lines (intent failures with their attempts) and the plan's failure memory printed at the end."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn play
  [path]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        world (:world m)
        until (:snapshot/until snap)
        last-id (volatile! nil)
        step (fn [wd ev]
               (let [n0 (count (:bot/effects wd))
                     wd' (sr/step wd ev)
                     i (:plan/intent wd')]
                 (when (and i (not= (:intent/id i) @last-id))
                   (vreset! last-id (:intent/id i))
                   (when (= :back-off (:intent/kind i))
                     (let [e (get-in wd' [:world/entities (:intent/target i)])]
                       (println "ENTITY"
                                (:time/now wd')
                                (some-> e
                                        entity/kind)
                                (:entity/pos e)
                                "bot"
                                (:player/pos wd'))))
                   (println "INTENT"
                            (:time/now wd')
                            (select-keys i
                                         [:intent/kind :intent/goal :intent/for :intent/target
                                          :intent/pickup? :intent/stage])))
                 (doseq [fx (drop n0 (:bot/effects wd'))
                         :when (= :log (:effect/kind fx))]
                   (println "LOG" (:time/now wd') (:effect/message fx)))
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
              :unwalkable (:plan/unwalkable w2)
              :boxed-at (count (:plan/boxed-at w2))
              :boxed-route (count (:plan/boxed-route w2))
              :blacklist (count (:plan/blacklist w2))
              :feet (:player/pos w2)})))

(defn -main [& paths] (doseq [p paths] (play p)) (shutdown-agents))
