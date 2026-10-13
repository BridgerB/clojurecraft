(ns clojurecraft.race-test
  "The race (issue #17): the claims reducer never grants one cell to two bots, the spiral visits
   every cell once, and the race's board and summary are functions of values."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.claims :as claims]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.race :as race]))

(def gen-request
  (gen/hash-map :claim/by (gen/elements ["Clj_r01" "Clj_r02" "Clj_r03" "Clj_r04"])
                :claim/cell (gen/tuple (gen/choose -3 3) (gen/choose -3 3))))

(deftest no-cell-is-granted-to-two-bots
  (let [r (tc/quick-check 500
                          (prop/for-all
                           [requests (gen/vector gen-request 0 60)]
                           (let [answers (second (reduce (fn [[held answers] q]
                                                           (let [[held a] (claims/claim held q)]
                                                             [held (conj answers a)]))
                                                         [{} []]
                                                         requests))
                                 granted (filter :claim/granted? answers)]
                             (every? (fn [[_ as]] (= 1 (count (distinct (map :claim/by as)))))
                                     (group-by :claim/cell granted)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest first-come-first-served
  (let [[held a] (claims/claim {} {:claim/by "Clj_r01" :claim/cell [0 0]})
        [held b] (claims/claim held {:claim/by "Clj_r02" :claim/cell [0 0]})
        [_ c] (claims/claim held {:claim/by "Clj_r01" :claim/cell [0 0]})]
    (is (:claim/granted? a))
    (is (not (:claim/granted? b)) "taken")
    (is (:claim/granted? c) "the holder may ask again")))

(deftest the-spiral-visits-each-cell-once-ring-by-ring
  (let [cells (take 25 (claims/spiral))]
    (is (= [0 0] (first cells)))
    (is (= 25 (count (distinct cells))))
    (is (= (set (for [x (range -2 3) z (range -2 3)] [x z])) (set cells)))
    (is (every? #(<= (max (abs (first %)) (abs (second %))) 1) (take 9 cells))
        "the first ring first"))
  (testing "a cell and its middle agree"
    (is (= [2 -1] (claims/cell-of [100 100] (claims/middle [100 100] [2 -1]))))))

(deftest the-board-reads-a-world-value
  (is (= "Clj_r01" (race/bot-name "Clj_r" 1)))
  (let [w (plan/begin (assoc (game/init fx/opts) :player/pos [0.5 70.0 0.5])
                      {:go/goals (plan/through "nether") :go/chain? true}
                      plan/goals)]
    (is (= {:race/target :wood
            :race/reached 0
            :race/dimension "overworld"
            :race/y 70
            :race/deaths 0
            :race/status :active}
           (race/standing w)))
    (is (= "Clj_r01 :active at :wood reached 0 overworld y 70 deaths 0"
           (race/board-line "Clj_r01" w)))))

(deftest a-milestone-is-a-fact-of-what-was-reached
  ;; the observer diffs successive world values: what :plan/reached gained is what happened
  (let [before {:plan/reached #{:wood}}
        world {:plan/reached #{:wood :kit} :time/now 90000 :plan/since 30000}]
    (is (= [:kit] (race/reached-new before world)))
    (is (empty? (race/reached-new world world)) "nothing new, no fact")
    (is (= {:milestone/runner 7
            :milestone/seed "race-1-7"
            :milestone/bot "Clj_r03"
            :milestone/goal :kit
            :milestone/at 90000
            :milestone/after-s 60}
           (race/milestone {:runner 7 :seed "race-1-7"} "Clj_r03" world :kit)))))

(deftest the-summary-is-a-fold-of-results
  (let [s (race/summary [{:name "a" :result {:ok true :reason :goal :plan {:plan/reached #{:wood}}}}
                         {:name "b" :result {:ok false :reason :no-site}}
                         {:name "c" :result {:ok false :reason :no-site}}])]
    (is (= 3 (:race/bots s)))
    (is (= 1 (:race/ok s)))
    (is (= {:goal 1 :no-site 2} (:race/reasons s)))
    (is (= ["a" "b" "c"] (map :name (:race/runs s))))))
