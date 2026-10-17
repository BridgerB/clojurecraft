(ns clojurecraft.capture-nether
  "A real Nether for the server model (issue #353): one region of a race set's Nether, round
   every landing's Nether point (its x/8, z/8) and margin chunks out from them, saved byte for
   byte as the server sent it, one file a set.

     clojure -M -m clojurecraft.capture-nether --port 25565 --rcon-port 25575 --rcon-pass S \\
       --landings cache/worlds/R1/landings.edn --set R1 --out cache/sim-nether/R1.edn.gz [--margin 32]

   The sim race's worlds (capture.clj) are the overworld round each landing; a bot through its
   portal needs the Nether the same seed's server gives a real bot there. The model takes this
   region as its :sim/nether (simrace/sim-nether), stands the player where the server would place
   the linked portal (sim/arrival) and streams the columns round it as a server does.

   How it is taken: capture.clj's client (main's loop and reducer, settle! and its stops' spacing)
   joins, is made a spectator, is teleported into the Nether and over stops a view apart until
   every column of the region has arrived, touring again the stops whose columns the server was
   still generating. Only columns that arrive while the client is in the Nether are kept: the
   overworld's round the spawn it joined at share their keys. Then the fortress bricks the region
   holds are measured from each landing's Nether point (reach): what a run there can find. Not
   locate structure: its ring search named fortresses 600 to 1100 blocks off for R4's landings
   whose captured bricks were 0 to 250 away (simworld run 39017474789).

   Its own namespace and its own cache key: capture.clj, landings.clj, fixture.clj and the
   server's files are the overworld worlds' key, and an edit to one makes every lane miss its
   worlds. The file is {:nether/set :nether/landings [[x z y] ...] :nether/region [cx0 cz0 cx1
   cz1] :nether/columns {[cx cz] bytes}}, bytes
   printed as the recordings print them."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.conn :as conn]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.landings :as landings]
            [clojurecraft.main :as main]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.sim :as sim]))

(set! *warn-on-reflection* true)

(def default-margin 32)               ; chunks past the landings' Nether points: fortress/sight-radius, 512 blocks, as far as a brick on record is a target
(def stop-y 100)                      ; the height the spectator tours at: inside the Nether, under its roof
(def passes 3)                        ; tours at most: the first, then again over stops still missing columns
(def bands [96 192 256 512])          ; blocks from a Nether point the reach summary counts landings within (96: the view a server shows)

;; ---------------------------------------------------------------- what to take

(defn nether-point
  "The Nether block column [x z] a portal on landing [x z _] leads to (sim/exit-point: the
   overworld's coordinates over 8)."
  [[x z _]]
  (let [[nx _ nz] (sim/exit-point [x 0 z])] [nx nz]))

(defn region
  "The chunk square [cx0 cz0 cx1 cz1] holding every landing's nether-point, margin chunks wider
   on each side."
  [ls margin]
  (let [cs (map (fn [l] (mapv capture/chunk-of (nether-point l))) ls)
        xs (map first cs)
        zs (map second cs)]
    [(- (reduce min xs) margin) (- (reduce min zs) margin) (+ (reduce max xs) margin)
     (+ (reduce max zs) margin)]))

(defn columns-of
  "Every chunk column [cx cz] of region, row by row."
  [[cx0 cz0 cx1 cz1]]
  (for [cx (range cx0 (inc cx1)) cz (range cz0 (inc cz1))] [cx cz]))

(defn stops
  "The block columns [x z] the client is teleported to so every column of region is within view
   of one (capture/axis-stops on each axis)."
  [[cx0 cz0 cx1 cz1]]
  (vec (for [sx (capture/axis-stops cx0 cx1)
             sz (capture/axis-stops cz0 cz1)]
         [(+ 8 (* 16 sx)) (+ 8 (* 16 sz))])))

(defn within
  "The columns of got ({[cx cz] bytes}) inside region."
  [got [cx0 cz0 cx1 cz1]]
  (into {} (filter (fn [[[x z] _]] (and (<= cx0 x cx1) (<= cz0 z cz1)))) got))

(defn missing
  "The columns of region got lacks, in order."
  [got region]
  (remove #(contains? got %) (columns-of region)))

(defn again
  "The stops (of stops) to tour again for the missing columns (gaps): each one's nearest stop, by chunks
   on the farther axis, distinct, in the stops' order."
  [ss gaps]
  (let [nearest (fn [[cx cz]]
                  (apply min-key
                         (fn [[x z]]
                           (max (abs (- (capture/chunk-of x) cx))
                                (abs (- (capture/chunk-of z) cz))))
                         ss))
        wanted (set (map nearest gaps))]
    (filterv wanted ss)))

(defn nether
  "The Nether file's value for set-name: the landings it was taken for, the region and the
   columns."
  [set-name ls region columns]
  {:nether/set set-name :nether/landings (vec ls) :nether/region region :nether/columns columns})

;; ---------------------------------------------------------------- what a run there can find

(defn bricks
  "Every fortress block in decoded columns at or over fortress/brick-min-y, [x y z]: the bricks a
   run in this Nether can make its target (fortress/bricks)."
  [decoded]
  (for [[x y z] (chunk/find-blocks decoded fortress/brick?)
        :when (>= y fortress/brick-min-y)]
    [x y z]))

(defn across
  "The distance across (x and z) from block column [x z] to block [bx _ bz]."
  [[x z] [bx _ bz]]
  (math/sqrt (+ (* (- bx x) (- bx x)) (* (- bz z) (- bz z)))))

(defn reach
  "Each landing's Nether point among the bricks found in a captured Nether: {:landing :point
   :brick the distance across to the nearest one, nil with none}, a landing each, in order."
  [ls found]
  (mapv (fn [l]
          (let [p (nether-point l)]
            {:landing l
             :point p
             :brick (when (seq found) (math/round (reduce min (map #(across p %) found))))}))
        ls))

(defn summary
  "How many landings of reach's have a brick within each of bands blocks, and how many none in
   the region: {:landings n :within {blocks n} :none n}."
  [measured]
  {:landings (count measured)
   :within (into (sorted-map)
                 (for [b bands]
                   [b
                    (count (filter #(some-> (:brick %)
                                            (<= b))
                                   measured))]))
   :none (count (remove :brick measured))})

;;;; I/O: the capture client, RCON, the file ;;;;

(defn nether-tap
  "A tap (main/apply-event!'s) that keeps in got ({[cx cz] bytes}) the bytes of every level-chunk
   packet that arrives while the client's world says it is in the Nether, and nothing else."
  [got world*]
  {:write (fn [{:event/keys [packet]}]
            (when (and (= :level-chunk-with-light (:packet/name packet))
                       (= dimension/nether (dimension/of @world*)))
              (swap! got assoc [(:x packet) (:z packet)] (:data packet))))
   :effects (fn [_])
   :result (fn [_])
   :close (fn [])})

(defn tour!
  "Teleport the capture client to each of ss in the Nether, waiting at each until its columns
   stop arriving (capture/settle!)."
  [rc ss got]
  (doseq [[x z] ss]
    (rcon/command!
     rc
     (str "execute in minecraft:the_nether run tp " capture/capture-name " " x " " stop-y " " z))
    (capture/settle! got)))

(defn take-region!
  "Connect a fresh client, tour the region's stops in the Nether (again over the stops near
   columns still missing, passes tours at most) and return its columns, {[cx cz] bytes}."
  [{:keys [host port rcon-port rcon-pass]} region]
  (let [got (atom {})
        opts {:host host :port port :name capture/capture-name}
        c (conn/open! opts)
        world* (atom (game/init opts))
        done (promise)
        tap (nether-tap got world*)
        loop-done
        (async/thread
         (main/apply-event!
          world*
          {:event/kind :start :start/host host :start/port port :start/name capture/capture-name}
          (:out c)
          tap)
         (main/run-loop! c (async/chan 1) world* (fn [_] (realized? done)) tap))]
    (try (rcon/with-rcon
          host
          rcon-port
          rcon-pass
          (fn [rc]
            (when-not (capture/wait-loaded! world* 60000)
              (throw (ex-info "the capture client never loaded" {:region region})))
            (rcon/command! rc (str "gamemode spectator " capture/capture-name))
            (let [ss (stops region)]
              (cli/log "region"
                       (pr-str region)
                       ":"
                       (count (columns-of region))
                       "columns,"
                       (count ss)
                       "stops")
              (reduce (fn [_ pass]
                        (let [m (missing @got region)]
                          (cond (empty? m) (reduced nil)
                                :else
                                (do (cli/log "tour" pass ":" (count m) "columns missing")
                                    (tour! rc (if (= 1 pass) ss (again ss m)) got)))))
                      nil
                      (range 1 (inc passes)))
              (let [columns (within @got region)]
                (cli/log "region" (pr-str region)
                         ":" (count columns)
                         "columns"
                         (if (empty? (missing columns region)) "(complete)" "(INCOMPLETE)"))
                columns))))
         (finally (deliver done true) (async/<!! loop-done) ((:close! c))))))

(defn -main
  "Take the Nether of --landings (--set) from the server at --port (RCON --rcon-port,
   --rcon-pass) into the file --out, margin --margin chunks (default-margin), and print each
   landing's reach (a NETHER line) and their summary (NETHERSUM)."
  [& args]
  (let [{:keys [host port rcon-port rcon-pass landings out margin]
         set-name :set
         :or {host "127.0.0.1"}}
        (cli/options args)
        server {:host host
                :port (Long/parseLong port)
                :rcon-port (Long/parseLong rcon-port)
                :rcon-pass rcon-pass}
        ls (landings/read-landings landings)
        r (region ls (if margin (Long/parseLong margin) default-margin))
        columns (take-region! server r)
        measured (reach ls (bricks (update-vals columns chunk/decode)))]
    (doseq [m measured] (println "NETHER" (pr-str m)))
    (println "NETHERSUM"
             (pr-str (assoc (summary measured) :set set-name :region r :columns (count columns))))
    (capture/write-world! (str (io/file out)) (nether set-name ls r columns))
    (shutdown-agents)))
