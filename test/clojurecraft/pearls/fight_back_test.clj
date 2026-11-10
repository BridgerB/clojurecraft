(ns clojurecraft.pearls.fight-back-test
  "Struck by an enderman on the walk home, the bot fights back where it stands."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(def home "The burrow's end the walk goes to." [20 64 8])

(def walk-home
  "The walk the real bots kept while an enderman hit them."
  {:intent/kind :walk
   :intent/target home
   :intent/for :shelter
   :intent/goal :ender-pearls
   :intent/why :hunt/walk-home})

(defn struck
  "A bot on its walk home, an angry enderman a block off that struck it 200 ms ago, at health hp."
  [hp]
  (-> (ps/standing {7 (assoc (ps/enderman [9.5 64.0 8.5]) :entity/creepy? true)})
      (assoc :player/health hp
             :time/now 1000
             :player/attacker 7
             :player/struck-at 800
             :pearls/shelter home
             :plan/intent walk-home)))

(deftest a-strike-on-the-walk-home-is-fought-back
  ;; five real bots kept the walk home under three to six hits, from 11 to 20 health, never swung
  (let [w (struck 16.0)]
    (is (= 7 (first (psense/home-striker w))))
    (is (plan/interrupts? w ps/row walk-home) "the walk is cut")
    (let [i (plan/next-intent w ps/row plan/goals)]
      (is (= :fight (:intent/kind i)))
      (is (= 7 (:intent/target i)))
      (is (true? (:intent/yield? i)) "breaking off under retreat-health (#577)"))
    (testing "with no strike the walk home is never cut"
      (is (not (plan/interrupts? (dissoc w :player/attacker :player/struck-at) ps/row walk-home))))
    (testing "hurt under danger-health, the walk home goes on: the burrow is the answer"
      (is (not= :fight (:intent/kind (plan/next-intent (struck 8.0) ps/row plan/goals)))))))
