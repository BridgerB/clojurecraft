(ns clojurecraft.failure
  "What a failed run was doing and why, as a value both races share: a run against the model
   (simrace) and a run on a real server (race) that stop the same way carry the same signature,
   and one signature is one issue however many runs hit it (bb failures)."
  (:require [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(defn stage
  "The target a run was on when it stopped: the first of until's chain it had not reached."
  [until reached]
  (first (remove (set reached) (plan/through until))))

(defn signature
  "What groups failures, from a RESULT: [the target the run was on, why it stopped, the last
   intent's kind]."
  [{:keys [until reason plan]}]
  [(stage until (:plan/reached plan)) reason (get-in plan [:plan/last :intent/kind])])
