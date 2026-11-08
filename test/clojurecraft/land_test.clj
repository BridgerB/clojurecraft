(ns clojurecraft.land-test
  "The warped-forest landing refuses a floor beside a Nether fortress: the biome test asks no
   structure, and a forest that overlaps a fortress put bots among wither skeletons and blazes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.land :as land]))

(defn server
  "A stand-in RCON connection ({:answer f}, no socket) and the commands it was sent, in an atom.
   Each spread puts the bot on the next of floors ([x y z] doubles); the fortress locate names
   fortress-at ([x z]) wherever it is asked."
  [floors fortress-at]
  (let [sent (atom [])
        at (atom -1)]
    {:sent sent
     :rc {:answer (fn [c]
                    (swap! sent conj c)
                    (cond (str/includes? c "run spreadplayers")
                          (do (swap! at inc)
                              "Spread 1 player around 0.0, 0.0 with an average distance of 1")
                          (str/includes? c " Pos")
                          (let [[x y z] (nth floors (min @at (dec (count floors))))]
                            (str "Clj_t has the following entity data: [" x "d, " y "d, " z "d]"))
                          (str/includes? c "if biome") "Test passed"
                          (str/includes? c "cave_air") "Test failed"
                          (str/includes? c "locate structure minecraft:fortress")
                          (str "The nearest minecraft:fortress is at ["
                               (first fortress-at)
                               ", ~, "
                               (second fortress-at)
                               "] (30 blocks away)")
                          :else "ok"))}}))

(deftest a-forest-floor-beside-a-fortress-is-refused
  (let [{:keys [sent rc]} (server [[430.5 57.0 941.5]] [450 930])]
    (testing "a located fortress 20 blocks from the first floors: those are refused"
      (is (nil? (land/warped-floor! rc "Clj_t" [430 0 941]))
          "every floor of the fixture is beside it, so the forest gives none")
      (is (some #(str/includes? % "locate structure minecraft:fortress") @sent)
          "the fortress was asked about")
      (is (= (* land/warped-tries (count land/warped-ranges))
             (count (filter #(str/includes? % "run spreadplayers") @sent)))
          "every spread was tried and refused"))))

(deftest a-forest-floor-clear-of-the-fortress-is-taken
  (let [{:keys [rc]} (server [[430.5 57.0 941.5] [900.5 70.0 -300.5]] [450 930])]
    (is (= [900 70 -301] (land/warped-floor! rc "Clj_t" [430 0 941]))
        "the first floor is beside the fortress, the second is far from it")))

(deftest fortress-near-is-a-distance-on-the-plane
  (let [near? (fn [at cell] (land/fortress-near? (:rc (server [[0.0 0.0 0.0]] at)) cell))]
    (is (near? [100 100] [100 64 100]))
    (is (near? [100 (+ 100 land/fortress-clear)] [100 64 100]) "at the clear")
    (is (not (near? [100 (+ 101 land/fortress-clear)] [100 64 100])) "past it")))

(deftest no-fortress-located-is-not-near
  (let [rc {:answer (fn [_] "Could not find a minecraft:fortress within reasonable distance")}]
    (is (false? (land/fortress-near? rc [0 64 0])))))
