(ns clojurecraft.pearls.burrow-fight-test
  "The pearl hunt fights an angry enderman at hand instead of standing or walking before it, and
   shuts a teleporter out of the burrow with one block while hurt (sim-18f deaths on 371509e:
   17 in the burrow, standing in a lookout at hp under 10 or with no swing sent, and 21 in the
   open, 10 of them on a walk)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.rules :as rules]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(defn angry "An angry enderman at pos." [pos] (assoc (ps/enderman pos) :entity/creepy? true))

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

(deftest struck-in-the-burrow-above-danger-health-the-bot-fights-back
  ;; sim-18f: five deaths stood in :lookout, struck six times with no swing, the enderman fought
  ;; out and so no fight's target
  (let [w (-> (burrow-with [6.5 61.0 8.5] 20.0 {})
              (ps/fought 9))
        i (rules/decide w)]
    (is (= :hunt/fight-adjacent-in-burrow (rules/row-of w)))
    (is (= :fight (:intent/kind i)))
    (is (true? (:intent/hold? i)))
    (is (= 7 (:intent/target i)))
    (is (true? (plan/interrupts?
                w
                ps/row
                {:intent/kind :lookout :intent/for :pearls :intent/status :active}))
        "a lookout at the burrow's end is cut by an enderman at hand")))

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

(deftest an-angry-enderman-at-hand-is-fought-before-a-walk-home
  ;; sim-18f: ten deaths on a wander, search, relocate or walk-home with 0 to 3 swings
  (let [w (ps/wide (ps/burrowed [8.5 64.0 14.5] {7 (angry [10.5 64.0 14.5])}))]
    (is (= :hunt/fight-adjacent (rules/row-of w)))
    (is (= :fight (:intent/kind (rules/decide w))))))
