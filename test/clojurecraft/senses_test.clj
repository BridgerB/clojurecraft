(ns clojurecraft.senses-test
  "Senses: a line a second of what the bot senses and decides, and the classes a run's last lines
   show. Pure over world values, as the observer reads them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.senses :as senses]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def blaze-type (some (fn [[id k]] (when (= :blaze k) id)) blocks/entity-names))

(defn- nether
  "A bot in play on one Nether column, holding seven rods, a blaze 6 blocks off."
  []
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :time/now 1793886242032
             :player/pos [8.5 64.0 8.5]
             :player/health 17.3
             :player/food 20
             :player/loaded? true
             :player/on-ground? true
             :player/on-fire? true
             :player/dimension dimension/nether
             :world/chunks {[0 0] (world/column {})}
             :player/inventory {36 {:item (recipe/item-id :blaze_rod) :count 7}}
             :world/entities
             {42615 {:entity/type blaze-type :entity/pos [14.5 65.0 8.5] :entity/health 20.0}}
             :plan/intent {:intent/kind :pocket :intent/goal :ender-pearls})))

(deftest a-line-says-what-the-bot-senses-and-decides
  (let [l (senses/of (nether) 3015)]
    (is (= 1793886242032 (:t l)))
    (is (= 17.3 (:hp l)))
    (is (= 7 (:rods l)))
    (is (:fire? l))
    (is (= 3015 (:tick-ms l)) "the loop's slowest event, from the observer")
    (is (= {:kind :pocket :goal :ender-pearls} (:intent l)))
    (is (= [{:kind :blaze :d 6.1 :hp 20.0 :sees? true}] (:hostiles l)))
    (testing "no position, no line: the bot is not in play yet"
      (is (nil? (senses/of (dissoc (nether) :player/pos) 0))))))

(deftest a-run-ends-in-the-classes-its-last-lines-show
  ;; 42024297742/s2/Clj_r57 on 0ace52e: rod 7, a pocket, the loop frozen 3015 ms, burning, then dead
  (let [blaze {:kind :blaze :d 6.0 :sees? true}
        lines [{:t 1000 :tick-ms 40 :hostiles [blaze] :watchers 1 :intent {:kind :pocket}}
               {:t 4015
                :tick-ms 3015
                :fire? true
                :hostiles (vec (repeat 6 blaze))
                :watchers 6
                :intent {:kind :pocket}}]]
    (is (= #{:freeze :burning :swarm :open :building :watched} (senses/classes lines))))
  (is (= #{:idle :open} (senses/classes [{:t 1 :tick-ms 5 :waiting :nothing-to-do}])))
  (is (= #{} (senses/classes [{:t 1 :tick-ms 5 :pocket {:in? true :sealed? true}}]))
      "sealed and quiet: nothing to name"))

(deftest lines-keep-the-last-five-minutes
  (let [ls (reduce senses/kept [] (map (fn [t] {:t t}) (range (+ senses/keep-lines 5))))]
    (is (= senses/keep-lines (count ls)))
    (is (= 5 (:t (first ls))) "the oldest five dropped")))
