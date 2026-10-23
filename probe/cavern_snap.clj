(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.intent :as intent]
         '[clojure.math :as math])

(def steps (atom []))
(def last-step (atom nil))
(def t0 (atom nil))

(defn trace!
  [world]
  (when-not @t0 (reset! t0 (:time/now world)))
  (let [p (:plan/intent world)
        from (:intent/from p)
        move (:intent/move p)]
    (when (and (= :tunnel-to (:intent/kind p)) move (not= [from move] @last-step))
      (reset! last-step [from move])
      (swap! steps conj
        {:t (quot (- (:time/now world) @t0) 1000)
         :from from
         :move move
         :target (:intent/target p)
         :digs (count (filter #(blocks/solid? (terrain/block-at world %))
                              (fortress/opens from move)))
         :safe (boolean (fortress/safe? world from move))}))))

(let [[path] *command-line-args*
      snap (capture/read-world path)
      w (simrace/world-for! snap)
      m (simrace/moment-of snap w)
      stage (simrace/stage-of snap)
      until (:snapshot/until snap)
      stop? (fn [world]
              (trace! world)
              (or (plan/done? world) (plan/failed? world) (contains? (:plan/reached world) stage)))
      {world' :world}
      (sim/play-from simrace/step m stop? (simrace/window-of snap) (simrace/go-of w until) nil)]
  (doseq [[mn ss] (sort-by key (group-by #(quot (:t %) 60) @steps))
          :let [a (first ss)
                z (last ss)
                hd (fn [{[fx _ fz] :from [tx _ tz] :target}]
                     (long (math/hypot (- tx fx) (- tz fz))))
                dy (frequencies (map #(long (math/signum (double (nth (:move %) 1)))) ss))]]
    (println "MIN" mn
             "steps" (count ss)
             "digs" (reduce + (map :digs ss))
             "dy" (pr-str dy)
             "unsafe" (count (remove :safe ss))
             "hdist" (hd a)
             "->" (hd z)
             "y" (nth (:from a) 1)
             "->" (nth (:from z) 1)
             "target" (pr-str (:target a))))
  (doseq [s (take 60 @steps)]
    (println "STEP" (:t s) (pr-str (:from s)) (pr-str (:move s)) "digs" (:digs s) "safe" (:safe s)))
  (println "END" (pr-str (select-keys world' [:player/pos])) (pr-str (:plan/status world')))
  (shutdown-agents))
