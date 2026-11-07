(ns clojurecraft.observe
  "Trials of what the bot sees, not of what it does: goals done when the world value holds a fact
   the server sent, so the gym can hold it against the server's own answer. The entities trial is
   done once a living entity beside the bot has a known health; it has nothing to do but wait for
   the metadata, which comes with the entity when its chunk is sent."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(def beside 4.0)                      ; blocks from the feet a living entity must be to end the trial: the gym's pig is 2.5 away

(defmethod plan/done-by :health-seen?
  ;; only a living entity beside the bot.
  [world _]
  (let [feet (:player/pos world)]
    (boolean (and feet
                  (entity/nearest (into {}
                                        (filter (fn [[_ e]] (contains? e :entity/health)))
                                        (:world/entities world))
                                  feet
                                  beside)))))

(defmethod plan/next-intent :watch [_ _ _] {:plan/wait :no-mob})
