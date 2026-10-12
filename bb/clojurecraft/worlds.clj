(ns clojurecraft.worlds
  "Build one gym landing set's world, once: boot a fresh server, find and pregenerate the set's
   landings (clojure -M:gym landings), flush to disk, insist no forceload is left behind, stop,
   and leave the world in cache/worlds/<set> with its landings.edn, ready for
   actions/cache/save (world.yml). The namespace is worlds because clojurecraft.world is the
   test fixture that builds chunk columns.

     SET=A RCON_PASS=secret bb prepare-world

   The server is on 25565, RCON on 25575, with a 6144 MB heap unless HEAP_MB says otherwise."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.server :as server]))

(def ports {:port "25565" :rcon-port "25575"})   ; a runner of its own: nothing else listens there

(def discarded
  "What a trial writes again on start, and logs that would only bloat the cache."
  ["server.properties" "server.pid" "server.log" "eula.txt" "logs"])

(defn repeats
  "The landings, [x z y] each, that appear more than once."
  [landings]
  (for [[l n] (frequencies landings) :when (> n 1)] l))

(defn forceload-clear?
  "Does a `forceload query` reply say no chunk is forceloaded?"
  [reply]
  (str/includes? reply "No force loaded"))

;;;; I/O: the server, RCON, the world directory ;;;;

(defn rcon!
  "One RCON command to the build's server; its reply."
  [pass & words]
  (:out (apply p/shell
               {:out :string :dir server/root}
               "clojure"
               "-M:rcon"
               "--host"
               "127.0.0.1"
               "--port"
               (:rcon-port ports)
               "--pass"
               pass
               words)))

(defn build!
  "Build the set's world into cache/worlds/<set>; throws when a landing repeats or a forceload
   is left behind (the server is stopped either way)."
  [set-name pass heap-mb]
  (let [world (str server/root "/cache/worlds/" set-name)
        opts (merge (server/env-opts) ports {:rcon-pass pass :world world :heap-mb heap-mb})
        landings-file (str world "/landings.edn")]
    (fs/delete-tree world)
    (fs/create-dirs world)
    (when-not (server/start! opts) (throw (ex-info "server did not start" {:world world})))
    (try (p/shell {:dir server/root}
                  "clojure"
                  "-M:gym" "landings"
                  "--set" set-name
                  "--plan" (str server/root "/ci/gym.edn")
                  "--rcon-port" (:rcon-port ports)
                  "--rcon-pass" pass
                  "--out" landings-file)
         (let [landings (edn/read-string (slurp landings-file))]
           (println "landings:" (pr-str landings))
           (when (seq (repeats landings))
             (throw (ex-info "landings repeat a spot" {:repeats (vec (repeats landings))}))))
         (rcon! pass "save-all" "flush")
         (let [reply (rcon! pass "forceload" "query")]
           (print reply)
           (spit (str server/root "/forceload.txt") reply)
           (when-not (forceload-clear? reply)
             (throw (ex-info "a forceload was left behind" {:reply reply}))))
         (finally (server/stop! opts)))
    (doseq [f discarded] (fs/delete-tree (fs/path world f)))
    (println (str/trim (:out (p/shell {:out :string} "du" "-sh" world))))))

(defn -main
  "Build the world for SET with RCON_PASS (and HEAP_MB) from the environment."
  []
  (let [env #(System/getenv %)]
    (doseq [k ["SET" "RCON_PASS"]] (when-not (env k) (throw (ex-info (str "set " k) {}))))
    (build! (env "SET") (env "RCON_PASS") (or (env "HEAP_MB") "6144"))))
