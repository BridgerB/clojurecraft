(ns clojurecraft.runner
  "The test runner: eftest over the test directory, every namespace and every var in parallel
   on all cores, with the reducers instrumented once for the whole run.

     clojure -M:test                      ; everything
     clojure -M:test -n clojurecraft.plan-test [-n ...]   ; namespaces
     clojure -M:test -v clojurecraft.sim-test/the-wood-goal-holds-in-generated-forests
     clojure -M:test -x clojurecraft.sim-test             ; everything but

   The tests are pure functions over values (a world in, a world out), so they share nothing
   and run side by side; the serial suite took eight minutes on a runner and most of it was
   one namespace. Exit code 1 when anything fails or errors."
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojurecraft.fixtures :as fixtures]
            [eftest.report.pretty]
            [eftest.runner :as eftest]))

(def test-dir "test")                 ; where the namespaces are found

(defn parse
  "{:namespaces [sym] :vars [sym] :exclude #{sym}} from the command line."
  [args]
  (reduce (fn [m [flag value]]
            (case flag
              "-n" (update m :namespaces conj (symbol value))
              "-v" (update m :vars conj (symbol value))
              "-x" (update m :exclude conj (symbol value))
              (throw (ex-info (str "unknown flag " flag) {:args args}))))
          {:namespaces [] :vars [] :exclude #{}}
          (partition 2 args)))

(defn selected
  "The test vars a parse selects: the named vars and namespaces, or every namespace under the
   test directory, less the excluded namespaces."
  [{:keys [namespaces vars exclude]}]
  (let [found (if (or (seq namespaces) (seq vars))
                (do (doseq [n (concat namespaces (map (comp symbol namespace) vars))] (require n))   ; find-tests wants them loaded
                    (concat (mapcat eftest/find-tests namespaces) (mapcat eftest/find-tests vars)))
                (eftest/find-tests test-dir))]
    ;; realized here, before the reducers are instrumented: find-tests loads a namespace as its
    ;; vars are walked, and a defmethod on an instrumented multimethod var cannot cast it
    (vec (remove (fn [v] (contains? exclude (ns-name (:ns (meta v))))) found))))

;;;; I/O: the run ;;;;

(defn -main
  "Run the selected tests in parallel and exit with the verdict."
  [& args]
  (let [vars (selected (parse args))
        _ (fixtures/instrument!)
        {:keys [fail error] :as result} (eftest/run-tests vars {:multithread? true
                                                                :report eftest.report.pretty/report
                                                                :test-warn-time 60000})]
    (println (str/join " " (for [[k v] (select-keys result [:test :pass :fail :error :duration])] (str (name k) " " v))))
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
