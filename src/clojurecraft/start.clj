(ns clojurecraft.start
  "Where and with what a bot begins, as data: resources/clojurecraft/starts.edn, read by the real
   race (race.clj) and the sim race (simrace.clj) through the functions here, so the two cannot
   disagree about a start. A start is a map of :start/id, :start/place, :start/goal, :start/kit and
   :start/cap-min (the file says what each means)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.plan :as plan]))

(set! *warn-on-reflection* true)

(def table
  "The starts file, as read: {:starts/default :starts/base-kit :starts [start ...]}."
  (edn/read-string (slurp (io/resource "clojurecraft/starts.edn"))))

(def by-id
  "{id start} for every start in the table, id a keyword."
  (into {} (map (juxt :start/id identity)) (:starts table)))

(defn start-of
  "The start named id, a keyword, a string or nil (the table's default), or nil when none is so
   named."
  [id]
  (by-id (if (nil? id) (:starts/default table) (keyword (name id)))))

(defn nether?
  "Does start begin in the Nether: at the fortress pad or in the warped forest?"
  [start]
  (contains? #{:fortress :warped-forest} (:start/place start)))

(defn forest?
  "Does start begin in the warped forest nearest the fortress?"
  [start]
  (= :warped-forest (:start/place start)))

(defn kit
  "What a bot at start carries, in the order it is given: [[item count] ...] with the start's own
   items first, then the base kit. Empty for a start in the overworld, which gives nothing."
  [start]
  (if (nether? start) (into (vec (:start/kit start)) (:starts/base-kit table)) []))

(defn kit-commands
  "The kit of start as give-command words, [\"minecraft:item count\" ...], in kit's order."
  [start]
  (mapv (fn [[item n]] (str "minecraft:" (name item) " " n)) (kit start)))

(defn kit-map
  "The kit of start as {item count}, a count added when an item is listed twice."
  [start]
  (reduce (fn [m [item n]] (update m item (fnil + 0) n)) {} (kit start)))

(defn goals-from
  "The goal ids from goal up in the chain to until (plan/through): the stages whose priority is at
   least goal's row's, so a failure before goal is not the bot's to file; the whole chain for a nil
   goal."
  [goal until]
  (let [chain (plan/through until)]
    (if goal
      (let [priority (fn [id] (:goal/priority (first (filter #(= id (:goal/id %)) plan/goals)) 0))
            from (priority goal)]
        (filterv #(>= (priority %) from) chain))
      chain)))

(defn goals
  "The goal ids a bot at start plays to until: goals-from the start's own goal."
  [start until]
  (goals-from (:start/goal start) until))

(defn cap-ms
  "The simulated ms a bot at start may play before it is cut as a :bot-timeout, or nil for no cap:
   the sim race's cap (its ticks are 50 ms by construction); the real race counts the server's ticks
   instead (cap-ticks)."
  [start]
  (some-> (:start/cap-min start)
          (* 60000)))

(defn cap-ticks
  "The server ticks a bot at start may play before it is cut as a :bot-timeout (game/server-ticks,
   20 a second of :start/cap-min at the game's own rate), or nil for no cap: game time, so a bot
   on a server ticking slower than 20 a second plays as much of the game as one in the sim."
  [start]
  (some-> (:start/cap-min start)
          (* 1200)))

