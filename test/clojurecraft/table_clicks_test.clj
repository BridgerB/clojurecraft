(ns clojurecraft.table-clicks-test
  "Every shaped recipe, laid by the bot's click plan into a crafting table that applies vanilla
   click rules (the sim), makes that recipe's result. Enumerated, not sampled."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(defn- table-slot [p] (cond (<= 0 p 8) (+ 37 p) (<= 9 p 35) (inc p)))

(defn- stock
  "One stack per ingredient set, enough for one craft, in main-inventory slots 9.."
  [r]
  (into {} (map-indexed (fn [i [s n]] [(+ 9 i) {:item (recipe/item-id (first (sort s))) :count (+ n 3)}])
                        (recipe/needs r))))

(defn- craft-in-table
  "Apply the click plan for r inside an open table window of the sim; returns the last item the
   server reported for slot 0 (the result), or :no-plan."
  [r]
  (let [inv (stock r)
        sim0 (-> (sim/init {:column (world/column-bytes {}) :spawn [0.5 64.0 0.5]})
                 (assoc :sim/phase :play :sim/inv inv :sim/window {:id 1 :grid {} :state-id 1}))]
    (if-let [cs (recipe/clicks inv r 3 table-slot)]
      (let [sim (reduce (fn [sim {:click/keys [slot button mode]}]
                          (sim/step sim {:sim/kind :packet
                                         :sim/packet {:packet/name :container-click :window-id 1
                                                      :state-id (get-in sim [:sim/window :state-id])
                                                      :slot slot :button button :mode mode :changed [] :cursor nil}}))
                        sim0 cs)]
        (->> (:sim/out sim)
             (filter #(and (= :container-set-slot (:packet/name %)) (= 0 (:slot %))))
             last
             :item))
      :no-plan)))

(deftest every-shaped-recipe-crafts-its-result-in-a-table
  (let [shaped (filter #(and (= :shaped (:recipe/kind %)) (recipe/fits? % 3)) recipe/recipes)
        bad (for [r shaped
                  :let [out (craft-in-table r)
                        want {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)}]
                  :when (not= out want)]
              [(:recipe/id r) out want])]
    (is (< 500 (count shaped)) "the enumeration covers the real table")
    (is (empty? bad) (pr-str (take 10 bad)))))
