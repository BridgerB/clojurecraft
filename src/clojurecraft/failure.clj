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
  "The target a run was on when it stopped: the first of until's chain it had not reached; of the
   targets in play (goals) when the run named them: a race that starts at the fortress plays only
   the stages from the blaze rods, and a failure of one is never filed at a stage before them."
  ([until reached] (stage until reached nil))
  ([until reached goals]
   (cond->> (plan/through until)
     (seq goals) (filter (set goals))
     true (remove (set reached))
     true first)))

(defn signature
  "What groups failures, from a RESULT: [the target the run was on, why it stopped]. One bug
   met in several intents (a pickaxe gone while mining or tunnelling) is one signature; the
   intents are listed inside its issue."
  [{:keys [until reason plan]}]
  [(stage until (:plan/reached plan) (:plan/goals plan)) reason])

(defn infra?
  "Did the run stop for a reason that is not the bot's play (infra-reasons)?"
  [{:keys [reason]}]
  (contains? infra-reasons reason))

(defn unplayed?
  "Did the run fail before its plan began (no :plan/status in its RESULT's :plan, which main
   leaves out until the bot's :go): its connection refused, or its server gone before it landed.
   Such a run says nothing about the bot's play (race 39817745908: a crashed server's slots
   recorded 2,864 [:wood :crashed] runs that never connected)."
  [r]
  (and (not (:ok r)) (nil? (get-in r [:plan :plan/status]))))

(defn mark
  "A RESULT with what bb failures reads, when it failed: :run/signature, and :run/infra? when
   the reason is not the bot's play. A passing RESULT is unchanged."
  [r]
  (cond-> r (not (:ok r)) (assoc :run/signature (signature r) :run/infra? (infra? r))))

(defn cut
  "A marked RESULT of a run the churn's job ended while it still played (issue #127): it says
   nothing about the bot's play, so it is :run/cut? and, like a server's disconnect, never filed."
  [r]
  (assoc r :run/cut? true :run/infra? true))
