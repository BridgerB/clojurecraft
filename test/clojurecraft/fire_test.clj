(ns clojurecraft.fire-test
  "Fire on the player (fire.clj) and the model burning it (sim/tick-burn): the real numbers, race
   f8b28e3 lane 2, are in the namespace's docstring."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fire :as fire]
            [clojurecraft.mob-test :as m]
            [clojurecraft.sim :as sim]))

(defn burn-run
  "Run fire/tick every 50 ms from now 0 to until-ms for a fire lit at 0 for ms, {:hurts n :out-at ms}."
  [ms until-ms env]
  (loop [now 0
         f (fire/ignite nil 0 ms)
         hurts 0]
    (let [[f' event] (fire/tick f now env)]
      (cond (= :out event) {:hurts hurts :out-at now}
            (> now until-ms) {:hurts hurts :out-at nil}
            :else (recur (+ now 50) f' (cond-> hurts (= :hurt event) inc))))))

(deftest a-fireball-burns-five-seconds-one-health-a-second
  ;; five recorded hurts of 1.0 per ignition (on_fire, 1778 hurts in race f8b28e3's lane 2)
  (is (= {:hurts 5 :out-at 5000} (burn-run fire/fireball-ms 6000 {}))))

(deftest water-puts-the-fire-out-at-once
  (is (= {:hurts 0 :out-at 0} (burn-run fire/fireball-ms 6000 {:water? true}))))

(deftest a-second-ignition-keeps-the-longer-burning
  (is (= {:until 8000 :next 0} (fire/ignite (fire/ignite nil 0 8000) 1000 5000)))
  (is (= {:until 6000 :next 0} (fire/ignite (fire/ignite nil 0 5000) 1000 5000))))

(deftest a-fire-block-lights-the-player-for-eight-seconds
  (let [[f event] (fire/tick nil 0 {:flame? true})]
    (is (= {:until fire/in-fire-ms :next 1000} f) "lit and hurt at once")
    (is (= :hurt event))))

(deftest a-blaze-shot-sets-the-player-on-fire-and-the-model-tells-the-bot
  (let [sim (-> (m/arena {} {} :normal m/night [{:type :blaze :pos [9.5 64.0 0.5]}])
                (m/ticks 3))
        said (partial m/said sim)
        flags (map :metadata (filter #(= 1 (:entity-id %)) (said :set-entity-data)))
        sources (map :source-type (said :damage-event))]
    (is (some? (:sim/fire sim)) "burning")
    (is (some #{{0 1}} flags) "the flags byte told: on fire")
    (is (= [14 31] (take 2 sources)) "a fireball, then burning")
    (is (<= (:sim/health sim) 15.0) "the shot's 5, then the burn")))

(deftest water-at-the-feet-puts-the-player-out
  (let [water (blocks/default-state :water)
        sim (-> (m/arena {[0 64 0] water} {} :normal m/night [])
                (assoc :sim/fire (fire/ignite nil 0 5000))
                (m/ticks 2))]
    (is (nil? (:sim/fire sim)))
    (is (some #{{0 0}} (map :metadata (m/said sim :set-entity-data))) "told out")))

(deftest standing-in-a-fire-block-burns
  (let [flame (blocks/default-state :fire)
        sim (-> (m/arena {[0 64 0] flame} {} :normal m/night [])
                (m/ticks 2))]
    (is (some? (:sim/fire sim)))
    (is (< (:sim/health sim) 20.0))))

(deftest a-fireball-against-a-wall-leaves-fire-on-the-floor-for-good-over-netherrack
  (let [rack (blocks/default-state :netherrack)
        flame (blocks/default-state :fire)
        on-rack (-> (m/arena {[5 63 0] rack} {} :normal m/night [])
                    (sim/set-fire [5 64 0])
                    (m/ticks 200))
        on-stone (-> (m/arena {} {} :normal m/night [])
                     (sim/set-fire [5 64 0])
                     (m/ticks 100))]
    (is (= flame (get-in on-rack [:sim/placed [5 64 0]])) "still burning after ten seconds")
    (is (empty? (:sim/fires on-rack)) "no end set")
    (is (zero? (get-in on-stone [:sim/placed [5 64 0]]))
        "stone: air again after about three seconds")
    (is (some #(= flame (:state %)) (m/said on-stone :block-update))
        "the client was told of both")))
