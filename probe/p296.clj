(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.make :as make]
         '[clojurecraft.build :as build]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.dimension :as dimension]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn ask
  [label f]
  (try (out label (pr-str (f)))
       (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/stage :intent/target :intent/move
                :intent/from :intent/status :intent/reason :intent/op]))
(defn fortress-row [] (first (filter #(= :fortress (:goal/id %)) plan/goals)))
(defn look
  [world]
  (ask " dimension" #(dimension/of world))
  (ask " feet" #(intent/feet world))
  (ask " held" #(recipe/counts (:player/inventory world)))
  (ask " intent" #(brief (:plan/intent world)))
  (ask " child" #(brief (:intent/child (:plan/intent world))))
  (ask " last" #(brief (:plan/last world)))
  (ask " attempts" #(:plan/attempts world))
  (ask " pickaxe-at-hand?" #(build/pickaxe-at-hand? world))
  (ask " pickaxe row"
       #(select-keys
         (make/next-row world {:goal/id :pickaxe :goal/provides {:tag/pickaxes 1}} plan/goals)
         [:goal/id :goal/act]))
  (ask " fortress next-intent" #(plan/next-intent world (fortress-row) plan/goals))
  (when-let [t (some-> (:plan/intent world)
                       :intent/op
                       :build/pos)]
    (ask " op block" #(blocks/name-of (terrain/block-at world t)))
    (ask " op dig-time" #(intent/dig-time (terrain/block-at world t)))
    (ask " op hand-dig-refused?" #(build/hand-dig-refused? world t))))
(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
          [world sim] (simrace/moment snap w0)]
      (out "==" p (pr-str (:snapshot/signature snap)) "now" (:time/now world))
      (look world)
      (loop [world world
             sim sim
             k 0
             prev nil]
        (if (and (< k 960) (not (plan/failed? world)) (not (plan/done? world)))
          (let [{world :world sim :sim}
                (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
                i (:plan/intent world)
                cur [(:intent/kind i) (:intent/goal i) (:intent/move i) (:build/op (:intent/op i))
                     (:intent/kind (:intent/child i)) (:intent/reason (:intent/child i))
                     (:intent/reason i) (:plan/waiting world)]]
            (when (not= cur prev)
              (out "  "
                   (:time/now world)
                   (intent/feet world)
                   (pr-str cur)
                   "attempts" (:plan/attempts world)
                   "pick?" (build/pickaxe-at-hand? world)))
            (recur world sim (inc k) cur))
          (do (out "  end"
                   (:plan/status world)
                   (:plan/reason world)
                   (pr-str (brief (:plan/last world))))
              (look world)))))
    (catch Exception e (out "==" p "THREW" (ex-message e)))))
(shutdown-agents)
