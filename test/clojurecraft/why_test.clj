(ns clojurecraft.why-test
  "Every intent carries a why: the planner's own label when it tagged its branch, else the goal row
   and kind (plan/start-intent), and a child an executor starts inherits its parent's. Pure over
   hand-built intents and worlds. Not a walk over every planner: the fallback sits in the one
   function every running intent passes through, so what is held here is that function."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(deftest default-why-names-goal-and-kind
  (is (= :wood/walk (intent/default-why {:intent/kind :walk :intent/goal :wood})))
  (is (= :planner/walk (intent/default-why {:intent/kind :walk})))
  (is (= :planner/unknown (intent/default-why {}))))

(deftest why-or-default-keeps-a-planners-label
  (let [tagged (intent/why :wood/nearest-log {:intent/kind :walk :intent/goal :wood})]
    (is (= :wood/nearest-log (intent/why-of (intent/why-or-default tagged))))
    (is (= :wood/walk
           (intent/why-of (intent/why-or-default {:intent/kind :walk :intent/goal :wood}))))
    (is (nil? (intent/why-or-default nil)))
    (is (= {:plan/wait :x} (intent/why-or-default {:plan/wait :x})))
    (is (nil? (intent/why-of (intent/why-or-default {:plan/wait :x}))))
    (testing "equality never sees the label"
      (is (= {:intent/kind :walk} (intent/why-or-default {:intent/kind :walk}))))))

(deftest start-intent-labels-every-running-intent
  (let [world (game/init fx/opts)
        untagged (plan/start-intent world {:intent/kind :walk :intent/goal :wood})
        tagged (plan/start-intent world
                                  (intent/why :wood/nearest-log
                                              {:intent/kind :walk :intent/goal :wood}))]
    (is (= :wood/walk (intent/why-of (:plan/intent untagged))))
    (is (= :wood/nearest-log (intent/why-of (:plan/intent tagged))))
    (testing "the intention fact copies it"
      (is (= :wood/walk (:intention/why (memory/intention (:plan/intent untagged) :started 0)))))))

(deftest a-child-inherits-its-parents-why
  (let [parent (fn [why-label]
                 (cond-> {:intent/kind :tunnel-to
                          :intent/status :active
                          :intent/child {:intent/kind :no-such-kind :intent/status :active}}
                   why-label
                   (->> (intent/why why-label))))
        run-under (fn [p]
                    (-> (game/init fx/opts)
                        (assoc :plan/intent p)
                        (intent/run-child {:event/kind :tick})
                        :plan/intent
                        :intent/child))]
    (is (= :child/tunnel-to (intent/why-of (run-under (parent nil)))))
    (is (= :hunt/tunnel (intent/why-of (run-under (parent :hunt/tunnel)))))))
