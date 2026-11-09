(ns clojurecraft.page-test
  "The results' writers (bb page): a round becomes a batch, batches are laid out by the day their
   run started, and a publish recomputed on whatever the branch holds keeps every batch once,
   whichever of two writers pushes first. Babashka runs these (bb test:bb)."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.failures :as failures]
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

(def refused
  "A real bot whose server refused it (race 39817745908's server 2): its RESULT, the plan never
   begun, as bb failures reads it from race.log."
  {:ok false
   :reason :crashed
   :error "Connection refused (connect failed)"
   :until "nether"
   :plan {}
   :run/signature [:wood :crashed]
   :run/infra? false
   :run/bot "Clj_r05"})

(deftest a-run-that-never-got-into-play-is-no-run
  (let [played {:ok false
                :reason :no-site
                :until "nether"
                :plan {:plan/status :failed :plan/reached #{:wood}}
                :run/signature [:kit :no-site]
                :run/bot "Clj_r01"}
        r (page/round (concat [played] (repeat 375 refused))
                      []
                      [:wood :kit]
                      {:kind :real :run 1 :commit "c" :at "2026-10-22T15:34:51Z"})]
    (testing "bb failures reports it under infrastructure stops, even from a RESULT marked before"
      (is (failures/infra? refused))
      (is (empty? (failures/failed [refused])))
      (is (not (failures/infra? played)))
      (is (not (failures/unplayed? (assoc refused :run/file "R1/3.edn.gz")))
          "a sim run is the model's: never infrastructure for want of a plan"))
    (testing "the batch counts only the runs that played"
      (is (= 1 (:round/runs r)))
      (is (= 375 (:round/unplayed r)))
      (is (= {:wood 1 :kit 0} (:round/reached r)))
      (is (= [[[:kit :no-site] 1]] (:round/failures r)))
      (is (= 375 (:unplayed (page/batch r)))))))

(deftest a-stored-batch-drops-the-runs-that-never-played
  (let [stored (page/read-json (page/json-line
                                {:run 39817745908
                                 :kind "real"
                                 :runner 1
                                 :batch 1
                                 :runs 380
                                 :ok 0
                                 :reached {:wood 1 :kit 1}
                                 :failures [["wood" "crashed" 375] ["portal-kit" "unreachable" 1]]
                                 :issues {(page/sig-key [:wood :crashed]) 152
                                          (page/sig-key [:portal-kit :unreachable]) 515}}))
        b (page/played stored)]
    (is (= 5 (:runs b)))
    (is (= 375 (:unplayed b)))
    (is (= [["portal-kit" "unreachable" 1]] (:failures b)))
    (is (= {(page/sig-key [:portal-kit :unreachable]) 515} (:issues b)))
    (is (= {:wood 1 :kit 1} (:reached b)) "they reached nothing: the reach stays")
    (is (= b (page/played b)) "a batch counted so is unchanged")
    (is (= (assoc stored :unplayed 0) (page/played (assoc stored :unplayed 0)))
        "a batch bb churn already counted (it carries :unplayed) is unchanged")
    (is (= (assoc stored :kind "sim") (page/played (assoc stored :kind "sim")))
        "a sim run's crash at wood is the model's: kept")
    (testing "the day and the summary count it so"
      (let [files (page/rebuilt {"2026-10-22" (page/ndjson [stored])}
                                {:days [{:date "2026-10-21" :runs 7}] :updated "u"})
            d (page/read-json (get files (page/day-path "2026-10-22")))
            s (page/read-json (get files page/summary-file))]
        (is (= 5 (get-in d [:totals :real :runs])))
        (is (= 375 (get-in d [:totals :real :unplayed])))
        (is (= {:portal-kit 1} (get-in d [:totals :real :stops])))
        (is (= ["2026-10-21" "2026-10-22"] (map :date (:days s))) "a day with no batches is kept")
        (is (= 5 (get-in s [:days 1 :real :runs])))
        (is (= "u" (:updated s)))))))

(deftest a-runs-observability-adds-up-its-batches
  (let [a (assoc (page/batch (round {}))
                 :observed
                 {:senses [3 3] :events [2 3] :why [4 10] :dropped 0 :unheard {:sound 5}})
        b (assoc
           (page/batch (round {:round/batch 2}))
           :observed
           {:senses [1 1] :events [1 1] :why [6 6] :dropped 2 :unheard {:sound 1 :system-chat 2}})
        r (page/combine [a b])]
    (is (= {:senses [4 4] :events [3 4] :why [10 16] :dropped 2 :unheard {:sound 6 :system-chat 2}}
           (:observed r)))
    (is (nil? (:observed (page/combine [(page/batch (round {}))])))
        "a run whose batches kept no row has none")))

(deftest a-batch-row-names-each-servers-distance-and-tick
  (let [sample (fn [server distance playing avg p95]
                 {:sample/server server
                  :sample/simulation-distance distance
                  :sample/playing playing
                  :sample/server-tick {:avg avg :p50 avg :p95 p95 :p99 p95}})
        samples [(sample 1 4 10 200.0 300.0) (sample 1 4 10 220.0 340.0) (sample 1 4 0 40.0 50.0)
                 (sample 2 3 10 150.0 200.0)]
        servers (page/servers-of samples)]
    (is (= [{:server 1
             :simulation-distance 4
             :samples 2
             :tick {:avg 210.0 :p50 210.0 :p95 320.0 :p99 320.0}}
            {:server 2
             :simulation-distance 3
             :samples 1
             :tick {:avg 150.0 :p50 150.0 :p95 200.0 :p99 200.0}}]
           servers)
        "the mean of the samples with bots playing, by server")
    (is (nil? (page/servers-of [])) "no samples, no entry")
    (is (= servers (:servers (page/batch (round {:round/servers servers})))) "it reaches the row")
    (is (not (contains? (page/batch (round {})) :servers)) "and is absent without samples")
    (is (= {:samples 0} (first (page/servers-of [{:sample/playing 0}]))) "a one-server runner")))

(deftest a-batch-row-names-the-servers-its-runner-carried
  (let [sample (fn [server runner-servers]
                 {:sample/server server
                  :sample/runner-servers runner-servers
                  :sample/simulation-distance 3
                  :sample/playing 10
                  :sample/server-tick {:avg 100.0 :p50 90.0 :p95 200.0 :p99 250.0}})
        one (page/servers-of [(sample nil 1) (sample nil 1)])
        two (page/servers-of [(sample 1 2) (sample 2 2)])
        row (fn [servers] (page/batch (round {:round/servers servers})))]
    (is (= [{:samples 2
             :simulation-distance 3
             :runner-servers 1
             :tick {:avg 100.0 :p50 90.0 :p95 200.0 :p99 250.0}}]
           one)
        "a one-server runner: one entry with no server number")
    (is (= [2 2] (map :runner-servers two)))
    (is (= 1 (:runner-servers (row one))) "the one-server arm")
    (is (= 2 (:runner-servers (row two))) "the two-server arm")
    (is (not (contains? (row (page/servers-of [{:sample/playing 1}])) :runner-servers))
        "samples from before it was recorded name no arm")
    (is (= 1 (:runner-servers (page/combine [(row one) (row one)]))) "a run's batches agree")
    (is (not (contains? (page/combine [(row one) (row two)]) :runner-servers))
        "a day's batches of both arms name neither")))

(deftest a-batch-row-names-the-bots-its-runner-carried
  (let [sample (fn [bots]
                 {:sample/runner-servers 1
                  :sample/runner-bots bots
                  :sample/playing bots
                  :sample/server-tick {:avg 40.0 :p50 40.0 :p95 60.0 :p99 80.0}})
        row (fn [bots] (page/batch (round {:round/servers (page/servers-of [(sample bots)])})))]
    (is (= 14 (:runner-bots (first (page/servers-of [(sample 14)])))) "the entry names it")
    (is (= [10 14] (map (comp :runner-bots row) [10 14])) "each arm's row")
    (is (= 14 (:runner-bots (page/combine [(row 14) (row 14)]))) "a run's batches agree")
    (is (not (contains? (page/combine [(row 10) (row 14)]) :runner-bots)) "both arms: neither")
    (is (not (contains? (page/batch (round {:round/servers (page/servers-of [{:sample/playing
                                                                              1}])}))
                        :runner-bots))
        "samples from before it was recorded name no arm")))
