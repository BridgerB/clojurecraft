(ns clojurecraft.failure
  "What a failed run was doing and why, as a value both races share: a run against the model
   (simrace) and a run on a real server (race) that stop the same way carry the same signature,
   and one signature is one issue however many runs hit it (bb failures)."
  (:require [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(def infra-reasons
  "Reasons a run stops that say nothing about the bot's play: the server closed the socket or
   disconnected the player. They are counted, never filed as an issue."
  #{:disconnected :closed})

(defn stage
  "The target a run was on when it stopped: the first of until's chain it had not reached."
  [until reached]
  (first (remove (set reached) (plan/through until))))

(defn signature
  "What groups failures, from a RESULT: [the target the run was on, why it stopped]. One bug
   met in several intents (a pickaxe gone while mining or tunnelling) is one signature; the
   intents are listed inside its issue."
  [{:keys [until reason plan]}]
  [(stage until (:plan/reached plan)) reason])

(defn infra?
  "Did the run stop for a reason that is not the bot's play (infra-reasons)?"
  [{:keys [reason]}]
  (contains? infra-reasons reason))

(defn mark
  "A RESULT with what bb failures reads, when it failed: :run/signature, and :run/infra? when
   the reason is not the bot's play. A passing RESULT is unchanged."
  [r]
  (cond-> r (not (:ok r)) (assoc :run/signature (signature r) :run/infra? (infra? r))))
