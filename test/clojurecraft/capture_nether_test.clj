(ns clojurecraft.capture-nether-test
  "What the Nether capture takes for a set (issue #353): a region round every landing's Nether
   point, stops whose views cover all of it, the stops to tour again for what is missing, and the
   bricks a run there can find."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture-nether :as nether]
            [clojurecraft.capture-test :as capture-test]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.world :as world]))

(def landings
  "Three landings as a set's file lists them, [x z y]."
  [[2040 2040 70] [3000 3000 64] [3960 2360 81]])

(deftest a-landing-leads-to-its-coordinates-over-eight
  (is (= [255 255] (nether/nether-point [2040 2040 70])))
  (is (= [-2 -1] (nether/nether-point [-9 -8 70])) "floored, as the server scales a position"))

(deftest the-region-holds-every-nether-point-and-the-margin
  ;; Nether points [255 255] [375 375] [495 295]: chunks 15 to 30 across, 15 to 23 down
  (let [r (nether/region landings 4)]
    (is (= [11 11 34 27] r))
    (is (= (* 24 17) (count (nether/columns-of r))))
    (is (every? (capture-test/seen (nether/stops r)) (nether/columns-of r))
        "every column within view of a stop")))

(deftest a-column-still-missing-is-toured-again-from-its-nearest-stop
  (let [r (nether/region landings 4)
        ss (nether/stops r)
        got (into {} (for [k (nether/columns-of r)] [k (byte-array 1)]))
        gaps [[11 11] [34 27]]
        short (apply dissoc got gaps)]
    (is (empty? (nether/missing got r)))
    (is (= gaps (nether/missing short r)))
    (is (= [(first ss) (peek ss)] (nether/again ss gaps)) "the corners' stops, nothing else")
    (is (= (count got) (count (nether/within (assoc got [40 40] (byte-array 1)) r)))
        "a column outside the region is not kept")))

(deftest a-locate-reply-names-the-fortress
  (is (= [-123 456]
         (nether/located-at
          "The nearest minecraft:fortress is at [-123, ~, 456] (300 blocks away)")))
  (is (nil? (nether/located-at
             "Could not find a structure of type \"minecraft:fortress\" nearby"))))

(deftest the-bricks-are-measured-from-each-nether-point
  ;; a brick under the lava sea's top is never a target (fortress/brick-min-y), so it is not found
  (let [brick (blocks/default-state :nether_bricks)
        columns {[0 0] (world/column-bytes {[3 70 4] brick [5 20 5] brick} dimension/nether)}
        found (nether/bricks (update-vals columns chunk/decode))
        measured (nether/reach [[24 32 70] [800 32 70]] [[0 0] nil] found)]
    (is (= [[3 70 4]] found))
    (is (= [{:landing [24 32 70] :point [3 4] :brick 0 :located 5}
            {:landing [800 32 70] :point [100 4] :brick 97 :located nil}]
           measured))
    (is (= {:landings 2 :within {96 1 192 2 256 2 512 2} :none 0} (nether/summary measured)))))
