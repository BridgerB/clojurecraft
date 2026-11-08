(ns clojurecraft.pearls.burrow-fight-test
  "The pearl hunt fights the enderman that just hit it instead of standing or walking before it, and
   shuts a teleporter out of the burrow with one block while hurt (sim-18f deaths on 371509e: 17 in
   the burrow, standing in a lookout at hp under 10 or with no swing sent, and 21 in the open, 10 of
   them on a walk)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.rules :as rules]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(defn angry "An angry enderman at pos." [pos] (assoc (ps/enderman pos) :entity/creepy? true))

(defn hit
  "w with enderman 7 the one that hit the bot a moment ago."
  [w]
  (assoc w :time/now 1000 :player/attacker 7 :player/struck-at 900))

(def filler "Blocks to lay." (ps/item :cobblestone 64))

(defn burrow-with
  "The burrow's end with an angry enderman at pos and blocks to lay, health hp."
  [pos hp blocks]
  (-> (ps/burrowed [8.5 61.0 8.5] {7 (angry pos)} blocks)
      (assoc :player/health hp)
      (assoc-in [:player/inventory 3] filler)))

(def sealed
  "The stair cells next to the end, stone: a block laid toward the mouth."
  {[7 62 8] ps/stone [7 61 8] ps/stone})

(deftest hit-in-the-burrow-above-danger-health-the-bot-fights-back
  ;; sim-18f: five deaths stood in :lookout, hit six times with no swing, the enderman fought out
  ;; and so no fight's target
  (let [quiet (ps/fought (burrow-with [6.5 61.0 8.5] 20.0 {}) 9)
        w (hit quiet)
        i (rules/decide w)
        lookout {:intent/kind :lookout :intent/for :pearls :intent/status :active}]
    (is (not= :hunt/fight-hit-in-burrow (rules/row-of (assoc quiet :time/now 1000)))
        "fought out and not hitting: no fight is held on it")
    (is (= :hunt/fight-hit-in-burrow (rules/row-of w)))
    (is (= :fight (:intent/kind i)))
    (is (true? (:intent/hold? i)))
    (is (= 7 (:intent/target i)))
    (is (true? (plan/interrupts? w ps/row lookout)) "the lookout at the burrow's end is cut by it")
    (is (not (plan/interrupts? (assoc quiet :time/now 1000) ps/row lookout))
        "and goes on when nothing has hit")))

(deftest hurt-in-the-burrow-the-mouth-is-sealed-then-the-bot-heals-then-unseals
  (let [hurt (burrow-with [11.5 64.0 8.5] 9.0 {})
        shut (burrow-with [11.5 64.0 8.5] 9.0 sealed)
        mended (burrow-with [11.5 64.0 8.5] (dec pparams/resume-hp) sealed)
        healed (burrow-with [11.5 64.0 8.5] 20.0 sealed)]
    (is (= :hunt/seal-mouth (rules/row-of hurt)))
    (is (= :place (:intent/kind (rules/decide hurt))))
    (is (some? (:intent/cell (rules/decide hurt))) "one block toward the mouth")
    (is (= :hunt/heal-sealed (rules/row-of shut)) "sealed and hurt: eat and wait")
    (is (= :hunt/heal-sealed (rules/row-of mended)) "and until the resume line")
    (is (= :hunt/unseal-healed (rules/row-of healed)) "healed: the block is dug out")
    (is (= :dig (:intent/kind (rules/decide healed))))))

(deftest the-enderman-that-hit-the-bot-is-fought-before-a-walk-home
  ;; sim-18f: ten deaths on a wander, search, relocate or walk-home with 0 to 3 swings
  (let [near (ps/wide (ps/burrowed [8.5 64.0 14.5] {7 (angry [10.5 64.0 14.5])}))]
    (is (= :hunt/walk-home (rules/row-of near)) "not yet hit: the burrow is made for the walk")
    (is (= :hunt/fight-hit (rules/row-of (hit near))))
    (is (= :fight (:intent/kind (rules/decide (hit near)))))))
