(ns clojurecraft.server
  "Start or stop a vanilla 26.1.2 server: CI's gym trials, the full run, the gym world build, a
   plan's jobs, and this Mac's own server all go through here.

     PORT=25565 RCON_PORT=25575 RCON_PASS=secret WORLD=work/world bb server start
     WORLD=work/world bb server stop
     bb local-server start|stop      ; this Mac: 25571 / RCON 25581, world data/local-world

   The environment (`bb server`): PORT RCON_PORT RCON_PASS WORLD, required for start (WORLD for
   stop); JAVA (default: java on PATH, else the flake's), HEAP_MB (2048), JAR (data/local-server/
   server-26.1.2.jar, downloaded and sha1-checked when missing), LEVEL_SEED (typecraft),
   DIFFICULTY (normal: mobs and hunger, issue #10)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def jar-url
  "https://piston-data.mojang.com/v1/objects/97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51/server.jar")
(def jar-sha1 "97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51")   ; the 26.1.2 server jar, as Mojang publishes it
(def up-timeout-s 180)                                        ; a fresh world generates its spawn in this
(def stop-timeout-s 60)                                       ; then the kill is forced
(def local-ports {:port 25571 :rcon-port 25581})              ; ruststeve 25567/25568, steve 25569/25570: never those

(def root
  "The repo, wherever bb was started from."
  (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))

(defn properties
  "server.properties for a run, as ordered [key value] pairs. online-mode off: the bot logs in
   with an offline UUID; spawn-protection 0: no op needed to dig at spawn; pause-when-empty 0:
   26.x pauses an empty server after 60 s, and forceloaded landings would stop generating.
   max-tick-time: the watchdog's limit on one tick, 60000 ms unless MAX_TICK_TIME says (-1 turns
   it off: a race's locate biome far from any forest took a 60 s tick and the watchdog killed
   the server under ten bots). max-players: 20 unless MAX_PLAYERS says (a capacity ramp seats 150).
   network-compression-threshold: vanilla's 256 unless COMPRESSION_THRESHOLD says (-1, none: a
   race's bots are on the same machine, where zlib is only CPU, the server deflating every chunk
   for every bot and each bot inflating it). sync-chunk-writes: vanilla's true unless
   SYNC_CHUNK_WRITES says (false: a race's world is thrown away, and a synchronous write per
   saved chunk is time the server's one tick thread waits)."
  [{:keys [port rcon-port rcon-pass level-seed difficulty max-tick-time max-players
           compression-threshold sync-chunk-writes]}]
  [["online-mode" false] ["allow-flight" true] ["level-seed" level-seed]
   ["level-type" "minecraft:normal"] ["difficulty" difficulty] ["gamemode" "survival"] ["pvp" false]
   ["spawn-protection" 0] ["view-distance" 6] ["simulation-distance" 4] ["server-port" port]
   ["server-ip" "127.0.0.1"] ["pause-when-empty-seconds" 0] ["enable-rcon" true]
   ["rcon.port" rcon-port] ["rcon.password" rcon-pass] ["enforce-secure-profile" false]
   ["motd" "clojurecraft"] ["max-tick-time" (or max-tick-time 60000)]
   ["max-players" (or max-players 20)]
   ["network-compression-threshold" (or compression-threshold 256)]
   ["sync-chunk-writes" (or sync-chunk-writes true)]])

(defn properties-text
  "The pairs as the file's lines."
  [pairs]
  (str/join (for [[k v] pairs] (str k "=" v "\n"))))

(defn sha1
  "The SHA-1 of a file, as lower-case hex."
  [file]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (with-open [in (io/input-stream (fs/file file))]
      (let [buf (byte-array 65536)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update digest buf 0 n) (recur))))))
    (str/join (map #(format "%02x" %) (.digest digest)))))

;;;; I/O: the jar, the world directory, the server process ;;;;

(defn java-bin!
  "java to run the server with: JAVA, else java on PATH (the flake's shell, CI), else the
   flake's own (scripts/nix-tool jdk, which builds it the first time)."
  [env-java]
  (cond env-java env-java
        (fs/which "java") "java"
        :else
        (str (str/trim (:out (p/shell {:out :string} (str root "/scripts/nix-tool") "jdk")))
             "/java")))

(defn ensure-jar!
  "The server jar at path, downloaded when missing or not the published jar."
  [path]
  (when-not (and (fs/exists? path) (= jar-sha1 (sha1 path)))
    (println "downloading server.jar")
    (fs/create-dirs (fs/parent path))
    (let [tmp (str path ".tmp")]
      (io/copy (:body (http/get jar-url {:as :stream})) (fs/file tmp))
      (when-not (= jar-sha1 (sha1 tmp)) (throw (ex-info "server.jar sha1 mismatch" {:url jar-url})))
      (fs/move tmp path {:replace-existing true}))))

(defn alive?
  "Is the process with this pid running?"
  [pid]
  (zero? (:exit (p/shell {:continue true :out :string :err :string} "kill" "-0" (str pid)))))

(defn tail
  "The last n lines of a file, as one string."
  [file n]
  (str/join "\n" (take-last n (str/split-lines (slurp file)))))

(defn start!
  "Write the world's eula.txt and server.properties, start the server detached (it outlives bb),
   and wait for its \"Done (\" line. Returns true when it came up."
  [{:keys [world jar java heap-mb java-opts] :as opts}]
  (ensure-jar! jar)
  (fs/create-dirs world)
  (spit (fs/file world "eula.txt") "eula=true\n")
  (spit (fs/file world "server.properties") (properties-text (properties opts)))
  (let [log (fs/file world "server.log")
        proc (apply p/process
                    {:dir world :out log :err :out}
                    (concat ["nohup" java "-Xms512M" (str "-Xmx" heap-mb "M") "-XX:+UseG1GC"]
                            (remove str/blank? (str/split (or java-opts "") #"\s+")) ; SERVER_JAVA_OPTS: a capacity run's flight recording
                            ["-jar" jar "nogui"]))
        pid (.pid (:proc proc))]
    (spit (fs/file world "server.pid") (str pid "\n"))
    (println (str "server pid " pid ", waiting for Done"))
    (loop [s 1]
      (cond (and (fs/exists? log) (str/includes? (slurp log) "Done ("))
            (do (println (str "server up after " s "s")) true)
            (not (alive? pid)) (do (println "server died:") (println (tail log 40)) false)
            (>= s up-timeout-s)
            (do (println (str "server did not come up in " up-timeout-s "s"))
                (println (tail log 40))
                false)
            :else (do (Thread/sleep 1000) (recur (inc s)))))))

(defn stop!
  "Stop the world's server, forcing it after stop-timeout-s; nothing when none is running."
  [{:keys [world]}]
  (let [pid-file (fs/file world "server.pid")]
    (when (fs/exists? pid-file)
      (let [pid (str/trim (slurp pid-file))]
        (when (alive? pid)
          (p/shell "kill" pid)
          (loop [s 0]
            (when (and (< s stop-timeout-s) (alive? pid)) (Thread/sleep 1000) (recur (inc s))))
          (when (alive? pid) (p/shell {:continue true} "kill" "-9" pid))))
      (fs/delete-if-exists pid-file))))

(defn env-opts
  "The options from the environment, as `bb server` takes them."
  []
  (let [env #(System/getenv %)]
    {:port (env "PORT")
     :rcon-port (env "RCON_PORT")
     :rcon-pass (env "RCON_PASS")
     :world (env "WORLD")
     :java (java-bin! (env "JAVA"))
     :heap-mb (or (env "HEAP_MB") "2048")
     :jar (or (env "JAR") (str root "/data/local-server/server-26.1.2.jar"))
     :level-seed (or (env "LEVEL_SEED") "typecraft")
     :difficulty (or (env "DIFFICULTY") "normal")
     :max-tick-time (env "MAX_TICK_TIME")
     :max-players (env "MAX_PLAYERS")
     :compression-threshold (env "COMPRESSION_THRESHOLD")
     :sync-chunk-writes (env "SYNC_CHUNK_WRITES")
     :java-opts (env "SERVER_JAVA_OPTS")}))

(defn local-opts
  "This Mac's server: its ports, a random RCON password kept in data/local-server/rcon.pass
   (gitignored; `cat` it for rcon), and the world in data/local-world."
  []
  (let [pass-file (fs/file root "data/local-server/rcon.pass")]
    (when-not (and (fs/exists? pass-file) (pos? (fs/size pass-file)))
      (fs/create-dirs (fs/parent pass-file))
      (spit pass-file (str/join (repeatedly 48 #(rand-nth "0123456789abcdef"))))
      (fs/set-posix-file-permissions pass-file "rw-------"))
    (merge (env-opts)
           local-ports
           {:rcon-pass (str/trim (slurp pass-file)) :world (str root "/data/local-world")})))

(defn command!
  "start or stop with opts; exit 1 when a start fails, 2 on an unknown command."
  [cmd opts]
  (case cmd
    "start"
    (do (doseq [k [:port :rcon-port :rcon-pass :world]]
          (when-not (opts k)
            (throw (ex-info (str "set " (str/upper-case (str/replace (name k) "-" "_"))) {}))))
        (when-not (start! opts) (System/exit 1)))
    "stop" (do (when-not (:world opts) (throw (ex-info "set WORLD" {}))) (stop! opts))
    (do (println "usage: bb server start|stop") (System/exit 2))))
