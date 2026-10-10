(ns clojurecraft.model-test
  "The written information model stays the code's: every key a running bot writes is listed,
   and every listed attribute has a spec."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.make]
            [clojurecraft.model :as model]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.spec]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(def step (game/compose game/step plan/step))

(defn qualified-keys
  "Every namespace-qualified keyword used as a map key anywhere inside x."
  [x]
  (cond
    (map? x) (into (set (filter qualified-keyword? (keys x))) (mapcat qualified-keys (vals x)))
    (and (coll? x) (not (instance? datascript.db.DB x))) (into #{} (mapcat qualified-keys x))
    :else #{}))

(defn keys-of-a-pickaxe-run
  "Every qualified key in every world, event and effect of a whole pickaxe run against the
   server model, minus the server model's own, recipe and window-view helpers, and DataScript's
   schema vocabulary (:db/*)."
  []
  (let [seen (atom #{})
        watching (fn [w e] (let [w (step w e)] (swap! seen into (qualified-keys [w e])) w))
        column (world/column-bytes {[6 64 0] 136 [6 65 0] 136 [6 66 0] 136 [6 67 0] 136 [6 68 0] 252})]
    (sim/run watching (game/init fx/opts) (sim/init {:column column :spawn [0.5 64.0 0.5]})
             #(or (plan/done? %) (plan/failed? %)) 200000 {:event/kind :go :go/goals [:pickaxe]})
    (remove #(#{"sim" "recipe" "click" "view" "menu" "db" "db.unique"} (namespace %)) @seen)))

(deftest every-key-a-running-bot-writes-is-in-the-model
  (let [ks (keys-of-a-pickaxe-run)]
    (is (< 50 (count ks)) "the run exercised the model")
    (is (= [] (sort (remove model/by-attribute ks))))))

(deftest every-attribute-in-the-model-has-a-spec
  (is (= [] (remove s/get-spec (map first model/attributes)))))

(deftest the-model-names-each-attribute-once
  (is (= (count model/attributes) (count model/by-attribute))))

(deftest memory-facts-are-in-the-model
  (testing "facts live in the DataScript value, which the key walk does not enter"
    (is (every? model/by-attribute [:sight/pos :sight/state :sight/at]))))
