(ns probe.blazetime-moment
  "blazetime: a snapshot's window played on, with the first intent changes printed whole."
  (:require [clojure.math :as math]
            [clojure.string]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn r1 [v] (mapv #(/ (math/round (* 10 (double %))) 10.0) v))

(defn play
  [path limit]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        t0 (:time/now (:world m))
        last-id (atom nil)
        n (atom 0)
        stop? (fn [world]
                (let [i (:plan/intent world)
                      id (:intent/id i)]
                  (when (and (not= id @last-id) (< @n limit))
                    (swap! n inc)
                    (println "I" (long (/ (- (:time/now world) t0) 1000))
                             "s"
                             (select-keys i
                                          [:intent/id :intent/kind :intent/target :intent/taken
                                           :intent/steps :intent/reason :intent/status :intent/for])
                             "hp" (:player/health world)
                             "food" (:player/food world)
                             "pos"
                             (some-> (:player/pos world)
                                     r1)
                             "search" (:fortress/search world)
                             "last"
                             (select-keys (:plan/last world)
                                          [:intent/kind :intent/status :intent/reason])))
                  (reset! last-id id)
                  false))]
    (println "==" path)
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w nil) nil)))

(doseq [p (clojure.string/split (or (System/getenv "SNAPS") "") #",")
        :when (seq p)]
  (try (play p 40) (catch Exception e (println "ERR" p (str e)))))
(println "done")
(shutdown-agents)
