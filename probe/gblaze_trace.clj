(ns probe.gblaze-trace
  "Where the blaze-rods bot stands when a fight cannot strike, and where a flee loses its
   footing. Prints one line a fact. Run on a runner:
   bb issue-check fetch 564 && bb issue-check fetch 514 && clojure -M:dev -m probe.gblaze-trace"
  (:require [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.combat :as combat]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(defn snaps
  "The snapshot files fetch left under test/issues/n."
  [n]
  (->> (file-seq (File. (str "test/issues/" n)))
       (filter #(str/ends-with? (str %) ".edn.gz"))
       (map str)
       sort))

(defn opened
  "A snapshot's play moment, [snap moment]."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)]
    [snap (simrace/moment-of snap w)]))

(defn blaze-line
  "What a fight would make of blaze entity e from world: the distances, whether the sword
   reaches, whether a step toward it is a drop, and where the approach's route ends."
  [world eid e]
  (let [route (combat/route-walk world e)]
    (pr-str {:eid eid
             :pos (:entity/pos e)
             :horizontal (combat/horizontal world e)
             :eye-to-aim (physics/distance (game/eye world) (combat/aim e))
             :hittable (combat/hittable? world e)
             :approachable (combat/approachable? world e)
             :ledge (intent/ledge-ahead? world (combat/aim e))
             :route (cond (nil? route) nil
                          (= :here route) :here
                          :else (:intent/target route))})))

(defn trace-fight
  "At the moment of each snapshot under test/issues/564: the feet, the running intent, and
   every blaze in the world."
  []
  (doseq [path (snaps 564)]
    (let [[_ m] (opened path)
          world (:world m)
          i (:plan/intent world)]
      (println "FIGHT"
               path
               (pr-str {:pos (:player/pos world)
                        :feet (intent/feet world)
                        :health (:player/health world)
                        :on-ground (:player/on-ground? world)
                        :lava (:player/in-lava? world)
                        :intent (select-keys i
                                             [:intent/kind :intent/stage :intent/target
                                              :intent/to-the-end? :intent/status])}))
      (doseq [[eid e] (:world/entities world)
              :when (= :blaze (entity/kind e))]
        (println "BLAZE" (blaze-line world eid e))))))

(defn stamp
  "The facts of world a step-trace compares: the feet, the health, whether it is in lava, and
   the running intent's kind, stage and target."
  [world]
  (let [i (:plan/intent world)]
    [(:player/pos world) (:player/health world) (boolean (:player/in-lava? world)) (:intent/kind i)
     (:intent/stage i) (:intent/target i) (:intent/status i)]))

(defn trace-flee
  "From the moment of each snapshot under test/issues/514, a quarter second at a time until the
   plan ends or the window does: one line whenever the feet, the health, the lava or the intent
   changes, and whether a step straight away from the back-off's target is a drop."
  []
  (doseq [path (snaps 514)]
    (let [[snap m] (opened path)
          until (+ (:t m) (simrace/window-of snap))]
      (println "FLEE" path "from" (:t m) "until" until)
      (loop [m m
             prev nil]
        (let [world (:world m)
              sim (:sim m)
              now (:time/now world)
              cur (stamp world)
              i (:plan/intent world)]
          (when (not= cur prev)
            (println "STEP"
                     now
                     (pr-str cur)
                     (when (= :back-off (:intent/kind i))
                       (when-let [e (get-in world [:world/entities (:intent/target i)])]
                         (pr-str {:away (combat/away-point world e)
                                  :ledge (intent/ledge-ahead? world
                                                              (combat/away-point world e))})))))
          (when (and (< now until) (not (plan/failed? world)) (not (plan/done? world)))
            (recur (sim/play-on simrace/step world sim (constantly false) 250 now [] nil) cur)))))))

(defn -main
  "Trace the fight snapshots, then the flee snapshots."
  [& _]
  (trace-fight)
  (trace-flee)
  (shutdown-agents))
