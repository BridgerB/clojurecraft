(ns clojurecraft.capture
  "Real worlds for the server model: a real server's chunk columns round each landing of a
   landing set, saved byte for byte as the server sent them, one world file a landing.

     clojure -M:capture --port 25565 --rcon-port 25575 --rcon-pass S \\
       --landings cache/worlds/R1/landings.edn --set R1 --out cache/sim-worlds/R1 [--radius 10]

   The model (sim.clj) then holds such a world whole and plays the bot in it with no Java
   server: the same terrain, biomes and encoding the race's servers send, the same landings every
   commit, thousands of runs a runner. Nothing is translated: a column is the bytes of a
   level-chunk-with-light, which the bot decodes as it decodes any server's.

   How a landing is taken: a client of its own (the bot's own connection and reducer, main's
   loop, with a tap that keeps every chunk packet's bytes) joins, is made a spectator and is
   teleported over stops a view apart until every column within radius chunks of the landing
   has arrived; it leaves, and the columns go to <out>/<n>.edn.gz as
   {:capture/set :capture/landing [x z y] :capture/radius :capture/columns {[cx cz] bytes}}, bytes
   printed as the recordings print them (#clojurecraft/bytes, record/readers reads them)."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.cli :as cli]
            [clojurecraft.conn :as conn]
            [clojurecraft.game :as game]
            [clojurecraft.landings :as landings]
            [clojurecraft.main :as main]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.record :as record])
  (:import (java.io PushbackReader)
           (java.util.zip GZIPInputStream GZIPOutputStream)))

(set! *warn-on-reflection* true)

(def default-radius 12)               ; chunks taken round a landing on each axis: 25x25 columns, 192 blocks out
(def view 6)                          ; the view distance the client asks for (game's client-information)
(def stop-chunks (* 2 view))          ; stops this many chunks apart: each sees the 13 columns across round it
(def settle-ms 2000)                  ; no new column this long after a teleport: the server sent what it will
(def stop-timeout-ms 30000)           ; the most one stop waits
(def capture-name "Clj_capture")      ; the capture client's player

;; ---------------------------------------------------------------- what to take

(defn chunk-of
  "The chunk column coordinate of block coordinate b."
  [b]
  (bit-shift-right (long b) 4))

(defn axis-stops
  "Chunk coordinates of stops along one axis so every chunk from lo to hi is within view of one:
   the first view in from lo, then stop-chunks apart, the last view in from hi."
  [lo hi]
  (let [first-stop (+ lo view)
        last-stop (max first-stop (- hi view))]
    (vec (distinct (concat (range first-stop last-stop stop-chunks) [last-stop])))))

(defn stops
  "The block columns [x z] the client is teleported to for the landing at [x z _]: a grid of
   stops whose views cover every column within radius chunks of the landing's."
  [[x z _] radius]
  (let [[cx cz] [(chunk-of x) (chunk-of z)]]
    (vec (for [sx (axis-stops (- cx radius) (+ cx radius))
               sz (axis-stops (- cz radius) (+ cz radius))]
           [(+ 8 (* 16 sx)) (+ 8 (* 16 sz))]))))

(defn within
  "The columns of got ({[cx cz] bytes}) within radius chunks of the landing's, on each axis."
  [got [x z _] radius]
  (let [[cx cz] [(chunk-of x) (chunk-of z)]]
    (into {}
          (filter (fn [[[kx kz] _]] (and (<= (abs (- kx cx)) radius) (<= (abs (- kz cz)) radius))))
          got)))

(defn complete?
  "Has every column within radius of the landing arrived?"
  [got landing radius]
  (= (* (inc (* 2 radius)) (inc (* 2 radius))) (count (within got landing radius))))

(defn world
  "The world file's value for a landing of set: the landing, the radius and its columns."
  [set-name landing radius columns]
  {:capture/set set-name :capture/landing landing :capture/radius radius :capture/columns columns})

;;;; I/O: a client per landing, RCON, the world files ;;;;

(defn chunk-tap
  "A tap (main/apply-event!'s) that keeps every level-chunk packet's bytes in got, an atom of
   {[cx cz] bytes}, and nothing else."
  [got]
  {:write (fn [{:event/keys [packet]}]
            (when (= :level-chunk-with-light (:packet/name packet))
              (swap! got assoc [(:x packet) (:z packet)] (:data packet))))
   :effects (fn [_])
   :result (fn [_])
   :close (fn [])})

(defn wait-loaded!
  "Wait until the client's world says the player is loaded, at most ms; whether it is."
  [world* ms]
  (let [give-up (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (:player/loaded? @world*) true
            (> (System/currentTimeMillis) give-up) false
            :else (do (Thread/sleep 100) (recur))))))

(defn settle!
  "Wait until got stops growing for settle-ms, at most stop-timeout-ms."
  [got]
  (let [give-up (+ (System/currentTimeMillis) stop-timeout-ms)]
    (loop [n (count @got)
           quiet-since (System/currentTimeMillis)]
      (Thread/sleep 250)
      (let [m (count @got)
            now (System/currentTimeMillis)]
        (cond (> now give-up) m
              (not= m n) (recur m now)
              (>= (- now quiet-since) settle-ms) m
              :else (recur n quiet-since))))))

(defn take-landing!
  "Connect a fresh client, tour the stops round landing over RCON and return the columns within
   radius of it, {[cx cz] bytes}."
  [{:keys [host port rcon-port rcon-pass]} landing radius]
  (let [got (atom {})
        opts {:host host :port port :name capture-name}
        c (conn/open! opts)
        world* (atom (game/init opts))
        done (promise)
        loop-done
        (async/thread
         (main/apply-event!
          world*
          {:event/kind :start :start/host host :start/port port :start/name capture-name}
          (:out c)
          (chunk-tap got))
         (main/run-loop! c (async/chan 1) world* (fn [_] (realized? done)) (chunk-tap got)))]
    (try (rcon/with-rcon
          host
          rcon-port
          rcon-pass
          (fn [rc]
            (when-not (wait-loaded! world* 60000)
              (throw (ex-info "the capture client never loaded" {:landing landing})))
            (rcon/command! rc (str "gamemode spectator " capture-name))
            (doseq [[x z] (stops landing radius)]
              (rcon/command! rc (str "tp " capture-name " " x " 200 " z))
              (settle! got))
            (let [columns (within @got landing radius)]
              (cli/log "landing" landing
                       ":" (count columns)
                       "columns" (if (complete? @got landing radius) "(complete)" "(INCOMPLETE)"))
              columns)))
         (finally (deliver done true) (async/<!! loop-done) ((:close! c))))))

(defn write-world!
  "Print a world value to path, gzipped, bytes as #clojurecraft/bytes."
  [path w]
  (io/make-parents path)
  (with-open [o (io/writer (GZIPOutputStream. (io/output-stream path)))]
    (binding [*out* o] (prn w))))

(defn read-world
  "The world value a world file holds, its column bytes as byte arrays."
  [path]
  (with-open [r (PushbackReader. (io/reader (GZIPInputStream. (io/input-stream path))))]
    (edn/read {:readers record/readers} r)))

(defn -main
  "Take every landing of --landings from the server at --port (RCON --rcon-port, --rcon-pass)
   into --out, one world file a landing, named by its place in the set (1.edn.gz ...)."
  [& args]
  (let [{:keys [host port rcon-port rcon-pass landings set out]
         chunks :radius
         :or {host "127.0.0.1"}}
        (cli/options args)
        server {:host host
                :port (Long/parseLong port)
                :rcon-port (Long/parseLong rcon-port)
                :rcon-pass rcon-pass}
        radius (Long/parseLong (or chunks (str default-radius)))
        ls (landings/read-landings landings)]
    (doseq [[i landing] (map-indexed vector ls)]
      (let [columns (take-landing! server landing radius)]
        (write-world! (str (io/file out (str (inc i) ".edn.gz")))
                      (world set landing radius columns))))
    (shutdown-agents)))
