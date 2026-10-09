(ns clojurecraft.make-test
  "The planner never asks for something the world cannot do: over generated inventories, the
   intent it picks for the :pickaxe goal is always executable from what is held and known."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(def pickaxe (first (filter #(= :pickaxe (:goal/id %)) plan/goals)))

(def held-gen
  (gen/hash-map :oak_log (gen/choose 0 3) :oak_planks (gen/choose 0 9) :stick (gen/choose 0 5)
                :crafting_table (gen/choose 0 1) :wooden_pickaxe (gen/frequency [[9 (gen/return 0)] [1 (gen/return 1)]])))

(defn world-holding
  "Standing on bare ground with no trees; optionally a remembered table one block away."
  [held table?]
  (let [inv (into {} (keep-indexed (fn [i [item n]] (when (pos? n) [i {:item (recipe/item-id item) :count n}])) held))]
    (cond-> (-> (game/init fx/opts)
                (assoc :bot/phase :play :player/pos [5.5 64.0 5.5] :player/loaded? true :player/on-ground? true
                       :player/inventory inv :world/chunks {[0 0] (world/column {})}))
      table? (assoc-in [:world/sightings [7 64 5]] {:block/state memory/crafting-table :block/seen-at 0}))))

(defn executable?
  "Is intent i something the world can do right now?"
  [w i]
  (let [counts (recipe/counts (:player/inventory w))]
    (case (:intent/kind i)
      :craft (let [r (recipe/by-id (:intent/recipe i))]
               (and (= :inventory (:intent/window i))            ; a table window is never open here
                    (recipe/fits? r 2)
                    (some? (recipe/clicks (:player/inventory w) r 2))))
      :place (and (= :crafting_table (:intent/item i)) (pos? (get counts :crafting_table 0)))
      :open-container (some? (get-in w [:world/sightings (:intent/target i)]))
      nil (= :no-log (:plan/wait i))                            ; gathering with no tree known: wait
      false)))

(deftest the-planner-only-picks-executable-intents
  (let [r (tc/quick-check
           400
           (prop/for-all [held held-gen table? gen/boolean]
                         (let [w (world-holding held table?)
                               i (make/decide w pickaxe)
                               counts (recipe/counts (:player/inventory w))]
                           (cond
                             (pos? (get counts :wooden_pickaxe 0)) (nil? i)
                             (nil? i) false
                             :else (and (executable? w i)
                            ;; gather (wait :no-log) only when no craft could make progress
                                        (or (not= :no-log (:plan/wait i))
                                            (zero? (get counts :oak_log 0))))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))
