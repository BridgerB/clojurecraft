(ns clojurecraft.pearls.params
  "The pearl hunt's constants: the thresholds table (pearls.edn) and the values derived from it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.combat :as combat]
            [clojurecraft.path :as path]
            [clojurecraft.stairs :as stairs]))

(set! *warn-on-reflection* true)

(def pearl :ender_pearl)

; the item a kill may drop
(def thresholds
  "Tuning parameters and thresholds for the hunt, read from resources/clojurecraft/pearls.edn."
  (edn/read-string (slurp (io/resource "clojurecraft/pearls.edn"))))

(def hunt-radius (:hunt-radius thresholds))

(def sighting-radius (:sighting-radius thresholds))

(def chase-max-fails (:chase-max-fails thresholds))

(def chase-giveup-ms (:chase-giveup-ms thresholds))

(def refuge-leave-ms (:refuge-leave-ms thresholds))

(def seek-radius (:seek-radius thresholds))

; blocks from the feet an enderman is walked toward, none nearer
(def lookout-ms (:lookout-ms thresholds))

(def lookout-turn (:lookout-turn thresholds))

(def roam-reach (:roam-reach thresholds))

(def toward-gain (:toward-gain thresholds))

(def roam-turn-ms (:roam-turn-ms thresholds))

(def headings ; the eight ways a roam may go, a quarter turn of each other in pairs
  [[1 0] [1 1] [0 1] [-1 1] [-1 0] [-1 -1] [0 -1] [1 -1]])

(def strike-range (:strike-range thresholds))

(def stare-range (:stare-range thresholds))

; blocks to the head within which a provoke stares, with a clear line: the enderman's follow range
(def stare-from (:stare-from thresholds))

; never stare nearer: an angry enderman stared at within 4 teleports away
(def stare-ms (:stare-ms thresholds))

(def burrow-depth (:burrow-depth thresholds))

(def burrow-length (:burrow-length thresholds))

(def burrow-span (+ burrow-depth burrow-length))

; blocks from the burrow's end every cell of it lies within: a walk toward one never ends inside
(def shelter-reach (:shelter-reach thresholds))

(def descend-drop (:descend-drop thresholds))

(def descend-step (:descend-step thresholds))

(def descend-radius (:descend-radius thresholds))

(def bore-length (:bore-length thresholds))

(def site-reach (:site-reach thresholds))

(def candidates (:candidates thresholds))

(def spare-digs (:spare-digs thresholds))

(def danger-radius (:danger-radius thresholds))

(def danger-rise (:danger-rise thresholds))

(def lure-tries (:lure-tries thresholds))

(def fight-tries (:fight-tries thresholds))

(def fight-rest-ms combat/give-up-ms)

(def fight-giveups (:fight-giveups thresholds))

(def fight-giveup-ms (:fight-giveup-ms thresholds))

(def fight-retry-ms (:fight-retry-ms thresholds))

(def park-ms (:park-ms thresholds))

(def park-near (:park-near thresholds))

(def close-gain (:close-gain thresholds))

(def stale-lookouts (:stale-lookouts thresholds))

(def lure-lookouts (:lure-lookouts thresholds))

(def shunned #{:enderman :creeper})

(def roam-reaches [roam-reach (* 2 roam-reach) (* 3 roam-reach) (* 4 roam-reach)])

;; ---------------------------------------------------------------- the kill and its drop
(def fires #{:fire :soul_fire})

(def hold-radius
  "Blocks from the feet within which an angry enderman keeps the bot where it is: the burrow is not
   given up (stale?) and no relocation begins while one stands this near."
  (:hold-radius thresholds))

(def cut-reach (:cut-reach thresholds))

(def lava-sea-top 32)

; the Nether's lava fills every cell up to this height
(def lava-margin (:lava-margin thresholds))

(def vantage-reach (:vantage-reach thresholds))

(def vantage-rise (:vantage-rise thresholds))

(def vantage-tries (:vantage-tries thresholds))

(def vantage-nodes (path/budget :vantage))

(def provoke-home-reach (:provoke-home-reach thresholds))

(def crowd-reach (:crowd-reach thresholds))

(def crowd-margin (:crowd-margin thresholds))

(def first-cut
  ;; the cells the first cut of each kind opens from the feet along a heading (stairs.clj)
  {:stairs-up stairs/up-cells :stairs-down stairs/stair-cells :tunnel stairs/level-cells})

(def brink-drop (:brink-drop thresholds))

(def nether-climb-ceiling (:nether-climb-ceiling thresholds))

(def provoke-margin (:provoke-margin thresholds))

; health a provoke keeps over the two hits it risks before the bot is back in its burrow
(def provoke-health (:provoke-health thresholds))

; health under which no enderman is provoked, from the mouth or anywhere: the bot rests in its burrow first; away from the mouth (a vantage) only at full health
(def danger-health (or (:danger-health thresholds) combat/retreat-health))

; health under which the hunt never fights, provokes or goes toward an enderman: one enderman hit (7 on Normal, ns doc) plus a margin, the same line combat breaks a fight off at. At or under it the bot backs off, walks home, eats or rests where it stands until its health is back over it
(def break-hp (:break-hp thresholds))

(def resume-hp (:resume-hp thresholds))

(def wander-lookouts (:wander-lookouts thresholds))

(def loiter-walks (:loiter-walks thresholds))

(def loiter-radius (:loiter-radius thresholds))

(def wander-reach (:wander-reach thresholds))

(def hold-patience-ms (:hold-patience-ms thresholds))

(def struck-health (:struck-health thresholds))

(def swarm-radius (:swarm-radius thresholds))

; blocks from the feet within which a monster counts toward a swarm
(def ranged-kinds
  "Kinds that hurt from where they hover, not from the sword's reach: they count toward a swarm
   only with a clear line to the eye (combat/seen?), and the bot gets out of their sight."
  #{:blaze :ghast})

(def eye-height 1.62)

(def room-margin
  "Blocks round a known spawner, on the plane and up and down, that count as its blaze room: a walk
   or a dig-out that goes through it goes into the fireballs."
  12)

(def cover-reach (:cover-reach thresholds))

(def shot-hold-ms (:shot-hold-ms thresholds))

(def weak-melee (:weak-melee thresholds))

(def heavy-kinds
  "Hostile kinds that hit too hard to fight at hand with no burrow, whether mob/kinds holds them or
   not: a piglin's blade, a hoglin's tusks, a blaze and a ghast's fire."
  #{:piglin :piglin_brute :hoglin :zoglin :wither_skeleton :blaze :ghast :vindicator :evoker})

(def neighbours [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1] [0 1 0] [0 -1 0]])

(def explore-memory (:explore-memory thresholds))

(def forest-reach (:forest-reach thresholds))

(def fortress-escape-reach
  "Blocks from every known spawner and fortress brick beyond which the bot is clear
   of the fortress structure and safe to surface."
  48.0)

(def bore-reach (:bore-reach thresholds))

(def exit-reach (:exit-reach thresholds))

(def relocate-ms (:relocate-ms thresholds))

; ms without a pearl gained before the hunt leaves the place for another (8 minutes)
(def relocate-dist (:relocate-dist thresholds))

; blocks from where it began that a relocation walks before it is over
(def relocate-timeout-ms (:relocate-timeout-ms thresholds))

; ms a relocation goes on at most (seven minutes) before the clock starts again
(def relocate-patience-ms (:relocate-patience-ms thresholds))

; ms a relocation waits in place for an angry enderman near to come or go (menaced?) before it walks past one that is parked (live-menaced?)
(def cluster-radius (:cluster-radius thresholds))

(def answers-to-hand #{:fight :back-off :eat :dig})

(def sword-acts
  "Intents that make a sword from what is already held: placing or opening a table, the craft,
   taking the table along (a dig and a collect), a toss that makes room for it."
  #{:place :craft :open-container :dig :collect :toss})

(def pick-acts
  "Intents that make a pickaxe from what is already held: placing or opening a table, the craft,
   taking the table along (a dig and a collect), a toss that makes room for it."
  #{:place :craft :open-container :dig :collect :toss})

(def reserve-digs (:reserve-digs thresholds))

(def reserve-stems (:reserve-stems thresholds))

(def heading-leg (:heading-leg thresholds))

(def heading-span (:heading-span thresholds))

(def heading-min-y (:heading-min-y thresholds))

(def heading-max-y (:heading-max-y thresholds))

(def heading-arrived (:heading-arrived thresholds))

(def seen-corridor (:seen-corridor thresholds))

(def progress (:progress thresholds))

(def watch-same (:watch-same thresholds))
