(ns clojurecraft.site-test
  "The results site's pure parts (bb site): the road from the goal table, a gym summary read
   back, the gym lines kept once each, the agents' history shaped from GitHub's lists. Babashka
   runs these (bb test:bb)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.page :as page]
            [clojurecraft.site :as site]))

(def summary-md
  "A gym run's summary.md, as gym.yml's report writes it."
  "## gym blazefort-k on landing set A at 0117de2

### blaze-rods

pass 1/5 [4%, 62%]; not counted: harness 0, disconnect 0

| run | landing | outcome | s | reason | truth |
|---|---|---|---|---|---|
| 1 | 2288,2304 | fail | 155 | unreachable | No items were found on player Clj_g1 |
| 2 | 2288,2304 | pass | 314 | goal | Found 7 matching item(s) on player Clj_g2 |
| 3 | 2288,2304 | death | 202 | other-dimension | No player was found |

outcomes: death 2, fail 1, pass 1, timeout 1
reasons: goal 1, other-dimension 2, timeout 1, unreachable 1

### wood

pass 12/12 [76%, 100%]; not counted: harness 0, disconnect 0

outcomes: pass 12
reasons: goal 12
")

(deftest a-gym-summary-reads-back
  (let [s (site/gym-summary summary-md)
        [blaze wood] (:goals s)]
    (is (= {:label "blazefort-k" :set "A" :sha "0117de2"} (select-keys s [:label :set :sha])))
    (is (= {:goal "blaze-rods" :pass 1 :n 5 :lo 4 :hi 62 :pass-s [314]}
           (select-keys blaze [:goal :pass :n :lo :hi :pass-s])))
    (is (= {:death 2 :fail 1 :pass 1 :timeout 1} (:outcomes blaze)))
    (is (= {:other-dimension 2 :goal 1 :timeout 1 :unreachable 1} (:reasons blaze)))
    (is (= {:goal "wood" :pass 12 :n 12} (select-keys wood [:goal :pass :n])))))

(deftest a-gym-line-names-its-rows
  (let [run {:id 38913023560
             :created_at "2026-10-16T14:05:55Z"
             :head_branch "probe/262-gym"
             :head_sha "0117de2abc"
             :conclusion "success"}
        line (site/gym-line run
                            (site/gym-summary summary-md)
                            ["gym-blazefort-k-blaze-fortress-1" "gym-blazefort-k-blaze-fortress-2"
                             "gym-blazefort-k-summary"])]
    (is (= ["blaze-fortress"] (:rows line)))
    (is (= 38913023560 (:run line)))
    (testing "a run that left no summary still gets a line, so it is not asked for again"
      (is (= [] (:goals (site/gym-line run nil [])))))))

(deftest gym-lines-are-kept-once-by-run
  (let [kept "{\"run\":2,\"goals\":[]}\n{\"run\":5,\"goals\":[]}\n"
        out (site/gyms-change [{:run 5 :goals [{:goal "x"}]} {:run 3 :goals []}]
                              {site/gyms-file kept})
        lines (page/read-ndjson (out site/gyms-file))]
    (is (= [2 3 5] (map :run lines)))
    (is (= [] (:goals (last lines))) "a run already kept keeps its line")))

(deftest the-road-runs-from-the-race-to-the-dragon
  (let [goals (edn/read-string {:default (fn [_ v] v)} (slurp "resources/clojurecraft/goals.edn"))
        road-def (edn/read-string (slurp "ci/road.edn"))
        r (site/road goals "fortress" road-def)
        ids (map :id (:stages r))]
    (is (= "wood" (first ids)))
    (is (= "dragon" (last ids)))
    (is (< (.indexOf ids "fortress") (.indexOf ids "blaze-rods") (.indexOf ids "stronghold")))
    (is (every? :race (take-while #(not= "blaze-rods" (:id %)) (:stages r))))
    (is (not-any? :race (drop-while #(not= "blaze-rods" (:id %)) (:stages r))))
    (is (every? #(seq (:says %)) (:stages r)) "every stage says what done means")
    (is (every? #(seq (:phase %)) (:stages r)))
    (testing "a stage the race is raised to leaves the asides"
      (is (= 1
             (count (filter #(= "fortress" (:id %))
                            (:stages (site/road
                                      goals
                                      "fortress"
                                      (update road-def :road/after conj :fortress))))))))))

(deftest a-failure-title-says-its-signature
  (is (= ["fortress" "no-pickaxe"] (site/signature-of "Bot stops at fortress: no-pickaxe")))
  (is (nil? (site/signature-of "Roadmap"))))

(deftest the-agents-history-from-github
  (let [issues [{:number 10
                 :title "Bot stops at cast-one: no-site"
                 :state "CLOSED"
                 :stateReason "COMPLETED"
                 :createdAt "2026-10-14T00:00:00Z"
                 :closedAt "2026-10-15T00:00:00Z"
                 :labels [{:name "bot-failure"}]}
                {:number 11
                 :title "Bot stops at wood: no-log"
                 :state "OPEN"
                 :stateReason ""
                 :createdAt "2026-10-15T00:00:00Z"
                 :labels [{:name "bot-failure"} {:name "agent-stuck"}]}]
        prs [{:number 20
              :title "Fix"
              :headRefName "copilot/fix-cast"
              :author {:login "app/copilot-swe-agent"}
              :state "MERGED"
              :isDraft false
              :createdAt "2026-10-14T05:00:00Z"
              :mergedAt "2026-10-15T00:00:00Z"
              :closedAt "2026-10-15T00:00:00Z"
              :closingIssuesReferences [{:number 10}]}
             {:number 21
              :title "Tooling"
              :headRefName "feat/page"
              :author {:login "BridgerB"}
              :state "OPEN"
              :isDraft false
              :createdAt "2026-10-15T00:00:00Z"
              :closingIssuesReferences []}
             {:number 22
              :title "Fix wood"
              :headRefName "fix/11-wood"
              :author {:login "BridgerB"}
              :state "OPEN"
              :isDraft true
              :createdAt "2026-10-15T01:00:00Z"
              :closingIssuesReferences [{:number 11}]}]
        a (site/agents issues prs "2026-10-16T00:00:00Z")]
    (is (= [{:n 10 :stage "cast-one" :reason "no-site" :state "closed" :why "completed"}
            {:n 11 :stage "wood" :reason "no-log" :state "open"}]
           (map #(select-keys % [:n :stage :reason :state :why]) (:issues a))))
    (is (= [[20 "copilot" "merged" [10]] [22 "fixer" "open" [11]]]
           (map (juxt :n :by :state :fixes) (:prs a)))
        "a pull request that fixes no bot-failure issue and is not Copilot's is left out")))

(deftest the-road-json-cannot-end-its-script-early
  (is (not (str/includes? (site/json-in-html {:says "</script><b>"}) "</"))))

(def road-def (edn/read-string (slurp "ci/road.edn")))
(def gym-stage (:road/gym-stage road-def))

(def plan-md
  "A plan run's report, as plan.yml's aggregate writes it (and comments on the plan's issue)."
  "## plan dragon3-b (ci/plan/dragon3-b.edn) at ff8a0aa, run 39593702551

## crystals (head)

### crystals

pass 3/3 [44%, 100%]; not counted: harness 0, disconnect 0

| run | landing | outcome | s | reason | truth |
|---|---|---|---|---|---|
| 1 | 1,2 | pass | 700 | goal | ok |

outcomes: pass 3
reasons: goal 3

## dragon (head)

### dragon

pass 3/3 [44%, 100%]; not counted: harness 0, disconnect 0

outcomes: pass 3
reasons: goal 3

### sim forests

worlds 10 in 1 shards; failing shards 0
")

(deftest a-gym-row-maps-to-its-road-stage
  (is (= "dragon" (site/stage-of gym-stage "crystals" "crystals")))
  (is (= "dragon" (site/stage-of gym-stage "dragon" "dragon")))
  (is (= "end-portal" (site/stage-of gym-stage "end-portal" "end-portal")))
  (is (= "the-end" (site/stage-of gym-stage "enter-end" "enter-end")))
  (is (= "ender-pearls" (site/stage-of gym-stage "pearls-1" "ender-pearl")))
  (is (= "ender-pearls" (site/stage-of gym-stage "pearls-12" "ender-pearls")))
  (is (= "blaze-rods" (site/stage-of gym-stage "blaze-fortress" "blaze-rods")))
  (is (= "blaze-rods" (site/stage-of gym-stage "blaze-7" "blaze-rods")))
  (testing "an unlisted row falls back to the heading's goal, then to itself"
    (is (= "dragon" (site/stage-of gym-stage "odd-name" "crystals")))
    (is (= "fortress" (site/stage-of gym-stage "fortress" "fortress"))))
  (testing "every mapped stage is a stage on the road"
    (let [goals (edn/read-string {:default (fn [_ v] v)} (slurp "resources/clojurecraft/goals.edn"))
          ids (set (map :id (:stages (site/road goals "fortress" road-def))))]
      (is (every? ids (vals gym-stage))))))

(deftest a-stage-status-follows-its-evidence
  (is (= :proven (site/stage-status true [{:pass 0 :n 5} {:pass 1 :n 5}])) "any pass proves it")
  (is (= :proven (site/stage-status false [{:pass 3 :n 3}])))
  (is (= :no-pass (site/stage-status true [{:pass 0 :n 5}])))
  (is (= :built (site/stage-status true [])) "a row and no gym run is built, not tried")
  (is (= :unbuilt (site/stage-status false []))))

(deftest the-road-reads-built-from-the-goal-table
  (let [goals (edn/read-string {:default (fn [_ v] v)} (slurp "resources/clojurecraft/goals.edn"))
        stages (into {} (map (juxt :id identity)) (:stages (site/road goals "fortress" road-def)))]
    (is (every? :goal (map stages ["end-portal" "the-end" "dragon" "blaze-rods" "portal-room"]))
        "the stages past the fortress have goal rows (the-end by enter-end)")
    (testing "a stage with no row is not built"
      (let [r (site/road (remove #(= :enter-end (:goal/id %)) goals) "fortress" road-def)]
        (is (false? (:goal (some #(when (= "the-end" (:id %)) %) (:stages r)))))))))

(deftest a-plan-run-becomes-a-gym-line
  (let [summary (site/plan-summary gym-stage plan-md)
        line (site/plan-line {:id 39593702551
                              :created_at "2026-10-21T11:40:33Z"
                              :head_branch "feat/267-dragon3"
                              :head_sha "ff8a0aaa4d4d694c1623bba93f4d11babcc905d6"
                              :conclusion "success"}
                             summary)]
    (is (= {:label "dragon3-b" :plan "ci/plan/dragon3-b.edn" :sha "ff8a0aa" :run 39593702551}
           (select-keys summary [:label :plan :sha :run])))
    (is (= [["dragon" "crystals" 3 3] ["dragon" "dragon" 3 3]]
           (map (juxt :goal :row :pass :n) (:goals line))))
    (is (= ["crystals" "dragon"] (:rows line)))
    (is (= {:run 39593702551 :at "2026-10-21T11:40:33Z" :via "plan" :conclusion "success"}
           (select-keys line [:run :at :via :conclusion])))
    (testing "a report with no gym section still gives a line, so it is not read again"
      (is (= []
             (:goals (site/plan-line {:id 1}
                                     (site/plan-summary gym-stage
                                                        "## plan x (p) at s, run 1\n"))))))))
