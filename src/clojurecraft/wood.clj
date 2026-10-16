(ns clojurecraft.wood
  "Getting a log: walk to the nearest remembered trunk, dig the log nearest the bot's own feet
   height (so the drop lands where the bot can stand, not in a hole under the rest of the
   trunk), pick up the drop. The :gather act of the goal table (clojurecraft.make) runs this."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def search-radius 48)                ; blocks from the eye searched for a remembered log
(def far-radius 256)                  ; a trunk remembered on the way in, past the near band
(def far-candidates 3)                ; routes planned on the way back to the surface
(def max-trunk 8)                     ; logs followed up one trunk column
(def bed-reach 4)                     ; cells over the head a dig up into water overhead looks through

(defn ground?
  "Can a drop come to rest on this block where a player stands beside it: solid, and neither a log
   (that is more trunk) nor leaves (that is canopy)?"
  [id]
  (boolean (and id (blocks/solid? id) (not (blocks/log? id)) (not (blocks/leaves? id)))))

(defn standing-trunk?
  "A trunk bottom whose drops fall where a player can stand: on ground, over the place its own
   dug base stood (a log was seen there), or over a block not loaded now. A branch, a log with
   air or leaves under it, is not one: its drop comes to rest on the trunk or in the canopy."
  [world [x y z :as pos]]
  (and (memory/trunk-bottom? world pos)
       (let [below [x (dec y) z]
             id (terrain/block-at world below)]
         (or (nil? id)
             (ground? id)
             (boolean (some (comp blocks/log? :block/state) (memory/history world below)))))))

(defn trunk-target
  "Of the remembered logs stacked on bottom, the one whose height is closest to the player's
   feet (lower on ties)."
  [world [x y z]]
  (let [feet (long (math/floor (physics/y-of (:player/pos world))))
        column (take-while #(memory/log-at? world %)
                           (map (fn [dy] [x (+ y dy) z]) (range max-trunk)))]
    (apply min-key (fn [[_ ly _]] (abs (- ly feet))) (reverse column))))

(def max-trunk-failures 2)            ; a trunk that failed twice is given up; once is chance
(def max-clears 6)                    ; leaf blocks broken to reach one drop

(defn continue-gather
  "The next intent of a log-gathering chain already in flight, or nil. Chains are marked
   :intent/for :log so a walk to a crafting table is never mistaken for one:
   walk → dig → collect; a collect blocked by leaves → dig the leaf → the same collect again."
  [world]
  (let [{:intent/keys [kind target blocked-by resume clears trunk] :as last} (:plan/last world)
        extra (if trunk {:intent/trunk trunk} {})]
    (cond (not= :log (:intent/for last)) nil
          (and (= :dig kind) resume) resume ; a leaf dug: back to the collect it cleared for
          (= :collect kind)
          (when (and blocked-by (< (or clears 0) max-clears))
            (merge {:intent/kind :dig
                    :intent/target blocked-by
                    :intent/for :log
                    :intent/resume (merge {:intent/kind :collect
                                           :intent/target target
                                           :intent/for :log
                                           :intent/drops blocks/log-items
                                           :intent/clears (inc (or clears 0))}
                                          extra)}
                   extra))
          :else (intent/chain-next last :log blocks/log-items extra))))

(defn skipped
  "The logs not to choose: blacklisted positions, and every log in a trunk column that has failed
   max-trunk-failures times."
  [world]
  (let [blacklist (:plan/blacklist world #{})
        failures (:plan/trunk-failures world {})]
    (fn [[x _ z :as pos]]
      (or (contains? blacklist pos) (>= (get failures [x z] 0) max-trunk-failures)))))

(defn log-walk
  "A walk to the log of bottom nearest the feet, tagged as the gather chain."
  [world bottom]
  {:intent/kind :walk
   :intent/target (trunk-target world bottom)
   :intent/for :log
   :intent/trunk bottom})

(defn climb-out
  "No trunk in the near band, and a standing trunk remembered further off: walk to the nearest one
   a route reaches (intent/routable?), under the open sky or not. Not out of the ground
   (stairs/out?: the open sky over the feet and a level step onto open ground beside them), with
   none of those a route reaches: walk up the air already dug (intent/opening) and cut stairs only
   when that mouth cannot be walked to (stairs/out? ends them; terrain/top-y is the top of the
   world; stairs/climb-to cuts the climb in lengths the planner looks again between). A pit under
   the open sky is not out: race 38477665442's stone mines dug the bot two deep beside a cliff
   (Clj_r20), into a notch under an overhang by a pond (Clj_r09), onto a ledge of its own quarry
   (Clj_r01) and onto a lakeshore under a cliff (Clj_r12); every trunk was up the wall no jump
   climbs, twelve walks failed :no-path, and the sky over the feet said there was nothing to cut.
   Nor is the floor of a quarry with a level step across it (stairs/pit-rim): race 38515209959
   (issues #102, #103) and the sim race in worlds R3/36 and R3/32 stood at the bottom of the
   quarry the stone mine had dug round their feet, its stairs eaten by the digs, with the trees
   seven blocks off and no route to any; the sky over the feet and a step across the floor said
   out, the hunt had no route either, and each waited :no-log for the table's log.
   nil when no trunk is remembered yet. Full run 29ef069 cut a stair at spawn before any tree was
   remembered, failed :stair-not-taken, and the plan then waited :no-log. Full run 4528031
   remembered the trees, found no route through the mine, and cut a new stair; the only pickaxe
   broke."
  [world]
  (let [feet (intent/feet world)
        trunks (->> (memory/positions-now world blocks/log-states)
                    (remove (skipped world))
                    (filter #(standing-trunk? world %))
                    (memory/nearest-first (game/eye world) far-radius)
                    (take far-candidates))]
    (when (seq trunks)
      (or (some (fn [bottom] (let [i (log-walk world bottom)] (when (intent/routable? world i) i)))
                trunks)
          (when-not (or (stairs/out? world feet) (contains? (:plan/boxed-at world #{}) feet))
            (or (intent/opening world)
                {:intent/kind :stairs-up
                 :intent/to-y (stairs/climb-to world terrain/top-y)
                 :intent/for :surface}))))))

(defn near-walk
  "A walk to the nearest standing trunk in the near band (search-radius, no more than 12 above or
   below the eye: memory/nearest-log) that a route reaches, of the far-candidates nearest, or nil.
   Asked before it is chosen, as the mine asks of its ore (intent/routable?): a trunk no route
   reaches is passed over, not walked at. It was walked at: in sim race 38395033917 (world R3/24,
   108 runs) the bot stood at the bottom of two staircases that met, the second having dug out the
   riser of the first, with no way up at all; every walk to a trunk in the band failed :no-path
   the tick it started, blacklisted that trunk, and the next was tried, twelve times, until the
   plan failed (issue #75). With none, climb-out has the far trunks and the way up."
  [world]
  (let [eye (game/eye world)
        skip (skipped world)]
    (first (reduce
            (fn [[_ tried] _]
              (if-let [bottom (memory/nearest-log world
                                                  eye
                                                  search-radius
                                                  (fn [p] (or (skip p) (contains? tried p)))
                                                  standing-trunk?)]
                (let [i (log-walk world bottom)]
                  (if (intent/routable? world i) (reduced [i tried]) [nil (conj tried bottom)]))
                (reduced [nil tried])))
            [nil #{}]
            (range far-candidates)))))

(defn hunt
  "Under the open sky with trunks remembered and no route to any: a walk toward the trees, the
   way a water hunt goes out (scoop/hunt-targets: the ground hunt-distance out along eight
   bearings, never nearer the landing than the feet are now), to the ground nearest the nearest
   remembered log, the first of the three such a route reaches (scoop/first-walk), tagged
   :intent/for :log-hunt. The chunks it loads are remembered as they arrive, so the next pass
   finds the way round. With no log remembered within far-radius the hunt sets out all the same,
   the targets in the water hunt's order: race 38665691517 runner 1 (issue #57) landed slots
   Clj_r08, Clj_r14 and Clj_r15 where no standing trunk was in reach, routes led out on three
   bearings, and each waited :no-log at its landing round after round. With logs remembered the
   hunt may also walk a hop back toward the landing, ranked only by how near the trees it lands:
   race 38680745815 runner 5 (issue #57) bot Clj_r10 hunted a hop away from its landing before
   any trunk was remembered, and with the valley's trunks then known below it, every bearing
   back toward them was refused as nearer the landing and the one bearing left had no route.
   nil underground, or with no ground a route reaches. Race 38477665442 bots Clj_r14 and Clj_r20 landed on a lake's shore with the forest
   across the water: every trunk was in the near band and no route reached one, and each waited
   :no-log until the plan failed (issue #57). Bot Clj_r15 came up out of its mine for the wood
   of a spare pickaxe with the trees a hundred blocks off, past the pathfinder's budget, and
   waited the same way (issue #87)."
  [world]
  (let [logs (memory/nearest-first (game/eye world)
                                   far-radius
                                   (memory/positions-now world blocks/log-states))]
    (when (terrain/under-sky? world (intent/feet world))
      (if (seq logs)
        (scoop/first-walk world
                          :log-hunt
                          (sort-by #(physics/horizontal-distance % (first logs))
                                   (scoop/hunt-targets world true)))
        (scoop/first-walk world :log-hunt (scoop/hunt-targets world))))))

(defn into-water
  "Boxed under water with no other way to the trees (the feet in :plan/boxed-at, where a climb
   out was boxed): dig up into it, the lowest solid cell over the head
   first (:intent/exposes-ok?: the dig lets the water in), when water lies within bed-reach over
   the head and nothing between would fall on the bot (blocks/falls?), is lava, is not loaded or
   is never dug; once nothing but air and water is over the head, a :swim-out for :rise up to the
   water's top (:intent/to-y), then to the shore (swim/far-shore), ending on dry land in the open;
   the water poured in is the trap too, and the escape it starts swims the same way. Sim race 38806318231 runner 1 and 38811426215 runner 8 (issue #89, worlds
   R4/30, R4/21 and R1/32 at 76010d2, 146 runs) had bots mine under the sea floor until the last
   pickaxe broke: for a new one they needed a log, every climb out was :boxed under the water,
   no route led home, and each waited :no-log. nil otherwise."
  [world]
  (let [[x y z :as feet] (intent/feet world)
        cells (map (fn [dy] [x (+ y dy) z]) (range 2 (+ 2 bed-reach)))
        [dry more] (split-with #(not= :water (terrain/fluid-kind world %)) cells)
        ids (map #(terrain/block-at world %) dry)
        rock (first (filter #(some-> (terrain/block-at world %)
                                     blocks/solid?)
                            dry))
        water? #(= :water (terrain/fluid-kind world [x % z]))
        top (->> (range (+ y 2) (+ y 34))
                 (drop-while (complement water?))
                 (drop-while water?)
                 first)]
    (cond (not (and (contains? (:plan/boxed-at world #{}) feet)
                    (seq more)
                    (every? some? ids)
                    (not-any? #(or (blocks/falls? %)
                                   (= :lava (:fluid/kind (blocks/fluid %)))
                                   (contains? intent/never-dug (blocks/name-of %)))
                              ids)))
          nil
          rock (assoc (intent/child-dig rock) :intent/exposes-ok? true :intent/for :into-water)
          :else {:intent/kind :swim-out :intent/for :rise :intent/to-y (or top (+ y 34))})))

(defn gather-next
  "The next step of getting a log: continue the chain, or start toward the nearest remembered
   trunk a route reaches (near-walk). Any goal that needs a log uses this. With none in the near
   band a route reaches, climb out to the trees, or walk toward them (hunt), or boxed under water
   dig up into it (into-water), instead of waiting :no-log."
  [world]
  (or (continue-gather world)
      (near-walk world)
      (climb-out world)
      (hunt world)
      (into-water world)
      {:plan/wait :no-log}))

(defn way-out
  "With no log to set out for (gather-next waits :no-log), the way out of where the feet are that
   the water hunt takes: out of a pit under the open sky deep under the landing (scoop/pit-climb),
   else a tunnel out along a heading that can be cut (scoop/tunnel-out, tagged :log-hunt), else,
   under rock, a retreat to new rock (stone/retreat) from a cell a climb died :boxed in; nil when
   none. Sim race 38843552659 (issue #186): world R1/18 stood at y 6 on a ravine's floor sixty
   under its landing, the sky open, no hunt target in its band, and world R4/39 under rock at
   y 47 where its climb out had died :boxed, and both waited :no-log with trees remembered sixty
   blocks off."
  [world]
  (or (scoop/pit-climb world)
      (some-> (scoop/tunnel-out world)
              (assoc :intent/for :log-hunt))
      (when-not (terrain/under-sky? world (intent/feet world)) (stone/retreat world))))

(defn gather-or-out
  "The :gather act: gather-next, or when it waits :no-log the way out (way-out) before the wait.
   Kept apart from gather-next, which make/spare-worth? asks whether a log can be started now: a
   spare never sends the bot out of a pit for wood."
  [world]
  (let [n (gather-next world)] (or (when (= {:plan/wait :no-log} n) (way-out world)) n)))
