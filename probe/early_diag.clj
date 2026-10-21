(ns probe.early-diag
  "Prints the bot's own log lines over each snapshot's judged window, and why a stair was refused."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.place :as place]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(defn why
  [world]
  (let [feet (intent/feet world)
        i (:plan/intent world)
        shape (:intent/kind i)]
    {:feet feet
     :kind shape
     :to-y (:intent/to-y i)
     :for (:intent/for i)
     :filler (stairs/carried-filler world)
     :boxed-at (:plan/boxed-at world)
     :dirs (vec
            (for [d stairs/directions
                  :let [cells (if (= :stairs-up shape)
                                (stairs/up-cells feet d)
                                (stairs/stair-cells feet d))]]
              {:d d
               :blocks (mapv #(some-> (terrain/block-at world %)
                                      blocks/name-of)
                             cells)
               :class (mapv #(path/classify world %) cells)
               :clear (stairs/clear-way? world cells)
               :safe (stairs/safe? world cells)
               :down-safe (stairs/down-safe? world cells)
               :down-lay (stairs/down-lay world feet cells)
               :up-dry (stairs/up-dry? world cells)
               :up-sealed (stairs/up-sealed? world cells)
               :up-safe (stairs/up-safe? world cells)
               :up-lay (stairs/up-lay world feet cells)
               :step-lay (stairs/step-lay world feet cells)
               :step-to-lay (stairs/step-to-lay world cells)
               :wet-feet (stairs/wet-feet world cells)
               :wet-support (mapv (fn [c] [c (terrain/fluid-at world c) (place/support world c)])
                                  (stairs/wet-feet world cells))
               :feet-fluid (terrain/fluid-at world feet)
               :exposes (filterv #(intent/exposes-fluid? world %) cells)
               :seal (stairs/seal world feet cells)
               :lay (stairs/lay world feet cells)
               :wet (vec (stairs/water-beside world cells))}))}))

(defn -main
  [& paths]
  (doseq [path paths]
    (try (let [snap (capture/read-world path)
               w (sr/world-for! snap)
               lines (atom [])
               dumps (atom 0)
               walks (atom 0)
               orig sr/step
               logged (fn [world event]
                        (let [w' (orig world event)]
                          (doseq [e (:bot/effects w')
                                  :when (= :log (:effect/kind e))]
                            (swap! lines conj [(:time/now w') (:effect/message e)])
                            (when (and (re-find #"^intent :walk" (str (:effect/message e)))
                                       (< @walks 4))
                              (swap! walks inc)
                              (swap! lines conj
                                [(:time/now w')
                                 (str "WALK " (pr-str (:plan/intent w'))
                                      " blacklist=" (count (:plan/blacklist w'))
                                      " unwalkable=" (:plan/unwalkable w')
                                      " attempts=" (:plan/attempts w'))]))
                            (when (and (re-find #"refused toward|every stair refused"
                                                (str (:effect/message e)))
                                       (< @dumps 4))
                              (swap! dumps inc)
                              (swap! lines conj [(:time/now w') (str "WHY " (pr-str (why w')))])))
                          w'))
               r (with-redefs [sr/step logged] (sr/judged snap w))]
           (println "DIAG" path (pr-str (:run/signature r)))
           (doseq [[t m] (distinct @lines)] (println "LOG" path t m)))
         (catch Exception e (println "CRASH" path (pr-str e)))))
  (shutdown-agents))
