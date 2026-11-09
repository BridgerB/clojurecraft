(ns clojurecraft.pearls.pack-test
  "The melee pack: two wither skeletons at hand are answered at once, in any intent, never fought."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(defn skeleton "A wither skeleton at pos." [pos] (ps/mob :wither_skeleton pos))

(defn held
  "The world with the race kit's sword and a stack of cobblestone held."
  [w]
  (assoc w :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :cobblestone 64)}))

(def travel
  "The walk the real bot was in when four wither skeletons came at it (a :hunt/travel walk)."
  {:intent/kind :walk
   :intent/target [100 64 8]
   :intent/for :pearls
   :intent/goal :ender-pearls
   :intent/why :hunt/travel})

(deftest two-wither-skeletons-at-hand-are-a-pack
  (let [two (held (ps/standing {1 (skeleton [10.5 64.0 8.5]) 2 (skeleton [8.5 64.0 11.5])}))]
    (is (psense/pack-at-hand? two) "two within the pack radius")
    (is (not (psense/pack-at-hand? (held (ps/standing {1 (skeleton [10.5 64.0 8.5])}))))
        "one alone is not a pack")
    (is (not (psense/pack-at-hand? (held (ps/standing {1 (skeleton [16.5 64.0 8.5])
                                                       2 (skeleton [8.5 64.0 15.5])}))))
        "two seven blocks off are not at hand")
    (is (psense/pack-at-hand? (-> (ps/standing {1 (skeleton [9.5 64.0 8.5])
                                                2 (skeleton [15.5 64.0 8.5])})
                                  held
                                  (assoc :time/now 1000 :player/attacker 1 :player/struck-at 900)))
        "one that struck and another closing within the swarm radius")
    (is (not (psense/pack-at-hand? (assoc two :player/pos [8.5 67.0 8.5])))
        "three blocks over their feet, out of every reach")))

(deftest a-pack-is-answered-by-a-pillar-never-a-fight
  (let [w (held (ps/standing {1 (skeleton [10.5 64.0 8.5]) 2 (skeleton [8.5 64.0 11.5])}))
        i (pcover/pack-answer w)]
    (is (= :pillar (:intent/kind i)) "in the open, up out of reach")
    (is (true? (:intent/wall? i)) "and not cut by the hunt's interrupt")
    (is (not= :fight (:intent/kind (plan/next-intent w ps/row plan/goals)))
        "the hunt never fights it")
    (is (= :pillar (:intent/kind (plan/next-intent w ps/row plan/goals))) "the hunt's first answer")
    (testing "under a roof there is no pillar: a block between"
      (let [roof (held (ps/standing {1 (skeleton [10.5 64.0 8.5]) 2 (skeleton [8.5 64.0 11.5])}
                                    {[8 67 8] ps/stone}))
            i (pcover/pack-answer roof)]
        (is (some? i))
        (is (not= :pillar (:intent/kind i)))
        (is (not= :fight (:intent/kind i)))))))

(deftest a-walk-under-a-pack-is-cut-at-once
  ;; real-18 Clj_r12: four wither skeletons took it from 20 to 4 health in 3.3 s while a
  ;; :hunt/travel walk went on, no new intent for 13 s
  (let [w (held (ps/standing {1 (skeleton [10.5 64.0 8.5]) 2 (skeleton [8.5 64.0 11.5])}))]
    (is (plan/interrupts? w ps/row travel) "the pack cuts the walk")
    (is (not (plan/interrupts? (held (ps/standing {})) ps/row travel))
        "with none, the walk goes on")
    (is (not (plan/interrupts? w ps/row (assoc (pcover/pack-answer w) :intent/goal :ender-pearls)))
        "the pillar it is cut for is not cut in turn")))

(deftest a-bot-over-a-pack-holds-its-pillar
  (let [w (-> (ps/standing {1 (skeleton [10.5 64.0 8.5]) 2 (skeleton [8.5 64.0 11.5])}
                           {[8 64 8] ps/stone [8 65 8] ps/stone [8 66 8] ps/stone})
              held
              (assoc :player/pos [8.5 67.0 8.5]))]
    (is (psense/pack-below? w))
    (is (nil? (pcover/pack-answer w)) "out of reach: no new cover")
    (is (= :lookout (:intent/kind (pcover/pack-hold w))) "it stands and looks round")
    (is (= :lookout (:intent/kind (plan/next-intent w ps/row plan/goals))) "never the come-down")
    (is (nil? (pcover/pack-hold (assoc w :world/entities {}))) "with them gone, no hold")
    (testing "on a ledge, not a pillar, over a pack the bot walks on"
      ;; sim R2/5, R2/17 and R2/33 stood 460 to 660 lookouts over skeletons on a lower floor
      (let [ledge
            (-> (ps/standing
                 {1 (skeleton [12.5 64.0 8.5]) 2 (skeleton [8.5 64.0 12.5])}
                 (into {} (for [x (range 6 11) y (range 64 67) z (range 6 11)] [[x y z] ps/stone])))
                held
                (assoc :player/pos [8.5 67.0 8.5]))]
        (is (nil? (pcover/pack-hold ledge)))))))

(deftest a-pocket-with-a-melee-hostile-at-hand-is-no-refuge
  ;; real-18 Clj_r13 held its sealed pocket while two wither skeletons at it took 20 health in 5 s
  (is (fexit/melee-at-hand? (ps/standing {1 (skeleton [9.5 64.0 8.5])})) "one in the sword's reach")
  (is (not (fexit/melee-at-hand? (ps/standing {1 (skeleton [14.5 64.0 8.5])})))
      "one six blocks off")
  (is (not (fexit/melee-at-hand? (ps/standing {1 (ps/mob :blaze [9.5 65.0 8.5])})))
      "a blaze is the refuge's own"))
