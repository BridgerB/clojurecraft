(ns clojurecraft.pearls.pit-test
  "Under danger-health with an angry enderman at hand, two cells straight down: healed there, the
   legs fought from there at resume-hp; and the hunt owns every enderman that strikes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.pit :as ppit]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(def ground
  "Stone six blocks deep under the bot's floor, across the column."
  (into {} (for [x (range 0 16) y (range 58 64) z (range 0 16)] [[x y z] ps/stone])))

(def angry
  "An angry enderman a block off."
  (assoc (ps/enderman [9.5 64.0 8.5]) :entity/creepy? true))

(defn bot
  "A bot on the ground at y 64 with an iron pickaxe and sword, the enderman at hand, at health
   hp, with blocks over the ground."
  [hp blocks]
  (-> (ps/standing {7 angry} (merge ground blocks))
      (assoc :player/health hp :plan/goals #{:ender-pearls})
      (assoc :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :iron_pickaxe 1)})))

(deftest under-danger-the-bot-digs-two-down
  (let [w (bot 8.0 {})]
    (is (= [8 63 8] (:intent/target (ppit/dig w))) "the cell under the feet")
    (is (= :dig (:intent/kind (plan/next-intent w ps/row plan/goals))) "the hunt's answer")
    (testing "a block deep, the next cell"
      (let [w1 (-> (bot 8.0 {[8 63 8] ps/air})
                   (assoc :player/pos [8.5 63.0 8.5]))]
        (is (= [8 62 8] (:intent/target (ppit/dig w1))))))
    (testing "over healthy, no pit: the fight is the answer" (is (nil? (ppit/dig (bot 15.0 {})))))
    (testing "lava under the second cell: no pit"
      (is (nil? (ppit/dig (bot 8.0 {[8 61 8] (blocks/default-state :lava)})))))
    (testing "no pickaxe: no pit"
      (is (nil? (ppit/dig (assoc (bot 8.0 {}) :player/inventory {0 (ps/item :iron_sword 1)})))))))

(deftest in-the-pit-the-bot-heals-and-fights-only-at-resume-hp
  (let [pit {[8 63 8] ps/air [8 62 8] ps/air}
        w (-> (bot 8.0 pit)
              (assoc :player/pos [8.5 62.0 8.5]))]
    (is (ppit/in-pit? w))
    (is (nil? (ppit/dig w)) "deep enough")
    (is (not= :fight (:intent/kind (ppit/hold w))) "under resume-hp it never swings (#577)")
    (is (= :fight (:intent/kind (ppit/hold (assoc w :player/health 19.5))))
        "healed, the legs from the pit")
    (is (true? (:intent/yield? (ppit/hold (assoc w :player/health 19.5)))))
    (testing "with no enderman near and no strike, the pit is left"
      (is (nil? (ppit/hold (assoc w :world/entities {})))))))

(deftest an-enderman-that-struck-is-the-hunts-in-the-pearl-stage
  ;; a :defend fight on it took turns with the hunt's burrow cut every second and neither finished
  (let [w (-> (bot 20.0 {})
              (assoc :player/attacker 7 :player/struck-at 900 :time/now 1000))]
    (is (combat/claims-attacker? w ps/row angry))))
