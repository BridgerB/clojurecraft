(ns clojurecraft.selection-test
  "Selection, apart from schema (Maybe Not): each function in spec/selected states which world
   attributes it needs, and instrumented calls are refused without them."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.spec :as spec]))

(use-fixtures :once fx/instrumented)

(deftest every-selection-is-an-fdef
  (is (= [] (remove s/get-spec spec/selected))))

(deftest a-world-without-what-a-function-selects-is-refused
  (testing "the eye needs a position, which the world lacks before the first teleport"
    (is (thrown? clojure.lang.ExceptionInfo (game/eye (game/init fx/opts))))
    (is (= [0.5 65.62 0.5] (game/eye (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5])))))
  (testing "the needs planner needs the inventory, the position and memory"
    (is (thrown? clojure.lang.ExceptionInfo
                 (make/decide (dissoc (assoc (game/init fx/opts) :player/pos [0.5 64.0 0.5]) :world/facts)
                              (first clojurecraft.plan/targets)))))
  (testing "selection says nothing about shapes: a well-shaped extra key never matters"
    (is (= 0 (inventory/item-count (assoc (game/init fx/opts) :anything/else 1) 134)))))
