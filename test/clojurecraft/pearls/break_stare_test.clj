(ns clojurecraft.pearls.break-stare-test
  "An angry enderman stared at within 16 stands still (EnderMan's freeze goal): the hunt breaks
   the stare so it comes to the sword, and stays in its burrow while one is left angry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.mob :as mob]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(def angry
  "An angry enderman 12 blocks off the burrow's end."
  (assoc (ps/enderman [20.5 61.0 8.5]) :entity/creepy? true))

(deftest a-stare-that-holds-an-angry-enderman-still-is-broken-in-the-burrow
  ;; real-18f on 5d894db: one provoked from the burrow stood at 14.6 blocks for a minute while the
  ;; bot looked at it from its end, and killed it in the open 528 s later
  (let [w0 (ps/burrowed [8.5 61.0 8.5] {7 angry})
        eye (game/eye w0)
        [yaw pitch] (physics/look-at eye (mob/head :enderman [20.5 61.0 8.5]))
        held (-> w0
                 (assoc :player/look [yaw pitch])
                 (assoc :player/controls {:control/look [yaw pitch]}))]
    (is (pearls/freezing? held [yaw pitch]) "the look holds it still")
    (is (= [yaw 90.0] (get-in (plan/steer held ps/row) [:player/controls :control/look]))
        "turned straight down, in the burrow's end too")
    (testing "one in the sword's reach is looked at: the fight needs the aim"
      (let [near (assoc-in held [:world/entities 7 :entity/pos] [10.0 61.0 8.5])
            [ny np] (physics/look-at eye (mob/head :enderman [10.0 61.0 8.5]))]
        (is (not (pearls/freezing? near [ny np])))))
    (testing "a calm one is no matter here (the provoke's own look)"
      (is (not (pearls/freezing? (update-in held [:world/entities 7] dissoc :entity/creepy?)
                                 [yaw pitch]))))))

(deftest the-burrow-is-kept-while-an-angry-enderman-is-alive-within-48
  (let [far (assoc angry :entity/pos [40.5 61.0 8.5])
        w (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 far}) 12)]
    (is (psense/angry-holds? w) "32 blocks off, angry: it holds the bot")
    (is (= [8 61 8] (intent/feet w)))
    (is (= :lookout (:intent/kind (plan/next-intent w ps/row plan/goals))) "it waits in the end")
    (testing "after four times the stale lookouts it no longer holds"
      (is (not (psense/angry-holds? (ps/looked (ps/burrowed [8.5 61.0 8.5] {7 far}) 48)))))))
