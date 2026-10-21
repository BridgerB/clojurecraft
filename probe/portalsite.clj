(ns probe.portalsite
  "Play each snapshot from its moment, printing what the build and its child do, and where it
   ends why the lava hunt and the site choice found nothing."
  (:require [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]))

(defn view
  "The feet, the plan's intent and its child, the wait."
  [w]
  (let [i (:plan/intent w)
        c (:intent/child i)]
    [(mapv long (intent/feet w))
     (select-keys i
                  [:intent/kind :intent/for :intent/stage :intent/status :intent/reason
                   :intent/target :intent/origin :intent/facing :intent/access])
     (select-keys c
                  [:intent/kind :intent/for :intent/target :intent/status :intent/reason
                   :intent/stage]) (:plan/wait w) (:plan/attempts w)]))

(defn hunt
  "Why the lava hunt found nothing at world w."
  [w]
  (let [sources (build/lava-sources w)
        near (build/sources-near w)
        far (build/sources-near w build/far-radius)
        feet (intent/feet w)]
    (println "HUNT feet" (pr-str feet)
             "sources" (count sources)
             "near" (count near)
             "far" (count far)
             "site" (pr-str (memory/site w))
             "given" (pr-str (memory/given-up-sites w)))
    (println "HUNT choose-site" (pr-str (build/choose-site w))
             "hunt-target" (pr-str (build/hunt-target w))
             "lava-walk" (pr-str (build/lava-walk w))
             "uncap" (pr-str (build/uncap w)))
    (doseq [src (take 12 far)
            :let [[x y z] src
                  over [x (inc y) z]]]
      (println "SRC" (pr-str src)
               "dist" (format "%.1f" (physics/distance (physics/centre feet) (physics/centre src)))
               "over" (pr-str (terrain/block-at w over))
               "fluid-over" (pr-str (terrain/fluid-kind w over))
               "stands" (count (build/stands-beside w src))
               "castable" (build/castable? sources src)
               "tgt-y" (<= (inc y) (second feet))))))

(defn trace
  "Print each change of the view, then the hunt at the end."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        prev (volatile! nil)
        n (volatile! 0)
        stop? (fn [world]
                (let [v (view world)]
                  (when (and (not= v @prev) (< @n 150))
                    (vswap! n inc)
                    (println (:time/now world) (pr-str v)))
                  (vreset! prev v)
                  (or (plan/done? world) (plan/failed? world))))
        r (do (println "TRACE" path (pr-str (:snapshot/signature snap)))
              (sim/play-from simrace/step
                             m
                             stop?
                             (simrace/window-of snap)
                             (simrace/go-of w (:snapshot/until snap))
                             nil))]
    (println "END" path (pr-str (view (:world r))))
    (when (= :no-site (:plan/wait (:world r))) (hunt (:world r)))
    (when (= [:portal :no-site] (:snapshot/signature snap)) (hunt (:world r)))))

(defn -main [& paths] (doseq [p paths] (trace p)) (shutdown-agents))
