(ns clojurecraft.blind-test
  "Blind: what the model's truth knew at a moment that the bot's moment did not hold. Pure over two
   small maps, as botlogs reads them from a sim run's files."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.blind :as blind]
            [clojurecraft.botlogs :as botlogs]))

(def truth
  "Three live mobs round the player and one corpse, two blows dealt (a blaze's, a skeleton's)."
  {:player {:pos [0.0 64.0 0.0] :health 6.0}
   :mobs [{:eid 5 :type :blaze :d 3.0 :targets? true} {:eid 6 :type :skeleton :d 9.0 :targets? true}
          {:eid 7 :type :zombie :d 14.0 :targets? false}
          {:eid 8 :type :creeper :d 2.0 :targets? true :dead-at 900}]
   :hits [{:t 1000 :cause :blaze :by 5 :dealt 5.0} {:t 2000 :cause :skeleton :by 6 :dealt 3.0}
          {:t 3000 :cause :lava :by nil :dealt 4.0}]})

(def moment
  "The bot tracked the blaze and the zombie, ranked neither a threat, felt the blaze's blow knowing
   who it was and the skeleton's not."
  {:entities {5 {:kind :blaze :d 3.0} 7 {:kind :zombie :d 14.0}}
   :threats []
   :hits [{:at 1010 :type :fireball :by 5 :by-kind :blaze} {:at 2005 :type :arrow}]})

(deftest a-mob-the-bot-never-tracked-is-named
  (is (= [{:eid 6 :kind :skeleton :d 9.0 :targets? true}] (blind/untracked truth moment))
      "the skeleton; the corpse is not a mob the bot should have tracked"))

(deftest a-mob-that-targets-the-bot-and-was-ranked-no-threat-is-named
  (is (= [{:eid 5 :kind :blaze :d 3.0}] (blind/unthreatened truth moment))
      "the blaze targets the bot and was tracked; the zombie does not target it")
  (is (= [] (blind/unthreatened truth (assoc moment :threats [5]))) "ranked, so not blind")
  (is (= [] (blind/unthreatened truth (dissoc moment :threats)))
      "an older moment names no threats: nothing to compare"))

(deftest a-blow-from-an-attacker-the-bot-did-not-know-is-named
  (is (= [{:t 2000 :cause :skeleton :by 6 :dealt 3.0}] (blind/unseen-hits truth moment))
      "the skeleton's arrow was felt with no attacker; the lava has no attacker to be blind to")
  (is (= [{:t 1000 :cause :blaze :by 5 :dealt 5.0} {:t 2000 :cause :skeleton :by 6 :dealt 3.0}]
         (blind/unseen-hits truth (assoc moment :hits [])))
      "a blow never felt at all is unseen too"))

(deftest the-blind-map-holds-only-what-was-missed
  (let [b (blind/blind truth moment)]
    (is (= #{:untracked :unthreatened :unseen-hits} (set (keys b))))
    (testing "the text a case prints, one blind: line each"
      (let [lines (str/split-lines (blind/blind-text b))]
        (is (= 3 (count lines)))
        (is (every? #(str/starts-with? % "blind: ") lines))
        (is (str/includes? (first lines) "never tracked skeleton#6")))))
  (is (nil? (blind/blind {:mobs [] :hits []} moment)) "nothing missed, nothing said")
  (is (= "" (blind/blind-text nil)))
  (is (nil? (blind/blind nil moment)) "no truth, no word")
  (is (nil? (blind/blind {:unavailable "threw"} moment)) "a run that threw has no truth to compare")
  (is (nil? (blind/blind truth nil)) "no moment, no word"))

(deftest a-case-prints-its-blind-lines
  (let [c {:bot "R1-3-9"
           :ending :death
           :killer "none"
           :at-end {}
           :hits 0
           :hits-by {}
           :swings 0
           :landed 0
           :replay "r"
           :timeline []
           :end 0}]
    (is (not (str/includes? (botlogs/case-text c) "blind:")) "no blindness, no line")
    (is (str/includes? (botlogs/case-text (assoc c :blind (blind/blind truth moment)))
                       "blind: never tracked skeleton#6"))))
