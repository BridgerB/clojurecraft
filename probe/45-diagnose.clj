(require '[clojure.string :as str]
         '[clojurecraft.build :as build]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.stairs :as stairs])

(defn out [& xs] (println (str/join " " (map pr-str xs))))
(defn ask
  [label f]
  (try (out label (f)) (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))

(doseq [path ["test/issues/45/R4-33-1.edn.gz" "test/issues/45/R4-41-1.edn.gz"]]
  (try (let [snap (capture/read-world path)
             prepared (simrace/prepared (capture/read-world (:snapshot/file snap)))
             [world _] (simrace/moment snap prepared)
             i (:plan/intent world)
             op (build/next-op world
                               (build/plan-for (:intent/build i)
                                               (:intent/origin i)
                                               (:intent/facing i)
                                               (:intent/access i 0)))]
         (out "==" path (:snapshot/signature snap))
         (out "feet" (intent/feet world) "pos" (:player/pos world))
         (out "intent"
              (select-keys i
                           [:intent/kind :intent/build :intent/origin :intent/facing :intent/access
                            :intent/op :intent/child]))
         (out "op" (select-keys op [:build/op :build/pos :build/stand :build/item :build/step]))
         (ask "reachable" #(build/reachable-stand world (:build/stand op) (:build/pos op)))
         (ask "reachable-designated"
              #(build/reachable-stand world (:build/stand op) (:build/stand op)))
         (ask "nearest" #(build/nearest-stand-cell world i (:build/pos op)))
         (ask "approach" #(build/approach-stand world i op))
         (ask "routable"
              #(intent/routable?
                world
                {:intent/kind :walk :intent/target (:build/stand op) :intent/for :build}))
         (ask "out" #(stairs/out? world (intent/feet world))))
       (catch Exception e (out "==" path "THREW" (.getName (class e)) (ex-message e)))))
(shutdown-agents)
