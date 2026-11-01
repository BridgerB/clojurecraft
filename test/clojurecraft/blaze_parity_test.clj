(ns clojurecraft.blaze-parity-test
  "The model's blaze held to what the real ones do (test/clojurecraft/real_blaze.edn, race f8b28e3,
   lane 2, 45 recordings): the volley's cycle and shot gap, the share of a fireball and of a melee
   hit the armour leaves, the burn's per-hurt damage, the hit rate and the burn-to-fireball split on
   a bot that stands in the open at ten blocks. A change to the blaze, the fireball, the fire or the
   armour in the model that drifts from the recordings fails here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojurecraft.fire :as fire]
            [clojurecraft.mob :as mob]
            [clojurecraft.mob-test :as m]
            [clojurecraft.sim :as sim]))

(def real (edn/read-string (slurp (io/resource "clojurecraft/real_blaze.edn"))))

(defn scene
  "A blaze 10 blocks from a bot standing in the open, run for n ticks with the bot's health kept
   full (a fight of many volleys): {:fireball-ticks [tick ...] :hits n :fireballs n :burn-hurts n
   :fireball-hp hp :burn-hp hp}."
  [n]
  (loop [s (m/arena {} {} :normal m/night [{:type :blaze :pos [10.5 65.0 0.5]}])
         i 1
         acc {:fireball-ticks [] :hits 0 :burn-hurts 0 :fireball-hp 0.0 :burn-hp 0.0}]
    (if (> i n)
      acc
      (let [s' (-> (m/ticks (assoc s :sim/out []) 1)
                   (assoc :sim/health 20.0))
            out (:sim/out s')
            spawned (count (filter #(and (= :add-entity (:packet/name %))
                                         (= sim/fireball-type (:type %)))
                                   out))
            dmg (filter #(= :damage-event (:packet/name %)) out)
            by (fn [t] (count (filter #(= t (:source-type %)) dmg)))]
        (recur s'
               (inc i)
               (-> acc
                   (update :fireball-ticks into (repeat spawned i))
                   (update :hits + (by 14))
                   (update :burn-hurts + (by 31))
                   (update :fireball-hp + (* (by 14) 5.0))
                   (update :burn-hp + (* (by 31) fire/burn-damage))))))))

(def run (delay (scene 5400)))

(deftest the-volley-matches-the-recorded-cycle-and-shot-gap
  (let [ticks (:fireball-ticks @run)
        gaps (map - (rest ticks) ticks)
        in-volley (filter #(< % 20) gaps)
        between (filter #(>= % 20) gaps)
        tol (:tolerance real)]
    (is (< 6 (count ticks)))
    (is (every? #(<= (abs (- % (* 20 (get-in real [:volley :shot-gap-s]))))
                     (* 20 (:shot-gap-s tol)))
                in-volley)
        "shots 0.30 s apart")
    (is (= (get-in real [:volley :fireballs]) (inc (count (take-while #(< % 20) gaps))))
        "three to a volley")
    (is (every? #(<= (abs (- (+ % 12) (* 20 (get-in real [:volley :cycle-min-s]))))
                     (* 20 (:volley-cycle-s tol)))
                between)
        "volleys 8.9 s apart, start to start")))

(deftest armour-leaves-the-recorded-share-of-a-hit
  (let [{:keys [fireball melee armour-points]} (:damage real)
        tol (get-in real [:tolerance :per-hurt])]
    (is (< (abs (- (:per-hurt fireball) (mob/absorbed 5.0 armour-points 0.0))) tol))
    (is (< (abs (- (:per-hurt melee) (mob/absorbed 6.0 armour-points 0.0))) tol))
    (is (= (get-in real [:damage :burn :per-hurt]) fire/burn-damage))))

(deftest a-bot-in-the-open-is-hit-and-burnt-as-the-recordings-say
  (let [{:keys [fireball-ticks hits burn-hp fireball-hp]} @run
        rate (/ hits (count fireball-ticks))
        tol (:tolerance real)]
    ;; the table's sim column, printed for the PR and the probes: the real hit rate (0.043 within 12
    ;; blocks) is a floor, the bot sat in pockets for most of those fireballs; a bot standing in the
    ;; open at ten blocks is hit about one in five. eftest replaces *out* and System/out and shows
    ;; them only when a test fails, so the line is written to the process's real stdout.
    (let [line (str "PARITY open-ground hit rate " (format "%.3f" (double rate))
                    " of " (count fireball-ticks)
                    " fireballs; burn hp " burn-hp
                    " fireball hp " fireball-hp
                    " ; real floor " (get-in real [:hit-rate :within-12]))
          ^java.io.PrintStream out (java.io.PrintStream. (java.io.FileOutputStream.
                                                          java.io.FileDescriptor/out)
                                                         true
                                                         "UTF-8")]
      (.println out line))
    (is (<= (:hit-rate-open-min tol) rate (:hit-rate-open-max tol)) (str "hit rate " rate))
    (is (<= (:burn-to-fireball-min tol) (/ burn-hp fireball-hp) (:burn-to-fireball-max tol))
        "the burn costs about what the fireballs do")))
