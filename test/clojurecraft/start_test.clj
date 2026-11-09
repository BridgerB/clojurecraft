(ns clojurecraft.start-test
  "The starts table (resources/clojurecraft/starts.edn) as the real and the sim race read it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.gear :as gear]
            [clojurecraft.plan :as plan]
            [clojurecraft.start :as start]))

(deftest a-start-is-found-by-a-keyword-a-string-or-nothing
  (is (= :rods (:start/id (start/start-of :rods))))
  (is (= :rods (:start/id (start/start-of "rods"))))
  (is (= :fortress (:start/id (start/start-of nil))) "the table's default")
  (is (nil? (start/start-of "nowhere"))))

(deftest a-start-in-the-nether-is-the-fortress-or-the-forest
  (is (start/nether? (start/start-of :fortress)))
  (is (start/nether? (start/start-of :rods)) "landed at the fortress pad, as the fortress start")
  (is (start/nether? (start/start-of :forest)) "a Nether start with the fortress's pool and pads")
  (is (not (start/nether? (start/start-of :spawn))))
  (is (start/forest? (start/start-of :forest)))
  (is (not (start/forest? (start/start-of :rods))))
  (is (not (start/forest? nil))))

(deftest a-start-plays-the-chain-from-its-goal
  (let [chain (plan/through "pearls-12")]
    (is (= chain (start/goals (start/start-of :spawn) "pearls-12")) "spawn: every stage")
    (is (= [:blaze-rods :ender-pearls] (start/goals (start/start-of :fortress) "pearls-12"))
        "the blaze rods, then the pearls")
    (is (= [:ender-pearls] (start/goals (start/start-of :rods) "pearls-12")) "the pearls alone")
    (is (= [:ender-pearls] (start/goals (start/start-of :forest) "pearls-12")))))

(deftest the-kit-puts-the-starts-own-items-first
  (is (= ["minecraft:blaze_rod 7" "minecraft:stone_pickaxe 1"]
         (take 2 (start/kit-commands (start/start-of :rods))))
      "the seven rods first: a kit that overfills the slots loses its last stack, never the rods")
  (is (= "minecraft:stone_pickaxe 1" (first (start/kit-commands (start/start-of :fortress)))))
  (is (= [] (start/kit-commands (start/start-of :spawn))) "a start in the overworld gives nothing")
  (is (= 7 (:blaze_rod (start/kit-map (start/start-of :forest)))))
  (is (= 256 (:cobblestone (start/kit-map (start/start-of :fortress))))))

(deftest only-the-pearl-starts-are-capped
  (is (= 3600000 (start/cap-ms (start/start-of :rods))))
  (is (= 3600000 (start/cap-ms (start/start-of :forest))))
  (is (nil? (start/cap-ms (start/start-of :fortress))))
  (is (nil? (start/cap-ms (start/start-of :spawn))))
  (is (= 72000 (start/cap-ticks (start/start-of :rods))) "an hour of game time, 20 ticks a second")
  (is (= 72000 (start/cap-ticks (start/start-of :forest))))
  (is (nil? (start/cap-ticks (start/start-of :fortress)))))

(deftest the-gear-start-hands-over-what-the-stages-before-it-made
  ;; an overworld start at the gear step: the chain from there to the Nether, carrying the portal
  ;; kit, the ingots the whole gear costs and a little wood; the probe that proves a chained run
  ;; enters the Nether with a shield starts here
  (let [gear (start/start-of :gear)
        kit (start/kit-map gear)]
    (is (not (start/nether? gear)))
    (is (= :gear (first (start/goals gear "nether"))))
    (is (= (last (plan/through "nether")) (last (start/goals gear "nether"))))
    (is (= 1 (:flint_and_steel kit) (:water_bucket kit) (:bucket kit)) "the portal kit")
    (is (= (reduce + (map second (gear/pieces))) (:iron_ingot kit)) "every piece's ingots")
    (is (nil? (:shield kit)) "the shield is the gear step's to make")
    (is (= "minecraft:iron_ingot 23"
           (some #(when (str/includes? % "iron_ingot") %) (start/kit-commands gear)))
        "the real race gives the same kit")))
