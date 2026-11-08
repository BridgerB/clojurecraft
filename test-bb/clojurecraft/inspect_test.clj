(ns clojurecraft.inspect-test
  "The race runner's samples as values: /proc's lines read, a process's role in the lane, shares of
   a core between two readings, the busiest threads by pool. Babashka runs these (bb test:bb)."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.inspect :as inspect]))

(def stat-line
  "A server's /proc/<pid>/stat: a name with a space and a parenthesis, utime 1500, stime 500,
   85 threads, 262144 resident pages."
  (str "4242 (Server (x) th) S 1 4242 4242 0 -1 4194560 100 0 0 0 1500 500 0 0 20 0 85 0 300 "
       "4000000000 262144 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 2 0 0 0 0 0"))

(deftest a-stat-line-is-read-after-its-name
  (is (= {:comm "Server (x) th" :ticks 2000 :threads 85 :rss-pages 262144}
         (inspect/proc-stat stat-line))))

(deftest the-cpu-split-is-each-kinds-share-of-the-interval
  (let [a (inspect/cpu-times "cpu  100 0 50 800 10 0 0 40 0 0\ncpu0 1 2 3")
        b (inspect/cpu-times "cpu  400 0 150 900 10 0 0 140 0 0\ncpu0 1 2 3")]
    (is (= 100 (:user a)) "the first line, the sum over every CPU")
    (is
     (= {:user 50.0 :nice 0.0 :system 16.7 :idle 16.7 :iowait 0.0 :irq 0.0 :softirq 0.0 :steal 16.7}
        (inspect/cpu-split a b)))
    (is (= {} (inspect/cpu-split a a)) "no time passed")))

(deftest the-load-carries-the-run-queue
  (is (= {:load [3.9 4.2 4.0] :runnable 6 :tasks 512}
         (inspect/load-avg "3.90 4.20 4.00 6/512 9999\n"))))

(deftest a-process-is-named-by-its-part-in-the-lane
  (is (= "server-2"
         (inspect/role ["java" "-Xmx3072M" "-jar" "/r/data/local-server/server-26.1.2.jar" "nogui"]
                       "/r/work/world-2")))
  (is (= "race-1"
         (inspect/role ["java" "-Xmx2048m" "clojure.main" "-m" "clojurecraft.race" "--config"
                        "ci/race.edn" "--port" "25565"]
                       "/r")))
  (is (= "convert" (inspect/role ["java" "clojure.main" "-m" "clojurecraft.simrace"] "/r")))
  (is (nil? (inspect/role ["bash" "-e"] "/r"))))

(deftest a-share-is-of-one-core
  (is (= 100.0 (inspect/share 3000 30000 100)) "30 s of one core at 100 ticks a second")
  (is (= {1 50.0 2 10.0} (inspect/shares {1 1000 3 5} {1 2500 2 300} 30000 100))
      "a key only in after counts from zero; one gone is dropped"))

(def before
  "A reading: a server with two worker threads and its tick thread, and a shell."
  {:at 0
   :cpus 4
   :cpu {:user 0 :idle 0}
   :loadavg "1.0 1.0 1.0 1/100 1"
   :procs {10 {:comm "java" :ticks 0 :threads 3 :rss-pages 1024 :role "server-1"}
           20 {:comm "bash" :ticks 0 :threads 1 :rss-pages 256}}
   :threads {[10 11] {:comm "Server thread" :ticks 0}
             [10 12] {:comm "Worker-Main-1" :ticks 0}
             [10 13] {:comm "Worker-Main-2" :ticks 0}}})

(def after
  "Thirty seconds later: the tick thread a whole core, the workers half of one between them."
  (-> before
      (assoc :at 30000 :cpu {:user 300 :idle 100} :loadavg "4.5 3.0 2.0 5/120 2")
      (assoc-in [:procs 10 :ticks] 4500)
      (assoc-in [:procs 20 :ticks] 30)
      (assoc-in [:threads [10 11] :ticks] 3000)
      (assoc-in [:threads [10 12] :ticks] 1000)
      (assoc-in [:threads [10 13] :ticks] 500)))

(deftest a-sample-names-the-busiest-processes-and-threads
  (let [s (inspect/sample before after 100)]
    (is (= 30000 (:inspect/dt-ms s)))
    (is (= [4.5 3.0 2.0] (:inspect/load s)))
    (is (= 5 (:inspect/runnable s)))
    (is (= 75.0 (get-in s [:inspect/cpu :user])))
    (is (= [{:pid 10 :comm "java" :cpu 150.0 :threads 3 :rss-mb 4 :role "server-1"}
            {:pid 20 :comm "bash" :cpu 1.0 :threads 1 :rss-mb 1}]
           (:inspect/procs s))
        "busiest first, a role only where the lane has one")
    (is (= {"server-1" [["Server thread" 100.0] ["Worker-Main-#" 50.0]]} (:inspect/threads s))
        "the workers summed as one pool")))
