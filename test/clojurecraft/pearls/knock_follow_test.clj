(ns clojurecraft.pearls.knock-follow-test
  "A hit knocks the enderman out of the burrow's reach: the held fight waits for it to walk in
   again, and an angry one that never comes holds the bot no longer than angry-wait-lookouts."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
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
  ;; one stood 15.8 blocks off, unhurt and unseen, for 546 lookouts: the run's whole remainder
  (let [angry (assoc (ps/enderman [20.5 61.0 8.5]) :entity/creepy? true)
        span (* pparams/angry-wait-lookouts pparams/lookout-ms)
        at (fn [closed now]
             (-> (ps/burrowed [8.5 61.0 8.5] {7 angry})
                 (assoc :time/now now :pearls/angry {7 {:since 0 :best 12.0 :closed-at closed}})))]
    (is (not (psense/waited-out? (at 0 (dec span)))) "it came nearer within the span: it holds")
    (is (psense/waited-out? (at 0 span)) "no nearer for the whole span: it holds no more")
    (is (not (psense/waited-out? (at span (+ span 1000)))) "it came nearer again: it holds again")
    (is (not (psense/waited-out? (assoc (at 0 span) :pearls/angry {}))) "untracked: no verdict")
    (is (psense/stale? (assoc (ps/looked (at 0 span) pparams/stale-lookouts) :time/now span))
        "waited out, the burrow can go stale as with none near")))

(deftest a-parked-out-angry-one-blocks-no-provoke-and-keeps-no-burrow
  ;; sim forest R1/17 seed 3 and R2/21 seed 2 (knockback): the last provoke angered one 10.3 blocks
  ;; off that never came, and nothing was provoked again for 54 and 52 minutes: one at a time held
  ;; every provoke off, and the angry hold kept the burrow from going stale
  (let [angry (assoc (ps/enderman [18.8 61.0 8.5]) :entity/creepy? true)
        span (* pparams/angry-wait-lookouts pparams/lookout-ms)
        at (fn [now]
             (-> (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 angry}) pparams/stale-lookouts)
                 (assoc :time/now now :pearls/angry {7 {:since 0 :best 10.3 :closed-at 0}})))]
    (is (psense/coming? (at (dec span))) "within the span it is still the one the hunt waits for")
    (is (not (psense/coming? (at span))) "parked out: the next one may be provoked")
    (is (some? (pplace/shelter (at (dec span)))) "within the span the burrow is held")
    (is (nil? (pplace/shelter (at span))) "parked out: the stale burrow is let go")))

(deftest a-parked-out-one-is-drawn-in-by-lengthening-the-burrow
  ;; anger never ends while an enderman has the bot as its target, and past 16 blocks it teleports
  ;; toward the bot: the burrow lengthened away from a parked-out one brings it in at the burrow
  (let [parked (assoc (ps/enderman [8.5 61.0 18.5]) :entity/creepy? true)
        span (* pparams/angry-wait-lookouts pparams/lookout-ms)
        at (fn [now]
             (-> (ps/burrowed [8.5 61.0 8.5] {7 parked})
                 (assoc :time/now now :pearls/angry {7 {:since 0 :best 10.0 :closed-at 0}})))
        i (pplace/draw-out (at span))]
    (is (= :extend-burrow (:intent/kind i)) (pr-str i))
    (is (= [0 -1] (:intent/dir i)) "straight away from it")
    (is (= 7 (:intent/length i)) "the fewest cells past 16.5")
    (is (nil? (pplace/draw-out (at (dec span)))) "not parked out yet: no draw")
    (is (nil? (pplace/draw-out (assoc (at span) :pearls/angry {}))) "untracked: no draw")))
