(ns clojurecraft.page-test
  "The results' writers (bb page): a round becomes a batch, batches are laid out by the day their
   run started, and a publish recomputed on whatever the branch holds keeps every batch once,
   whichever of two writers pushes first. Babashka runs these (bb test:bb)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.page :as page]))

(def legacy-line
  "One line of the old rounds.edn, as the lanes wrote it."
  "#:round{:ok 0, :batch 1, :runner 5, :kind :real, :commit \"c58e3fd9bdc3fe5ed3802b68714253784b7f2512\", :at \"2026-10-16T23:14:52Z\", :reached {:wood 1, :kit 1, :pickaxe 1, :furnace 0}, :started \"2026-10-16T23:21:08Z\", :failures [[[:furnace :boxed] 1]], :issues {\"[:furnace :boxed]\" 41}, :first-at {:wood \"2026-10-16T23:22:36.659Z\"}, :runs 1, :run 38980959410, :speed {:wood {:n 30, :fastest-s 5, :median-s 11.0}}, :ended \"2026-10-16T23:26:54.292880Z\", :leader {:bot \"Clj_r04 (runner 5/2)\", :splits {:wood 7}}}")

(defn round
  "A round as bb churn makes one, with what a test varies."
  [m]
  (merge {:round/run 1
          :round/kind :sim
          :round/commit "abcdef0123"
          :round/at "2026-10-16T10:00:00Z"
          :round/started "2026-10-16T10:05:00Z"
          :round/ended "2026-10-16T10:25:00Z"
          :round/runner 1
          :round/batch 1
          :round/runs 4
          :round/ok 0
          :round/deaths 2
          :round/died 1
          :round/reached {:wood 4 :kit 3 :pickaxe 1}
          :round/speed {:wood {:n 4 :fastest-s 5 :median-s 10}
                        :kit {:n 3 :fastest-s 12 :median-s 20}}
          :round/failures [[[:pickaxe :no-path] 2] [[:kit :boxed] 1]]}
         m))

(deftest a-legacy-round-becomes-a-batch
  (let [b (page/batch (first (page/read-rounds legacy-line)))]
    (is (= "real" (:kind b)))
    (is (= [["furnace" "boxed" 1]] (:failures b)))
    (is (= 41 (get-in b [:issues (page/sig-key [:furnace :boxed])])))
    (is (= {:n 30 :fastest 5 :median 11.0} (get-in b [:speed :wood])))
    (is (= "2026-10-16T23:22:36.659Z" (get-in b [:first :wood])))
    (is (nil? (:deaths b)) "an older round counted no deaths, and its batch does not pretend to")
    (is (= "2026-10-16" (page/day-of b)))
    (testing "JSON keeps it: written and read back, the same batch"
      (is (= b (page/read-json (page/json-line b)))))))

(deftest a-batch-belongs-to-the-day-its-run-started
  (is (= "2026-10-15"
         (page/day-of {:started "2026-10-15T23:59:00Z" :ended "2026-10-16T05:00:00Z"})))
  (is (= "2026-10-16" (page/day-of {:at "2026-10-16T01:00:00Z"})))
  (is (= "1970-01-01" (page/day-of {}))))

(deftest a-batch-published-twice-is-kept-once-in-its-last-place
  (let [a {:run 1 :kind "sim" :runner 1 :batch 1 :runs 1}
        b {:run 1 :kind "sim" :runner 1 :batch 2 :runs 2}
        a2 (assoc a :runs 9)]
    (is (= [b a2] (page/with-batches [a b] [a2])))
    (is (= [a b] (page/with-batches [] [a b])))))

(deftest batches-combine-into-a-row
  (let [bs [(page/batch (round {}))
            (page/batch (round {:round/batch 2
                                :round/runner 3
                                :round/runs 6
                                :round/deaths 0
                                :round/died 0
                                :round/started "2026-10-16T10:05:00Z"
                                :round/ended "2026-10-16T10:45:00Z"
                                :round/speed {:wood {:n 6 :fastest-s 3 :median-s 20}}
                                :round/failures [[[:kit :boxed] 4]]}))]
        r (page/combine bs)]
    (is (= 10 (:runs r)))
    (is (= [1 3] (:runners r)))
    (is (= 2 (:batches r)))
    (is (= {:wood 8 :kit 6 :pickaxe 2} (:reached r)))
    (is (= [["kit" "boxed" 5] ["pickaxe" "no-path" 2]] (:failures r)))
    (is (= 3 (get-in r [:speed :wood :fastest])))
    (is (= 16.0 (get-in r [:speed :wood :median]))
        "the medians weighted by their counts: (4x10 + 6x20) / 10")
    (is (= "2026-10-16T10:45:00Z" (:ended r)))
    (is (= {:deaths 2 :died 1 :death-runs 10} (select-keys r [:deaths :died :death-runs]))))
  (testing "deaths count only over the batches that counted them"
    (let [old (dissoc (page/batch (round {})) :deaths :died)
          r (page/combine [old (page/batch (round {:round/batch 2}))])]
      (is (= {:deaths 2 :died 1 :death-runs 4} (select-keys r [:deaths :died :death-runs]))))))

(deftest a-day-has-its-runs-and-its-totals-per-kind
  (let [bs [(page/batch (round {})) (page/batch (round {:round/batch 2}))
            (page/batch (round {:round/run 2
                                :round/kind :real
                                :round/runs 1
                                :round/failures [[[:wood :no-log] 1]]
                                :round/issues {"[:wood :no-log]" 7}}))]
        d (page/day "2026-10-16" bs)]
    (is (= 2 (count (:runs d))) "one row a run and kind")
    (is (= #{:sim :real} (set (keys (:totals d)))))
    (is (= {:pickaxe 4 :kit 2} (get-in d [:totals :sim :stops])))
    (is (= 1 (get-in d [:totals :sim :rounds])))
    (is (= {(page/sig-key [:wood :no-log]) 7} (get-in d [:totals :real :issues])))))

(defn branch
  "A branch as a map {path text} after applying files ({path text-or-nil})."
  [files changes]
  (reduce (fn [m [path text]] (if (some? text) (assoc m path text) (dissoc m path))) files changes))

(defn publish
  "The branch after publishing rounds on it, as a lane's publish computes it."
  [files rounds]
  (branch files (page/publish-change "2026-10-17T00:00:00Z" (mapv page/batch rounds) files)))

(deftest the-legacy-file-is-absorbed-whole-then-removed
  (let [files {"rounds.edn" (str legacy-line "\n") "index.html" "<html>" "orchestrator.json" "{}"}
        after (publish files [(round {})])]
    (is (not (contains? after "rounds.edn")))
    (is (not (contains? after "index.html")))
    (is (= "{}" (after "orchestrator.json")) "the orchestrator's file is never touched")
    (is (= 2 (count (page/read-ndjson (after "data/batches/2026-10-16.ndjson")))))
    (let [s (page/read-json (after page/summary-file))]
      (is (= ["2026-10-16"] (map :date (:days s))))
      (is (= 1 (get-in s [:days 0 :real :runs])))
      (is (= 4 (get-in s [:days 0 :sim :runs]))))))

(deftest two-writers-in-either-order-keep-both-batches
  (let [a (round {:round/runner 1})
        b (round {:round/runner 2 :round/runs 3 :round/reached {:wood 3}})
        c (round {:round/run 9 :round/started "2026-10-15T22:00:00Z"})
        base (publish {} [c])
        ab (publish (publish base [a]) [b])
        ba (publish (publish base [b]) [a])
        day #(set (page/read-ndjson (% "data/batches/2026-10-16.ndjson")))]
    (is (= (day ab) (day ba)))
    (is (= 2 (count (day ab))))
    (is (= (page/read-json (ab "data/days/2026-10-16.json"))
           (page/read-json (ba "data/days/2026-10-16.json")))
        "a day file depends on its batches, not on the order they came")
    (is (= (page/read-json (ab page/summary-file)) (page/read-json (ba page/summary-file))))
    (is (= ["2026-10-15" "2026-10-16"] (map :date (:days (page/read-json (ab page/summary-file))))))
    (testing "a retry after a lost push publishes the same batch again: kept once"
      (is (= ab (publish ab [b]))))))

(deftest the-summary-is-small-json
  (let [after (publish {} [(round {})])
        s (json/parse-string (after page/summary-file) true)]
    (is (= "2026-10-17T00:00:00Z" (:updated s)))
    (is (every? #(contains? % :date) (:days s)))
    (is (nil? (get-in s [:days 0 :sim :leader])) "no leader in the totals: the day file has it")))

(deftest the-chain-ends-at-the-race-target
  (let [goals [{:goal/id :escape-water
                :goal/priority 0
                :goal/target? true
                :goal/always? true
                :goal/until "x"}
               {:goal/id :wood :goal/priority 1 :goal/target? true :goal/until "wood"}
               {:goal/id :kit :goal/priority 2 :goal/target? true :goal/until "table"}
               {:goal/id :blaze-rods
                :goal/priority 18
                :goal/target? true
                :goal/aside? true
                :goal/until "blaze-rods"}
               {:goal/id :pickaxe :goal/priority 3 :goal/target? true :goal/until "pickaxe"}]]
    (is (= [:wood :kit] (page/chain goals "table")))
    (is (= [:wood :kit :pickaxe] (page/chain goals "pickaxe")))))
