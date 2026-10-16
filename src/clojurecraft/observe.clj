(ns clojurecraft.observe
  "Trials of what the bot sees, not of what it does: goals done when the world value holds a fact
   the server sent, so the gym can hold it against the server's own answer. The entities trial
   (issue #258) is done once a living entity's health is known; it has nothing to do but wait for
   the metadata, which comes with the entity when its chunk is sent."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(defmethod plan/done-by :health-seen?
  [world _]
  (boolean (and (:player/pos world) (seq (entity/healths world (:player/pos world))))))

(defmethod plan/next-intent :watch [_ _ _] {:plan/wait :no-mob})
