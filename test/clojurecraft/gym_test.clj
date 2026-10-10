(ns clojurecraft.gym-test
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.gym :as gym]
            [clojurecraft.landings :as landings]
            [clojurecraft.plan :as plan]
            [clojurecraft.spec]))

(deftest the-registry-is-data-and-agrees-with-the-goal-table
  (is (every? #(s/valid? :clojurecraft.spec/gym %) gym/gyms))
  (is (= gym/gyms (edn/read-string (pr-str gym/gyms))) "printable and readable back")
  (testing "every row's --until puts exactly its goal in play"
    (is (every? #(= [(:gym/goal %)] (plan/goals-for (:gym/until %))) gym/gyms)))
  (is (= :kit (:gym/goal (gym/gym "table"))) "a row is found by its --until name")
  (is (= :kit (:gym/goal (gym/gym :kit))) "or by its goal"))

(def ok-result {:ok true :reason :goal :stats {}})

(def found {:truth/cmd "clear Clj_g1 #minecraft:logs 0" :truth/reply "Found 1 matching item(s)" :truth/ok? true})
(def none {:truth/cmd "clear Clj_g1 #minecraft:logs 0" :truth/reply "No items were found" :truth/ok? false})

(defn left [& {:as m}]
  (merge {:gym/landed? true :gym/result ok-result :gym/truths [found] :gym/ms 1000}
         (into {} (map (fn [[k v]] [(keyword "gym" (name k)) v])) m)))

(deftest outcome-is-judged-twice
  (is (= :pass (gym/outcome (left))))
  (is (= :fail (gym/outcome (left :truths [found none])))
      "the bot says ok but the server disagrees: :fail")
  (is (= :fail (gym/outcome (left :truths []))) "no truth answered: never a pass")
  (is (= :fail (gym/outcome (left :result {:ok false :reason :stuck :stats {}}))))
  (is (= :timeout (gym/outcome (left :result {:ok false :reason :timeout :stats {}}))))
  (is (= :death (gym/outcome (left :result {:ok false :reason :timeout :stats {:stats/deaths 1}}))))
  (is (= :disconnect (gym/outcome (left :result {:ok false :reason :disconnected :disconnected "kicked" :stats {}}))))
  (is (= :disconnect (gym/outcome (left :result nil))) "the bot never printed RESULT")
  (is (= :harness (gym/outcome (left :landed? false))) "the landing failed before :go"))

(deftest a-truth-reads-the-servers-reply
  (let [row {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]
    (is (= "clear Clj_g1 minecraft:stick 0" (gym/truth-cmd row "Clj_g1")))
    (is (:truth/ok? (gym/truth row "Clj_g1" "Found 4 matching item(s) on player Clj_g1")))
    (is (:truth/ok? (gym/truth row "Clj_g1" "Found 12 matching item(s) on player Clj_g1")))
    (is (not (:truth/ok? (gym/truth row "Clj_g1" "Found 3 matching item(s) on player Clj_g1"))))
    (is (not (:truth/ok? (gym/truth row "Clj_g1" "No items were found on player Clj_g1"))))))

(deftest the-result-line-is-read-from-the-log
  (is (= {:ok true :reason :goal} (gym/parse-result "12:00 connected\nRESULT {:ok true, :reason :goal}\n12:01 holding")))
  (is (nil? (gym/parse-result "12:00 connected\n"))))

(deftest wilson-intervals
  (let [[lo hi] (gym/wilson 12 12)] (is (< 0.75 lo 0.76)) (is (= 1.0 hi)))
  (let [[lo hi] (gym/wilson 6 12)] (is (< 0.25 lo 0.26)) (is (< 0.74 hi 0.75)))
  (is (= [0.0 1.0] (gym/wilson 0 0))))

(deftest the-report-folds-results
  (let [rows [(gym/judged (merge (left) {:gym/goal :wood :gym/run 1 :gym/landing [1 2 70] :gym/ms 20000}))
              (gym/judged (merge (left :truths [none])
                                 {:gym/goal :wood :gym/run 2 :gym/landing [3 4 70] :gym/ms 30000}))
              (gym/judged (merge (left :landed? false) {:gym/goal :wood :gym/run 3 :gym/landing [5 6 70]}))]
        md (gym/report rows)]
    (is (every? #(s/valid? :clojurecraft.spec/gym-result %) rows))
    (is (str/includes? md "pass 1/2 [9%, 91%]; not counted: harness 1, disconnect 0"))
    (is (str/includes? md "| 2 | 3,4 | fail | 30 | goal | No items were found |") "the disagreement shows its truth text")
    (is (str/includes? md "| 1 | 1,2 | pass | 20 | goal | Found 1 matching item(s) |"))
    (is (str/includes? md "outcomes: fail 1, harness 1, pass 1"))))

(deftest the-plan-is-goals-by-runs
  (let [m (gym/plan-matrix ["wood" "pickaxe"] 2)]
    (is (= 4 (count (re-seq #"\"run\"" m))))
    (is (str/includes? m "{\"goal\":\"wood\",\"run\":2,\"minutes\":10}"))
    (is (str/includes? m "{\"goal\":\"pickaxe\",\"run\":1,\"minutes\":13}"))))

(deftest landings-are-paired-and-checked
  (let [g (landings/grid [20000 20000] 20 640)]
    (is (= 20 (count g)) "n cells")
    (is (apply distinct? g))
    (is (= g (landings/grid [20000 20000] 20 640)) "the same set every time"))
  (let [ls [[1 2 70] [3 4 71] [5 6 72]]]
    (is (= [1 2 70] (landings/landing-for ls 1)) "run 1 lands on the first")
    (is (= [1 2 70] (landings/landing-for ls 4)) "runs past the end wrap"))
  (is (= [2000 3000] (landings/parse-located "The nearest #minecraft:is_forest is at [2000, ~, 3000] (12 blocks away)")))
  (is (= 169 (landings/chunk-count (landings/square [100 100] 96))) "13x13 chunks at radius 96")
  (is (<= (landings/chunk-count (landings/square [-8 -8] 96)) landings/max-forceload-chunks))
  (testing "a landing is fit when high enough and no check passed"
    (is (landings/fit? [0 70 0] [[:water "Test failed"] [:tree "Test failed"]]))
    (is (not (landings/fit? [0 50 0] [[:water "Test failed"]])) "too low")
    (is (not (landings/fit? [0 70 0] [[:water "Test passed"]])) "water")))
