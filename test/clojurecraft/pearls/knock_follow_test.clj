(ns clojurecraft.pearls.knock-follow-test
  "A hit knocks the enderman out of the burrow's reach: the held fight waits for it to walk in
   again, and an angry one that never comes holds the bot no longer than angry-wait-lookouts."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]))

(use-fixtures :once fx/instrumented)

(deftest a-held-fight-outlasts-its-targets-knockback
  (let [knocked (assoc (ps/enderman [14.5 61.0 8.5]) :entity/creepy? true :entity/hurt-at 19000)
        w (assoc (ps/burrowed [8.5 61.0 8.5] {7 knocked}) :time/now 20000)
        fight {:intent/kind :fight :intent/target 7 :intent/hold? true :intent/since 1000}]
    (is (not (pearls/held-idle? w fight)) "hit a second ago: the fight waits for it to come again")
    (is (pearls/held-idle? (assoc w :time/now (+ 19000 pparams/hold-patience-ms 1)) fight)
        "patience run out since the last hit, still out of reach: the fight is over")
    (is (pearls/held-idle? (assoc-in w [:world/entities 7 :entity/hurt-at] nil) fight)
        "never hit: patience from the fight's start, as before")))

(deftest an-angry-one-that-never-comes-holds-the-bot-for-a-while
  (let [angry (assoc (ps/enderman [24.0 61.0 8.5]) :entity/creepy? true)
        w (ps/burrowed [8.5 61.0 8.5] {7 angry})]
    (is (not (psense/angry-wait-over? (ps/looked w 3))))
    (is (psense/angry-wait-over? (ps/looked w pparams/angry-wait-lookouts))
        "angry-wait-lookouts lookouts with no fight since the burrow: it holds no more")))
