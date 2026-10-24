(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.combat :as combat]
         '[clojurecraft.entity :as entity]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)) (flush))

(defn ask
  [label f]
  (try (out label (pr-str (f))) (catch Exception e (out label "THREW" (ex-message e)))))

(doseq [path ["test/issues/69/R3-27-1.edn.gz" "test/issues/69/R4-21-1.edn.gz"]]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        world (:world m)
        intent (:plan/intent world)
        eid (:intent/target intent)
        e (get-in world [:world/entities eid])]
    (out "==" path)
    (ask "intent"
         #(select-keys intent
                       [:intent/kind :intent/goal :intent/stage :intent/target :intent/status]))
    (ask "ekind" #(entity/kind e))
    (ask "etype" #(:entity/type e))
    (ask "epos" #(:entity/pos e))
    (ask "ehealth" #(:entity/health e))
    (ask "dead" #(entity/dead? e))
    (ask "gone" #(entity/gone? world eid))
    (ask "hostile" #(entity/hostile? e))
    (ask "threats" #(sort (keys (combat/threats world))))
    (ask "hittable" #(combat/hittable? world e))
    (ask "strikable" #(combat/strikable? world e))
    (ask "approachable" #(combat/approachable? world e))
    (ask "route-walk" #(combat/route-walk world e))
    (ask "botpos" #(:player/pos world))
    (let [played (sim/play-from simrace/step m (constantly false) 3000 nil nil)
          end (:world played)]
      (ask "end-pos" #(:player/pos end))
      (ask "end-health" #(:player/health end))
      (ask "end-intent"
           #(select-keys (:plan/intent end)
                         [:intent/kind :intent/stage :intent/target :intent/status]))
      (doseq [line (take-last 14 (simrace/story end (:time/now world)))]
        (out "PLAY" (pr-str line))))))

(shutdown-agents)
(System/exit 0)
