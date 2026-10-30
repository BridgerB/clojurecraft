(ns clojurecraft.dodge-test
  "Dodging a blaze's fireballs (issue #262) over volleys read from blaze-race-l race 2's recording
   (plan run 40976506117, volleys.ts): the fireball's spawn position and velocity, the bot's
   position, and that it hit. Coordinates are moved to the fixture's open floor (feet at y 64)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dodge :as dodge]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn fireball
  "A tracked small fireball at pos with velocity vel, as add-entity left it."
  [pos vel]
  {:entity/type (blocks/entity-types :small_fireball)
   :entity/pos pos
   :entity/vel vel
   :entity/seen-at 0})

(defn standing
  "A play world on open floor with the feet at pos and the entities es by id."
  ([pos es] (standing pos es {}))
  ([pos es chunk]
   (assoc (game/init fx/opts)
          :bot/phase :play
          :player/pos pos
          :player/loaded? true
          :player/on-ground? true
          :player/health 20.0
          :time/now 0
          :world/chunks {[0 0] (world/column chunk)}
          :world/entities es)))

(def volley-1
  "Fireball 542 at +210.4 s: spawned 4.6 blocks off at [2347.7 62.4 2282.0] with velocity
   [-0.064 -0.020 0.074] and hit the bot at [2344.6 60.0 2284.5] 450 ms later (a line passing 1.04
   blocks from its body). Moved -2336 on x, +4 on y, -2276 on z."
  {:pos [8.6 64.0 8.5] :fb (fireball [11.7 66.4 6.0] [-0.064 -0.020 0.074])})

(def volley-2
  "Fireball 574 at +337.5 s: spawned 5.8 blocks off at [2347.8 62.4 2281.8] with velocity
   [-0.005 -0.068 0.074] and hit the bot at [2347.5 58.0 2285.5] 550 ms later, head on (a line
   passing 0.13 blocks from its body). Moved -2336 on x, +6 on y, -2276 on z."
  {:pos [11.5 64.0 9.5] :fb (fireball [11.8 68.4 5.8] [-0.005 -0.068 0.074])})

(deftest a-fireball-that-hit-is-seen-coming-with-its-line-and-its-time
  (let [w (standing (:pos volley-1) {541 (:fb volley-1)})
        [c] (dodge/incoming w)]
    (is (= 541 (:eid c)))
    (is (< 0.9 (:miss c) 1.2) "the line passes about a block from the body")
    (is (< 350 (:eta-ms c) 500) "it arrived 450 ms after it spawned")
    (testing
      "a fireball whose line passes 1.6 blocks or more away is not coming: none of 76 such hit"
      (is (empty? (dodge/incoming (standing (:pos volley-1)
                                            {1 (fireball [11.7 66.4 6.0] [-0.074 -0.020 0.02])})))))
    (testing "one flying away is not coming"
      (is (empty? (dodge/incoming (standing (:pos volley-1)
                                            {1 (fireball [11.7 66.4 6.0] [0.064 0.02 -0.074])})))))
    (testing "a blaze, a drop or a fireball with no velocity is not a fireball in flight"
      (is (empty? (dodge/incoming (standing (:pos volley-1)
                                            {1 (dissoc (:fb volley-1) :entity/vel)
                                             2 (assoc (:fb volley-1)
                                                      :entity/type
                                                      (blocks/entity-types :blaze))})))))))

(deftest the-bot-steps-clear-of-the-line-before-it-arrives
  (doseq [{:keys [pos fb]} [volley-1 volley-2]]
    (let [w (standing pos {1 fb})
          i (dodge/intent-for w)
          c (:intent/target i)]
      (is (= :dodge (:intent/kind i)))
      (is (some? c))
      (is (>= (dodge/miss-at [fb] c) dodge/worth-radius)
          "the line passes farther from the new cell")
      (is (> (dodge/miss-at [fb] c) (:miss (first (dodge/incoming w)))) "than from where it stood")
      (is (<= (+ dodge/move-base-ms (* dodge/move-ms-per-block (path/octile (intent/feet w) c)))
              (+ (:eta-ms (first (dodge/incoming w))) dodge/slack-ms))
          "it gets there in time"))))

(deftest in-a-tunnel-one-wide-there-is-no-cell-beside-it-to-dodge-to
  (let [stone (blocks/default-state :stone)
        walls (into {} (for [x (range 3 14) y [64 65 66] z [7 9]] [[x y z] stone]))
        roof (into {} (for [x (range 3 14)] [[x 66 8] stone]))
        w (standing (:pos volley-1) {1 (:fb volley-1)} (merge walls roof))]
    (is (seq (dodge/incoming w)) "the line still passes the body")
    (is (every? #(= 8 (nth % 2)) (keep #(:intent/target (dodge/intent-for %)) [w]))
        "no cell beside the tunnel: a dodge, if any, is along it")))

(deftest a-fireball-in-flight-is-dodged-before-anything-but-fire-at-the-feet
  (let [w (standing (:pos volley-1) {1 (:fb volley-1)})
        run (fn [w]
              (:plan/intent (intent/run (assoc w :plan/intent (dodge/intent-for w) :time/now 1000)
                                        (dodge/intent-for w)
                                        {:event/kind :tick :event/now 1000})))]
    (is (not (intent/done? (run w))) "walking at the cell")
    (testing "centred in it, done"
      (let [c (:intent/target (dodge/intent-for w))
            at (assoc w :player/pos (mapv + (mapv double c) [0.5 0.0 0.5]))
            i {:intent/kind :dodge :intent/target c}]
        (is (intent/done? (:plan/intent (intent/run (assoc at :plan/intent i :time/now 1000)
                                                    i
                                                    {:event/kind :tick :event/now 1000}))))))))
