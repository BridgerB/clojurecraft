(ns clojurecraft.gym-test
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.gym :as gym]
            [clojurecraft.landings :as landings]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.spec]))

(deftest the-registry-is-data-and-agrees-with-the-goal-table
  (is (every? #(s/valid? :clojurecraft.gym/row %) gym/gyms))
  (is (= gym/gyms (edn/read-string (pr-str gym/gyms))) "printable and readable back")
  (testing "every row's --until puts exactly its goal in play"
    (is (every? #(= [(:gym/goal %)] (plan/goals-for (:gym/until %)))
                (remove :gym/fixture? gym/gyms))))
  (testing "a fixture row's puts none: it judges the landing alone (issue #261)"
    (is (seq (filter :gym/fixture? gym/gyms)))
    (is (every? #(nil? (plan/goals-for (:gym/until %))) (filter :gym/fixture? gym/gyms))))
  (is (= :kit (:gym/goal (gym/gym "table"))) "a row is found by its --until name")
  (is (= :kit (:gym/goal (gym/gym :kit))) "or by its goal")
  (testing "several rows of one goal are found by their own names (issue #262)"
    (is (= "blaze-7" (:gym/name (gym/gym "blaze-7"))))
    (is (= :blaze-rods (:gym/goal (gym/gym "blaze-fortress"))))
    (is (str/includes? (gym/plan-matrix ["blaze-7"] 1) "{\"goal\":\"blaze-7\",\"run\":1,"))
    (is (apply distinct? (keep :gym/name gym/gyms)))))

(def ok-result {:ok true :reason :goal :stats {}})

(def found
  {:truth/cmd "clear Clj_g1 #minecraft:logs 0"
   :truth/reply "Found 1 matching item(s)"
   :truth/ok? true})
(def none
  {:truth/cmd "clear Clj_g1 #minecraft:logs 0" :truth/reply "No items were found" :truth/ok? false})

(defn left
  [& {:as m}]
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
  (is (= :disconnect
         (gym/outcome (left :result
                            {:ok false :reason :disconnected :disconnected "kicked" :stats {}}))))
  (is (= :disconnect (gym/outcome (left :result nil))) "the bot never printed RESULT")
  (is (= :harness (gym/outcome (left :landed? false))) "the landing failed before :go"))

(deftest an-arena-is-written-round-the-landing
  ;; a portal row builds its arena relative to the landing [x z y] (issue #8)
  (is (= "fill 92 63 192 116 63 216 minecraft:stone"
         (gym/fill-in "fill {x-8} {y-1} {z-8} {x+16} {y-1} {z+16} minecraft:stone"
                      "Clj_g1"
                      [100 200 64])))
  (is (= "data get entity Clj_g1 Dimension"
         (gym/fill-in "data get entity {name} Dimension" "Clj_g1" [0 0 0]))))

(deftest the-summons-are-commands-round-the-landing
  ;; issue #261: what the fight rows meet, written as fill-in templates and run in the row's
  ;; dimension
  (is (= "summon minecraft:zombie {x+2} {y} {z-2} {NoAI:1b,PersistenceRequired:1b}"
         (gym/no-ai-zombie [2 0 -2])))
  (is (= "summon minecraft:zombie 102 72 198 {NoAI:1b,PersistenceRequired:1b}"
         (gym/fill-in (gym/no-ai-zombie [2 0 -2]) "Clj_g1" [100 200 72])))
  (is (= "summon minecraft:pig {x} {y+1} {z}" (gym/summon "pig" [0 1 0])))
  (is (str/starts-with?
       (gym/fill-in (gym/blaze-spawner [3 0 3]) "Clj_g1" [0 0 72])
       "setblock 3 72 3 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}}"))
  (is (str/includes? (gym/fill-in (gym/slow-blaze-spawner [3 0 3]) "Clj_g1" [0 0 72])
                     "setblock 3 72 3 minecraft:spawner{")
      "issue #262: the slower one, one blaze at a time")
  (is (= "effect give Clj_g1 minecraft:fire_resistance 1000000 0 true"
         (gym/fill-in gym/fire-resistance "Clj_g1" [0 0 72])))
  (is (= 3 (count (gym/endermen 3 [0 0 2]))))
  (is (every? #(str/starts-with? % "summon minecraft:enderman {x} {y} {z+2}")
              (gym/endermen 3 [0 0 2])))
  (is (= ["kill @e[type=minecraft:end_crystal]"
          "summon minecraft:end_crystal {x+3} {y} {z+3} {ShowBottom:0b}"]
         (gym/crystals [[3 0 3]])))
  (is (str/includes? (peek gym/dragon) "{DragonPhase:0,Health:200f}")
      "a summoned dragon circles with full health, not hovering in phase 10")
  (is (= "time set midnight" gym/night))
  (is
   (=
    ["execute in minecraft:the_nether run summon minecraft:zombie 12 72 22 {NoAI:1b,PersistenceRequired:1b}"]
    (gym/summons (gym/gym :arena-nether) "Clj_g1" [10 20 72])))
  (is (= ["give Clj_g1 minecraft:iron_sword 1" "give Clj_g1 minecraft:cooked_beef 8"]
         (gym/kit (gym/gym :arena-nether) "Clj_g1"))))

(deftest each-landing-names-its-dimension
  (is (= "minecraft:the_nether" (gym/dimension-of (gym/gym :fortress))))
  (is (= "minecraft:the_nether" (gym/dimension-of (gym/gym :arena-nether))))
  (is (= "minecraft:the_end" (gym/dimension-of (gym/gym :arena-end))))
  (is (= "minecraft:overworld" (gym/dimension-of (gym/gym :wood))))
  (is (= "execute as Clj_g1 at @s if dimension minecraft:the_end"
         (gym/dimension-test "Clj_g1" "minecraft:the_end"))))

(defn landing-commands
  "The RCON commands landing! sends for goal, against a server that answers every test with
   reply-for and every position with one in the arena; [result commands]. It redefines the RCON
   client for the whole JVM, so every test that calls it is :eftest/synchronized: two at once
   (the warped forest landing beside the kit test, issue #263) swapped each other's answers."
  [goal reply-for]
  (let [sent (atom [])
        landings (java.io.File/createTempFile "landings" ".edn")]
    (spit landings (pr-str [[800 1600 70]]))
    (with-redefs [rcon/with-rcon (fn [_ _ _ f] (f {}))
                  rcon/command! (fn [_ c]
                                  (swap! sent conj c)
                                  (if (str/includes? c " Pos")
                                    "Clj_g1 has the following entity data: [100.5d, 72.0d, 200.5d]"
                                    (reply-for c)))
                  gym/dimension-wait-ms 300]
      (let [r (gym/landing! {:goal goal
                             :run 1
                             :name "Clj_g1"
                             :rcon-port 1
                             :rcon-pass "p"
                             :landings (str landings)})]
        (.delete landings)
        [r @sent]))))

(deftest ^:eftest/synchronized the-kit-is-given-only-in-the-rows-dimension
  ;; steve gave its End kit before the cross-dimension teleport and the server held none of it
  ;; (issue #10): the gym lands, asks the server which dimension the bot is in, then gives
  (let [[r sent] (landing-commands :arena-nether (constantly "Test passed"))
        at (fn [pred] (first (keep-indexed (fn [i c] (when (pred c) i)) sent)))
        tp (at #(str/includes? % "run tp Clj_g1"))
        asked (at #{"execute as Clj_g1 at @s if dimension minecraft:the_nether"})
        give (at #(str/starts-with? % "give "))
        summoned (at #(str/includes? % "summon minecraft:zombie"))]
    (is (:gym/landed? r))
    (is (= [100 200 72] (:gym/landing r)) "the arena over the landing's Nether coordinates")
    (is (some #{"difficulty normal"} sent))
    (is (< tp asked give summoned)
        "teleported, then the dimension confirmed, then the kit, then the mobs")
    (is (some #(str/includes? % "fill 95 70 195 105 77 205 minecraft:netherrack") sent)
        "the room's shell"))
  (let [[r sent] (landing-commands
                  :arena-nether
                  #(if (str/includes? % "if dimension") "Test failed" "Test passed"))]
    (is (not (:gym/landed? r)) "a bot that never reaches the dimension is a failed landing")
    (is (not-any? #(str/starts-with? % "give ") sent) "and never gets its kit in the wrong world")))

(deftest the-pearl-rows-are-found-by-their-own-names
  ;; issue #263: pearls-1 and pearls-1-live share the goal :ender-pearl and the --until pearls-1
  (let [still (gym/gym "pearls-1")
        live (gym/gym "pearls-1-live")]
    (is (= [:ender-pearl :ender-pearl :ender-pearls]
           (mapv :gym/goal [still live (gym/gym "pearls-12")])))
    (is (not= still live))
    (is (every? #(str/includes? % "NoAI:1b") (:gym/summon still)) "first, endermen that never move")
    (is (not-any? #(str/includes? % "NoAI") (:gym/summon live)) "then live ones")
    (is (= ["pearls-1" "pearls-1-live"] (mapv gym/trial-name [still live])))
    (is (str/includes? (gym/plan-matrix ["pearls-1-live"] 1)
                       "{\"goal\":\"pearls-1-live\",\"run\":1")
        "the live trial crosses the job boundary under its own name")
    (is (str/includes? (gym/plan-matrix ["table"] 1) "{\"goal\":\"table\",\"run\":1")
        "every other row as before")
    (is (:truth/ok? (gym/truth (first (:gym/truth (gym/gym "pearls-12")))
                               "c"
                               "Found 13 matching item(s) on player")))
    (is (not (:truth/ok? (gym/truth (first (:gym/truth (gym/gym "pearls-12")))
                                    "c"
                                    "Found 1 matching item(s) on player"))))))

(deftest the-eyes-row-gives-rods-and-pearls-and-counts-the-eyes
  ;; issue #264: seven rods and twelve pearls given, twelve eyes or more held
  (let [row (gym/gym "eyes")
        truth (first (:gym/truth row))]
    (is (= :eyes-of-ender (:gym/goal row)))
    (is (= ["give c minecraft:blaze_rod 7" "give c minecraft:ender_pearl 12"] (gym/kit row "c")))
    (is (:truth/ok? (gym/truth truth "c" "Found 12 matching item(s) on player c")))
    (is (not (:truth/ok? (gym/truth truth "c" "Found 11 matching item(s) on player c"))))
    (is (not (:truth/ok? (gym/truth truth "c" "No items were found on player c"))))))

(deftest ^:eftest/synchronized a-warped-forest-landing-is-spread-onto-its-floor
  ;; issue #263: the forest nearest the landing's Nether coordinates; its located height can be
  ;; inside the rock (probe 38896760962), so the bot is spread onto a floor round it and kept
  ;; where the server says that floor is warped forest
  (let [[r sent] (landing-commands
                  :ender-pearls
                  #(cond
                     (str/includes? % "locate biome")
                     "The nearest minecraft:warped_forest is at [120, 70, 240] (90 blocks away)"
                     (str/includes? % "spreadplayers")
                     "Spread 1 player around 120, 240 with an average distance of 0 blocks apart"
                     :else "Test passed"))]
    (is (:gym/landed? r))
    (is (= [100 200 72] (:gym/landing r)) "where the spread stood it (the server's Pos)")
    (is
     (some
      #{"execute in minecraft:the_nether positioned 100 64 200 run locate biome minecraft:warped_forest"}
      sent)
     "an eighth of the overworld landing [800 1600]")
    (is (some
         #{"execute in minecraft:the_nether run spreadplayers 120 240 0 16 under 110 false Clj_g1"}
         sent))
    (is (some #{"execute in minecraft:the_nether if biome 100 72 200 minecraft:warped_forest"}
              sent))
    (is (some #(str/includes? % "run tp Clj_g1 100.5 72 200.5") sent))
    (is (some #{"difficulty normal"} sent))
    (is (some #{"give Clj_g1 minecraft:iron_sword 1"} sent))))

(deftest a-truth-reads-the-servers-reply
  (let [row {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]
    (is (= "clear Clj_g1 minecraft:stick 0" (gym/truth-cmd row "Clj_g1" [0 0 64])))
    (is (:truth/ok? (gym/truth row
                               "clear Clj_g1 minecraft:stick 0"
                               "Found 4 matching item(s) on player Clj_g1")))
    (is (:truth/ok? (gym/truth row
                               "clear Clj_g1 minecraft:stick 0"
                               "Found 12 matching item(s) on player Clj_g1")))
    (is (not (:truth/ok? (gym/truth row
                                    "clear Clj_g1 minecraft:stick 0"
                                    "Found 3 matching item(s) on player Clj_g1"))))
    (is (not (:truth/ok? (gym/truth row
                                    "clear Clj_g1 minecraft:stick 0"
                                    "No items were found on player Clj_g1"))))))

(deftest the-result-line-is-read-from-the-log
  (is (= {:ok true :reason :goal} (gym/result-line "RESULT {:ok true, :reason :goal}")))
  (is (nil? (gym/result-line "12:00 connected"))))

(deftest the-result-comes-back-through-the-pipe-whole
  ;; live iron-kit run 19's judge polled the log and read its 44 KB RESULT half written: a
  ;; process that writes its RESULT in two pieces is read as one value, and the log keeps every line
  (let
    [log (java.io.File/createTempFile "bot" ".log")
     p
     (.start
      (doto
        (ProcessBuilder.
         ^java.util.List
         ["sh" "-c"
          "echo connected; printf 'RESULT {:ok true, :plan '; sleep 0.3; printf '#:plan{:status :done}}\\n'"])
        (.redirectErrorStream true)))
     result (promise)
     tee (gym/tee-output! p log result)]
    (is (= {:ok true :plan {:plan/status :done}} (deref result 5000 :none)))
    (.join ^Thread tee 5000)
    (is (= ["connected" "RESULT {:ok true, :plan #:plan{:status :done}}"]
           (clojure.string/split-lines (slurp log))))
    (.delete log)))

(deftest wilson-intervals
  (let [[lo hi] (gym/wilson 12 12)]
    (is (< 0.75 lo 0.76))
    (is (= 1.0 hi)))
  (let [[lo hi] (gym/wilson 6 12)]
    (is (< 0.25 lo 0.26))
    (is (< 0.74 hi 0.75)))
  (is (= [0.0 1.0] (gym/wilson 0 0))))

(deftest the-report-folds-results
  (let [rows [(gym/judged (merge (left)
                                 {:gym/goal :wood :gym/run 1 :gym/landing [1 2 70] :gym/ms 20000}))
              (gym/judged (merge (left :truths [none])
                                 {:gym/goal :wood :gym/run 2 :gym/landing [3 4 70] :gym/ms 30000}))
              (gym/judged (merge (left :landed? false)
                                 {:gym/goal :wood :gym/run 3 :gym/landing [5 6 70]}))]
        md (gym/report rows)]
    (is (every? #(s/valid? :clojurecraft.gym/result %) rows))
    (is (str/includes? md "pass 1/2 [9%, 91%]; not counted: harness 1, disconnect 0"))
    (is (str/includes? md "| 2 | 3,4 | fail | 30 | goal | No items were found |")
        "the disagreement shows its truth text")
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
  (is (= [2000 3000]
         (fixture/parse-located
          "The nearest #minecraft:is_forest is at [2000, ~, 3000] (12 blocks away)")))
  (is (= 169 (landings/chunk-count (landings/square [100 100] 96))) "13x13 chunks at radius 96")
  (is (<= (landings/chunk-count (landings/square [-8 -8] 96)) landings/max-forceload-chunks))
  (testing "a landing is fit when high enough and no check passed"
    (is (landings/fit? [0 70 0] [[:water "Test failed"] [:tree "Test failed"]]))
    (is (not (landings/fit? [0 50 0] [[:water "Test failed"]])) "too low")
    (is (not (landings/fit? [0 70 0] [[:water "Test passed"]])) "water")))

(deftest a-truth-can-hold-the-server-to-what-the-bot-saw
  ;; issue #258: the entities trial passes only when the server reports the pig's health the bot
  ;; read from its metadata; a RESULT with no such value never passes
  (let [row (:truth/re (first (:gym/truth (gym/gym :entities))))
        truth (first (:gym/truth (gym/gym :entities)))
        reply "Pig has the following entity data: 7.5f"]
    (is (= "entity data: {result}f" row))
    (is (:truth/ok? (gym/truth truth "cmd" reply {:ok true :mobs {:pig 7.5}})))
    (is (not (:truth/ok? (gym/truth truth "cmd" reply {:ok true :mobs {:pig 10.0}}))))
    (is (not (:truth/ok? (gym/truth truth "cmd" reply {:ok true}))) "the bot saw no pig")
    (is (not (:truth/ok? (gym/truth truth
                                    "cmd"
                                    "Pig has the following entity data: 715f"
                                    {:ok true :mobs {:pig 7.5}})))
        "the value is quoted, its dot no wildcard")))
