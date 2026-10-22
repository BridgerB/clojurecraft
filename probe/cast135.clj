(ns probe.cast135
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  [& paths]
  (doseq [path paths]
    (let [snap (capture/read-world path)
          w (@#'sr/world-for! snap)
          {:keys [world] :as m} (@#'sr/moment-of snap w)
          last-id (volatile! nil)
          stop? (fn [wd]
                  (let [i (:plan/intent wd)
                        id (:intent/id i)]
                    (when (not= id @last-id)
                      (vreset! last-id id)
                      (println "TRACE"
                               path
                               (:time/now wd)
                               (pr-str (intent/feet wd))
                               (pr-str (select-keys i
                                                    [:intent/kind :intent/for :intent/stage
                                                     :intent/target :intent/to-y :intent/dir
                                                     :intent/trunk :intent/id]))
                               "wait"
                               (pr-str (:plan/wait (:plan/decision wd)))))
                    (or (plan/done? wd) (plan/failed? wd))))]
      (sim/play-from sr/step
                     m
                     stop?
                     (@#'sr/window-of snap)
                     (@#'sr/go-of w (:snapshot/until snap))
                     nil))))
