(ns clojurecraft.unstick
  "The rungs of the planner's escape ladder (plan/escape): ways to change place for a bot that
   retried the same intent or sat in one cell. A walk away over ground a route reaches comes first
   (stone/retreat, never onto cells a failed descent visited), then stairs up and a tunnel cut
   with the pickaxe held, each along the heading the count of escapes points at, so that one
   refused heading is followed by another. Issue #263: runs 1 and 2 of gym plan run 40583923956
   stood in :lookout for two hours and forty-four minutes after a descent died :boxed."
  (:require [clojure.string :as str]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]))

(set! *warn-on-reflection* true)

(def climb-height 4)                  ; blocks a climb-away goes up
(def cut-length 6)                    ; level steps a tunnel-away cuts

(defn pickaxe?
  "Does the bot hold any pickaxe?"
  [world]
  (boolean (some (fn [[n c]] (and (pos? c) (str/ends-with? (name n) "_pickaxe")))
                 (recipe/counts (:player/inventory world)))))

(defn heading
  "The heading of stairs/directions the count of escapes picks: a quarter turn a time."
  [world]
  (nth stairs/directions (mod (:plan/escapes world 0) (count stairs/directions))))

(defmethod plan/escape :retreat [world _] (stone/retreat world))

(defmethod plan/escape :climb
  [world _]
  (when (pickaxe? world)
    (let [[_ y _] (intent/feet world)]
      {:intent/kind :stairs-up
       :intent/to-y (+ y climb-height)
       :intent/dir (heading world)
       :intent/to-height? true})))

(defmethod plan/escape :cut
  [world _]
  (when (pickaxe? world)
    {:intent/kind :tunnel :intent/dir (heading world) :intent/length cut-length}))
