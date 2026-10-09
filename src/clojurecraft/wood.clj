(ns clojurecraft.wood
  "The first goal: hold one log. Walk to the nearest remembered trunk, dig the log nearest the
   bot's own feet height (so the drop lands where the bot can stand, not in a hole under the
   rest of the trunk), pick up the drop. Requiring this namespace registers the goal."
  (:require [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]))

(def search-radius 48)
(def max-trunk 8)

(defn trunk-target
  "Of the remembered logs stacked on bottom, the one whose height is closest to the player's
   feet (lower on ties)."
  [world [x y z]]
  (let [feet (long (Math/floor (second (:player/pos world))))
        column (take-while #(memory/log-at? world %) (map (fn [dy] [x (+ y dy) z]) (range max-trunk)))]
    (apply min-key (fn [[_ ly _]] (abs (- ly feet))) (reverse column))))

(defmethod plan/goal-done? :wood [world _] (pos? (game/logs-held world)))

(defmethod plan/next-intent :wood [world _]
  (let [last (:plan/last world)]
    (case (:intent/kind last)
      :walk {:intent/kind :dig :intent/target (:intent/target last)}
      :dig {:intent/kind :collect :intent/target (:intent/target last)}
      (if-let [bottom (memory/nearest-log world (game/eye world) search-radius (:plan/blacklist world #{}))]
        {:intent/kind :walk :intent/target (trunk-target world bottom)}
        {:plan/wait :no-log}))))
