(ns clojurecraft.claims
  "The one thing racing bots share (issue #17, docs/RICH-HICKEY.md \"Conveyance, bounded\"): which
   landing cells are taken. Claims are values and the claims are a value; `claim` is a pure
   reducer, first come first served, so no cell is ever granted twice. A cell is a square of the
   world cell-size blocks wide, [cx cz], counted from the race's centre; the spiral orders them
   outward so the first bots land nearest the world spawn."
  (:require [clojure.math :as math]))

(set! *warn-on-reflection* true)

(def cell-size 96)                    ; blocks a landing cell is wide: six chunks, so bots do not share a forest's trees

(defn claim
  "[claims' answer] for request {:claim/by name :claim/cell [cx cz]} against claims ({cell name}):
   the cell is granted when no one holds it, or the claimant already does; the answer is the
   request with :claim/granted?."
  [claims {:claim/keys [by cell] :as request}]
  (let [holder (get claims cell)
        granted? (or (nil? holder) (= holder by))]
    [(cond-> claims granted? (assoc cell by)) (assoc request :claim/granted? granted?)]))

(defn spiral
  "The cells [cx cz] of a square spiral out from [0 0], ring by ring: [0 0], then the eight
   round it, then the sixteen round those."
  []
  (cons [0 0]
        (for [r (iterate inc 1)
              cx (range (- r) (inc r))
              cz (range (- r) (inc r))
              :when (= r (max (abs cx) (abs cz)))]
          [cx cz])))

(defn cell-of
  "The cell [cx cz] holding block column [x z], cells counted from centre [ox oz]."
  [[ox oz] [x z]]
  [(math/floor-div (long (- x ox)) (long cell-size))
   (math/floor-div (long (- z oz)) (long cell-size))])

(defn middle
  "The block column [x z] in the middle of cell [cx cz] counted from centre [ox oz]."
  [[ox oz] [cx cz]]
  [(+ ox (* cx cell-size) (quot cell-size 2)) (+ oz (* cz cell-size) (quot cell-size 2))])
