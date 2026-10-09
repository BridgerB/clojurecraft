(ns clojurecraft.wood
  "The first goal: hold one log. Walk to the nearest remembered trunk bottom, dig it, pick up
   the drop. Requiring this namespace registers the goal's methods."
  (:require [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]))

(def search-radius 48)

(defmethod plan/goal-done? :wood [world _] (pos? (game/logs-held world)))

(defmethod plan/next-intent :wood [world _]
  (let [last (:plan/last world)]
    (case (:intent/kind last)
      :walk {:intent/kind :dig :intent/target (:intent/target last)}
      :dig {:intent/kind :collect :intent/target (:intent/target last)}
      (if-let [target (memory/nearest-log world (game/eye world) search-radius (:plan/blacklist world #{}))]
        {:intent/kind :walk :intent/target target}
        {:plan/wait :no-log}))))
