(ns clojurecraft.recipe-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.spec]))

(defn inv [& kvs] (into {} (for [[slot name n] (partition 3 kvs)] [slot {:item (recipe/item-id name) :count n}])))

(deftest the-table-is-vanilla
  (is (= 1030 (count recipe/recipes)))
  (is clojurecraft.spec/recipes-valid? "every generated recipe satisfies ::recipe")
  (is clojurecraft.spec/goals-valid?)
  (is (= {:recipe/id :stick :recipe/result :stick :recipe/count 4 :recipe/kind :shaped :recipe/pattern ["#" "#"]
          :recipe/key {"#" (recipe/tags :planks)} :recipe/width 1 :recipe/height 2}
         (recipe/by-id :stick)))
  (is (contains? (recipe/tags :logs) :birch_log))
  (testing "shaped patterns are shrunk like vanilla: padding columns are not part of the shape"
    (is (= ["#" "X" "X"] (:recipe/pattern (recipe/by-id :spyglass))))
    (is (= 1 (:recipe/width (recipe/by-id :spyglass))))
    (is (recipe/fits? (recipe/by-id :waxed_chiseled_copper) 2) "a 1x2 recipe written 3 wide fits the 2x2 grid"))
  (is (= {(recipe/tags :planks) 4} (recipe/needs (recipe/by-id :crafting_table)))))

(deftest match-is-the-servers-rule
  (is (= :oak_planks (:recipe/id (recipe/match {1 :oak_log} 2))))
  (is (= :oak_planks (:recipe/id (recipe/match {4 :oak_log} 2))) "shapeless anywhere")
  (testing "stick at any offset"
    (is (= :stick (:recipe/id (recipe/match {1 :oak_planks 3 :birch_planks} 2))))
    (is (= :stick (:recipe/id (recipe/match {2 :oak_planks 4 :oak_planks} 2)))))
  (is (= :crafting_table (:recipe/id (recipe/match {1 :oak_planks 2 :oak_planks 3 :spruce_planks 4 :oak_planks} 2))))
  (testing "the junk a dirty grid mints, which is why a take is only ever made after verifying slot 0"
    (is (= :oak_pressure_plate (:recipe/id (recipe/match {1 :oak_planks 2 :oak_planks} 2))))
    (is (= :oak_button (:recipe/id (recipe/match {1 :oak_planks} 2)))))
  (is (nil? (recipe/match {1 :oak_log 2 :dirt} 2)))
  (is (nil? (recipe/match {} 2))))

(deftest clicks-lay-one-craft
  (is (= [{:click/slot 36 :click/button 0 :click/mode 0}
          {:click/slot 1 :click/button 1 :click/mode 0}
          {:click/slot 3 :click/button 1 :click/mode 0}
          {:click/slot 36 :click/button 0 :click/mode 0}]
         (recipe/clicks (inv 0 :oak_planks 8) (recipe/by-id :stick) 2)))
  (testing "a stack used up exactly needs no put-back"
    (is (= 5 (count (recipe/clicks (inv 0 :oak_planks 4) (recipe/by-id :crafting_table) 2)))))
  (testing "one stack per ingredient: a short stack is skipped for one that covers it"
    (is (= 12 (:click/slot (first (recipe/clicks (inv 0 :oak_planks 1 12 :birch_planks 4) (recipe/by-id :crafting_table) 2))))))
  (is (nil? (recipe/clicks (inv 0 :oak_planks 3) (recipe/by-id :crafting_table) 2)))
  (is (nil? (recipe/clicks {} (recipe/by-id :oak_planks) 2))))

(def two-by-two (filter #(recipe/fits? % 2) recipe/recipes))

(deftest clicks-then-match-round-trip
  (let [r (tc/quick-check
           300
           (prop/for-all [r (gen/elements two-by-two)]
                         (let [stock (into {} (map-indexed (fn [i [s n]] [(+ 9 i) {:item (recipe/item-id (first (sort s))) :count (+ n 3)}])
                                                           (recipe/needs r)))
                               cs (recipe/clicks stock r 2)
                               grid (into {} (for [{:click/keys [slot button]} cs :when (= 1 button)] [slot true]))
                               laid (into {} (for [[slot s] (recipe/placement r 2)] [slot (first (sort s))]))]
                           (and (some? cs)
                                (= (set (keys grid)) (set (keys laid)))
                                (= (:recipe/result (recipe/match laid 2)) (:recipe/result r))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))
