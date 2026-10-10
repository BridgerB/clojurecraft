(ns clojurecraft.landings
  "Landing sets for the gym: fixed, pre-checked places in a pregenerated world, so run k of every
   batch lands on the same spot and batches compare like with like (paired runs).

   A set is data in ci/gym.edn: a centre far from world spawn, n grid cells spacing apart, the
   biome each cell snaps to, and the radius pregenerated around each landing. landings! builds
   the set against a running server over RCON: each cell snaps to the nearest biome match, the
   surface there is found with a marker entity, and the spot is kept only when it is high
   enough and none of the harness's landing checks (water, lava, tree, buried) passes; otherwise
   the harness's nearby offsets are tried. The chunks around each landing are then generated and
   released. The result is [[x z y] ...], saved with the world in the gym's cache."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.harness :as harness]
            [clojurecraft.rcon :as rcon]))

(def min-y 55)                          ; lower surfaces are ravines and caves, not ground
(def max-forceload-chunks 256)          ; the most chunks one forceload command accepts
(def marker-tag "clj_gym")              ; tags the marker used to read a surface height

(defn grid
  "n cell centres around centre, spacing apart, row by row on a square as small as fits n."
  [[cx cz] n spacing]
  (let [side (long (Math/ceil (Math/sqrt n)))
        half (quot side 2)]
    (vec (take n (for [row (range side) col (range side)]
                   [(+ cx (* spacing (- col half))) (+ cz (* spacing (- row half)))])))))

(defn parse-located
  "[x z] from a `locate` reply, or nil when nothing was found."
  [reply]
  (when-let [[_ x z] (re-find #"\[(-?\d+), (?:~|-?\d+), (-?\d+)\]" (str reply))]
    [(parse-long x) (parse-long z)]))

(defn square
  "[x0 z0 x1 z1]: the block square of radius r around [x z]."
  [[x z] r]
  [(- x r) (- z r) (+ x r) (+ z r)])

(defn chunk-count
  "How many chunk columns a block square touches."
  [[x0 z0 x1 z1]]
  (* (inc (- (Math/floorDiv (long x1) 16) (Math/floorDiv (long x0) 16)))
     (inc (- (Math/floorDiv (long z1) 16) (Math/floorDiv (long z0) 16)))))

(defn fit?
  "Is a surface position a landing? High enough, and no landing check (answers to
   harness/checks, [[reason reply] ...]) passed."
  [[_ y _] answers]
  (and (>= y min-y) (nil? (harness/bad-landing answers))))

(defn read-set
  "The landing-set definition named set-name in a plan file (ci/gym.edn), or nil."
  [plan-path set-name]
  (get-in (edn/read-string (slurp plan-path)) [:landing-sets set-name]))

(defn read-landings
  "The [[x z y] ...] a landings file holds."
  [path]
  (edn/read-string (slurp (io/file path))))

(defn landing-for
  "The landing run (1-based) uses: runs past the end of the set wrap around, so a run number
   always names the same landing."
  [landings run]
  (nth landings (mod (dec run) (count landings))))

;;;; I/O: RCON against a running server ;;;;

(defn surface!
  "[x y z] of the ground at column x z (the highest motion-blocking block that is not leaves),
   read with a marker entity that is removed again; nil when the column would not load."
  [rc [x z]]
  (rcon/command rc (str "forceload add " x " " z))
  (try
    (when (harness/wait-loaded! rc x z)
      (rcon/command rc (str "execute positioned " x " 0 " z " positioned over motion_blocking_no_leaves"
                            " run summon minecraft:marker ~ ~ ~ {Tags:[\"" marker-tag "\"]}"))
      (let [pos (harness/parse-pos (rcon/command rc (str "data get entity @e[type=minecraft:marker,tag=" marker-tag ",limit=1] Pos")))]
        (rcon/command rc (str "kill @e[type=minecraft:marker,tag=" marker-tag "]"))
        (when pos (mapv #(long (Math/floor %)) pos))))
    (finally (rcon/command rc (str "forceload remove " x " " z)))))

(defn answers!
  "The landing checks run at a surface position: [[reason reply] ...]."
  [rc [x y z]]
  (vec (for [[reason test] harness/checks]
         [reason (rcon/command rc (str "execute positioned " x " " y " " z " " test))])))

(defn landing!
  "The first fit surface near grid cell, after snapping it to the set's biome: [x z y], or nil."
  [rc cell biome]
  (let [[cx cz] cell
        snapped (or (parse-located (rcon/command rc (str "execute positioned " cx " 64 " cz " run locate biome " biome)))
                    cell)]
    (some (fn [[ox oz]]
            (let [[x z] [(+ (first snapped) ox) (+ (second snapped) oz)]
                  pos (surface! rc [x z])]
              (when (and pos (fit? pos (answers! rc pos)))
                (let [[px py pz] pos] [px pz py]))))
          harness/landing-offsets)))

(defn pregenerate!
  "Generate every chunk within r blocks of a landing, then release them: forceload the square,
   wait until its corners report loaded, remove the forceload. Squares larger than one forceload
   allows are refused."
  [rc [x z _] r]
  (let [[x0 z0 x1 z1 :as sq] (square [x z] r)]
    (when (> (chunk-count sq) max-forceload-chunks)
      (throw (ex-info "pregen square too large for one forceload" {:square sq :chunks (chunk-count sq)})))
    (rcon/command rc (str "forceload add " x0 " " z0 " " x1 " " z1))
    (try
      (doseq [[cx cz] [[x0 z0] [x1 z0] [x0 z1] [x1 z1]]] (harness/wait-loaded! rc cx cz))
      (finally (rcon/command rc (str "forceload remove " x0 " " z0 " " x1 " " z1))))))

(defn landings!
  "Build a landing set against a running server: one landing per grid cell that has one, each
   pregenerated. Returns [[x z y] ...]."
  [rc {:keys [center n spacing biome pregen-r]}]
  (vec (keep (fn [cell]
               (when-let [l (landing! rc cell biome)]
                 (harness/log "landing" l "for cell" cell)
                 (pregenerate! rc l pregen-r)
                 l))
             (grid center n spacing))))
