(ns clojurecraft.wood
  "Getting a log: walk to the nearest remembered trunk, dig the log nearest the bot's own feet
   height (so the drop lands where the bot can stand, not in a hole under the rest of the
   trunk), pick up the drop. The :gather act of the goal table (clojurecraft.make) runs this."
  (:require [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]))

(def search-radius 48)                ; blocks from the eye searched for a remembered log
(def max-trunk 8)                     ; logs followed up one trunk column

(defn trunk-target
  "Of the remembered logs stacked on bottom, the one whose height is closest to the player's
   feet (lower on ties)."
  [world [x y z]]
  (let [feet (long (Math/floor (second (:player/pos world))))
        column (take-while #(memory/log-at? world %) (map (fn [dy] [x (+ y dy) z]) (range max-trunk)))]
    (apply min-key (fn [[_ ly _]] (abs (- ly feet))) (reverse column))))

(def max-clears 6)                    ; leaf blocks broken to reach one drop

(defn continue-gather
  "The next intent of a log-gathering chain already in flight, or nil. Chains are marked
   :intent/for :log so a walk to a crafting table is never mistaken for one:
   walk → dig → collect; a collect blocked by leaves → dig the leaf → the same collect again."
  [world]
  (let [{:intent/keys [kind target blocked-by resume clears] :as last} (:plan/last world)]
    (when (= :log (:intent/for last))
      (case kind
        :walk {:intent/kind :dig :intent/target target :intent/for :log}
        :dig (or resume {:intent/kind :collect :intent/target target :intent/for :log})
        :collect (when (and blocked-by (< (or clears 0) max-clears))
                   {:intent/kind :dig :intent/target blocked-by :intent/for :log
                    :intent/resume {:intent/kind :collect :intent/target target :intent/for :log
                                    :intent/clears (inc (or clears 0))}})
        nil))))

(defn gather-next
  "The next step of getting a log: continue the chain, or start toward the nearest remembered
   trunk. Any goal that needs a log uses this."
  [world]
  (or (continue-gather world)
      (if-let [bottom (memory/nearest-log world (game/eye world) search-radius (:plan/blacklist world #{}))]
        {:intent/kind :walk :intent/target (trunk-target world bottom) :intent/for :log}
        {:plan/wait :no-log})))


