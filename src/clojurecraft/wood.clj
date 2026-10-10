(ns clojurecraft.wood
  "Getting a log: walk to the nearest remembered trunk, dig the log nearest the bot's own feet
   height (so the drop lands where the bot can stand, not in a hole under the rest of the
   trunk), pick up the drop. The :gather act of the goal table (clojurecraft.make) runs this."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.terrain :as terrain]))

(def search-radius 48)                ; blocks from the eye searched for a remembered log
(def max-trunk 8)                     ; logs followed up one trunk column

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
  (let [feet (long (Math/floor (second (:player/pos world))))
        column (take-while #(memory/log-at? world %) (map (fn [dy] [x (+ y dy) z]) (range max-trunk)))]
    (apply min-key (fn [[_ ly _]] (abs (- ly feet))) (reverse column))))

(def max-trunk-failures 2)            ; a trunk that failed twice is given up; once is chance
(def max-clears 6)                    ; leaf blocks broken to reach one drop

(defn continue-gather
  "The next intent of a log-gathering chain already in flight, or nil. Chains are marked
   :intent/for :log so a walk to a crafting table is never mistaken for one:
   walk → dig → collect; a collect blocked by leaves → dig the leaf → the same collect again."
  [world]
  (let [{:intent/keys [kind target blocked-by resume clears trunk] :as last} (:plan/last world)
        of-trunk (fn [i] (cond-> i trunk (assoc :intent/trunk trunk)))]
    (when (= :log (:intent/for last))
      (case kind
        :walk (of-trunk {:intent/kind :dig :intent/target target :intent/for :log})
        :dig (or resume (of-trunk {:intent/kind :collect :intent/target target :intent/for :log}))
        :collect (when (and blocked-by (< (or clears 0) max-clears))
                   (of-trunk {:intent/kind :dig :intent/target blocked-by :intent/for :log
                              :intent/resume (of-trunk {:intent/kind :collect :intent/target target :intent/for :log
                                                        :intent/clears (inc (or clears 0))})}))
        nil))))

(defn skipped
  "The logs not to choose: blacklisted positions, and every log in a trunk column that has failed
   max-trunk-failures times."
  [world]
  (let [blacklist (:plan/blacklist world #{})
        failures (:plan/trunk-failures world {})]
    (fn [[x _ z :as pos]] (or (contains? blacklist pos) (>= (get failures [x z] 0) max-trunk-failures)))))

(defn gather-next
  "The next step of getting a log: continue the chain, or start toward the nearest remembered
   trunk. Any goal that needs a log uses this."
  [world]
  (or (continue-gather world)
      (if-let [bottom (memory/nearest-log world (game/eye world) search-radius (skipped world) standing-trunk?)]
        {:intent/kind :walk :intent/target (trunk-target world bottom) :intent/for :log :intent/trunk bottom}
        {:plan/wait :no-log})))


