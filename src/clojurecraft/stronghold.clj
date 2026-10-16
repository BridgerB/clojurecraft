(ns clojurecraft.stronghold
  "Finding the stronghold (issue #265), the one stage of the run no sibling has proven: steve's
   finder is a stub (\"TODO: Track the eye entity, calculate direction, triangulate\") and
   ruststeve never threw an eye.

   An eye of ender used in the overworld finds the nearest stronghold (EnderEyeItem.use:
   findNearestMapStructure over the eye_of_ender_located structures) and flies from the
   thrower's middle toward it, twelve blocks along the line on the plane and eight up when the
   stronghold is farther (EyeOfEnder.signalTo), straight on the plane all the way. So two
   positions of the eye a moment apart give the line to the stronghold exactly, up to how the
   server rounds a move (1/4096 block); two such lines from two places cross at it. A throw is
   kept as a fact on the world, {:throw/from :throw/dir :throw/at} in :stronghold/throws, and
   the crossing is a pure function of two of them (triangulate)."
  (:require [clojure.math :as math]))

(set! *warn-on-reflection* true)

(def min-crossing 0.035)              ; the sine of the least angle two throws' lines may cross at (2 degrees): nearer parallel, a sliver of error moves the crossing hundreds of blocks

;; ---------------------------------------------------------------- the plane

(defn plane
  "[x z] of a point given as [x y z] or already as [x z]."
  [p]
  (if (= 3 (count p)) [(nth p 0) (nth p 2)] (vec p)))

(defn cross
  "The 2-D cross product of [ax az] and [bx bz]: positive when b turns counter-clockwise from a
   (x east, z south), zero when they are parallel."
  [[ax az] [bx bz]]
  (- (* ax bz) (* az bx)))

(defn norm "The length of a 2-D vector [x z]." [[x z]] (math/hypot x z))

;; ---------------------------------------------------------------- the crossing

(defn triangulate
  "[x z] where the lines of throws a and b cross, each a ray on the plane from :throw/from (where
   the eye left the bot) along :throw/dir (the way it flew), or nil when they do not cross ahead
   of both: nearly parallel (the sine of their angle under min-crossing, so two eyes thrown along
   one line, which say nothing about how far, are no answer), or the crossing behind either throw
   (an eye never flies away from its stronghold). A pure function of the two facts."
  [a b]
  (let [[ax az] (plane (:throw/from a))
        [bx bz] (plane (:throw/from b))
        da (:throw/dir a)
        db (:throw/dir b)
        c (cross da db)
        w [(- bx ax) (- bz az)]]
    (when (> (abs c) (* min-crossing (norm da) (norm db)))
      (let [ta (/ (cross w db) c)
            tb (/ (cross w da) c)]
        (when (and (>= ta 0.0) (>= tb 0.0))
          [(+ ax (* ta (first da))) (+ az (* ta (second da)))])))))
