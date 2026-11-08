(ns clojurecraft.inspect
  "Where a race runner's CPU goes while a lane plays (race.yml): the servers tick 150 to 450 ms
   under ten bots each against vanilla's 50, and this says whether the machine is saturated and
   what inside a server takes the tick. It observes and never touches play.

     bb inspect sample <out-dir>
       until killed, every 30 s: one EDN line in <out-dir>/cpu.edn (sample): the load, the CPU
       split with steal, the busiest processes' share of a core, and for every server, race and
       conversion JVM its busiest threads by name; machine.txt once; a jstat -gcutil per server and
       race JVM (gc-<role>-<pid>.log), attached when the JVM first appears.
     bb inspect profile <k> <rcon-port> <rcon-pass> <world> <out-dir> <minute>...
       at each minute after its start, server k profiled for profile-s: the server's own profiler
       (debug start / debug stop, then perf start), a JFR recording beside it and its views, the
       tick query before and after, all under <out-dir>/s<k>-min<m>/.

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.math :as math]
            [clojure.string :as str]))

(def every-ms 30000)                      ; between samples
(def jstat-every "30s")                   ; jstat's own interval
(def busiest 12)                          ; processes a sample keeps, by share of a core
(def thread-groups 10)                    ; thread names a sample keeps per JVM
(def profile-s 60)                        ; the server's debug profile and the JFR recording beside it
(def perf-s 20)                           ; perf start records 10 s and writes its zip after
(def cpu-kinds [:user :nice :system :idle :iowait :irq :softirq :steal])
(def watched #"^(server|race)-|^convert$") ; roles whose threads a sample reads
(def jstat-roles #"^(server|race)-")      ; roles jstat is attached to
(def jfr-views ["hot-methods" "gc-pauses" "gc" "cpu-load" "thread-cpu-load" "allocation-by-class"])

;; ---------------------------------------------------------------- values

(defn round1 "x to one decimal place." [x] (/ (math/round (* 10.0 (double x))) 10.0))

(defn cpu-times
  "The machine's CPU time by kind (cpu-kinds), in clock ticks since boot, from /proc/stat's text:
   its first line, the sum over every CPU."
  [text]
  (let [nums (->> (str/split (first (str/split-lines text)) #"\s+")
                  rest
                  (map parse-long))]
    (zipmap cpu-kinds nums)))

(defn cpu-split
  "Each kind's percent of all CPU time between two cpu-times readings a and b, 100 in all; {} when
   no time passed. :steal is time the hypervisor gave another guest."
  [a b]
  (let [d (into {} (map (fn [k] [k (- (b k 0) (a k 0))])) cpu-kinds)
        total (reduce + (vals d))]
    (cond (pos? total) (update-vals d #(round1 (/ (* 100.0 %) total)))
          :else {})))

(defn load-avg
  "/proc/loadavg's text as {:load [1-min 5-min 15-min] :runnable n :tasks n}: the run queue now
   (runnable) beside the threads that exist (tasks)."
  [text]
  (let [[a b c rt] (str/split (str/trim text) #"\s+")
        [r t] (str/split rt #"/")]
    {:load [(parse-double a) (parse-double b) (parse-double c)]
     :runnable (parse-long r)
     :tasks (parse-long t)}))

(defn proc-stat
  "A process's or thread's /proc stat line as {:comm :ticks :threads :rss-pages}: its name, its
   user plus system clock ticks, its threads and its resident pages. The name is in parentheses
   and may hold spaces and parentheses, so the fields are read after the last \")\"."
  [line]
  (let [open (str/index-of line "(")
        close (str/last-index-of line ")")
        f (str/split (str/trim (subs line (inc close))) #"\s+")]
    {:comm (subs line (inc open) close)
     :ticks (+ (parse-long (f 11)) (parse-long (f 12)))
     :threads (parse-long (f 17))
     :rss-pages (parse-long (f 21))}))

(defn role
  "What a process is to the lane, from its command line (a vector of strings) and its working
   directory: \"server-k\" (the server jar, run in work/world-k), \"race-k\" (clojurecraft.race on
   game port 25564+k), \"convert\" (the churn's fold, clojurecraft.simrace); nil for any other."
  [args cwd]
  (let [after (fn [flag] (second (drop-while #(not= flag %) args)))]
    (condp some args
      #(str/ends-with? % "server-26.1.2.jar")
      (str "server-" (or (second (re-find #"world-(\d+)$" (str cwd))) "?"))
      #{"clojurecraft.race"}
      (str "race-"
           (or (some-> (after "--port")
                       parse-long
                       (- 25564))
               "?"))
      #{"clojurecraft.simrace"} "convert"
      nil)))

(defn thread-group
  "A thread's name with its numbers as #: the pool it belongs to (Worker-Main-#, C2 CompilerThre#)."
  [comm]
  (str/replace comm #"\d+" "#"))

(defn share
  "The percent of one core that ticks clock ticks are over dt-ms at hz ticks a second."
  [ticks dt-ms hz]
  (round1 (/ (* 100.0 ticks) (* hz (/ dt-ms 1000.0)))))

(defn shares
  "{key percent of one core} between readings before and after ({key ticks}) dt-ms apart at hz;
   a key only in after counts from zero (a process or thread born between them)."
  [before after dt-ms hz]
  (into {} (map (fn [[k t]] [k (share (- t (before k 0)) dt-ms hz)])) after))

(defn ticks-of "{key ticks} of a map {key {:ticks n ...}}." [m] (update-vals m :ticks))

(defn busiest-threads
  "Per role, the thread groups (thread-group) with the most of a core between readings before and
   after, the busiest thread-groups of them, as [[group percent] ...], busiest first. A reading's
   :threads is {[pid tid] {:comm :ticks}} and its :procs {pid {:role ...}}."
  [before after dt-ms hz]
  (let [cpu (shares (ticks-of (:threads before)) (ticks-of (:threads after)) dt-ms hz)
        rows (for [[[pid _ :as k] t] (:threads after)
                   :let [r (get-in after [:procs pid :role])]
                   :when r]
               [r (thread-group (:comm t)) (cpu k)])
        by-role (group-by first rows)]
    (update-vals by-role
                 (fn [rs]
                   (->> rs
                        (reduce (fn [m [_ g c]] (update m g (fnil + 0.0) c)) {})
                        (sort-by (comp - val))
                        (take thread-groups)
                        (mapv (fn [[g c]] [g (round1 c)])))))))

(defn sample
  "One sample as a value from two readings dt-ms apart ({:at :cpu :loadavg :procs :threads}),
   hz clock ticks a second: the time, the load, the CPU split, the busiest processes' share of a
   core (100 is one core, the runner has :cpus) with their role, threads and resident MB, and the
   busiest threads of each watched JVM."
  [before after hz]
  (let [dt (- (:at after) (:at before))
        cpu (shares (ticks-of (:procs before)) (ticks-of (:procs after)) dt hz)]
    (merge {:inspect/at (:at after)
            :inspect/dt-ms dt
            :inspect/cpus (:cpus after)
            :inspect/cpu (cpu-split (:cpu before) (:cpu after))
            :inspect/procs (->> (:procs after)
                                (map (fn [[pid m]]
                                       (cond-> {:pid pid
                                                :comm (:comm m)
                                                :cpu (cpu pid)
                                                :threads (:threads m)
                                                :rss-mb (quot (* 4 (:rss-pages m)) 1024)}
                                         (:role m) (assoc :role (:role m)))))
                                (sort-by (comp - :cpu))
                                (take busiest)
                                vec)
            :inspect/threads (busiest-threads before after dt hz)}
           (update-keys (load-avg (:loadavg after)) #(keyword "inspect" (name %))))))

;;;; I/O: /proc, jstat, RCON, jcmd and jfr ;;;;

(defn slurp-or-nil
  "The file's text, or nil when it cannot be read (a process that ended)."
  [f]
  (try (slurp (str f)) (catch Exception _ nil)))

(defn read-proc!
  "{pid {:comm :ticks :threads :rss-pages :role}} of every process this user can read."
  []
  (into {}
        (keep (fn [d]
                (let [pid (parse-long (fs/file-name d))
                      stat (slurp-or-nil (fs/file d "stat"))
                      args (some-> (slurp-or-nil (fs/file d "cmdline"))
                                   (str/split #"\u0000"))
                      cwd (try (str (fs/read-link (fs/path d "cwd"))) (catch Exception _ nil))]
                  (when stat
                    (try [pid (assoc (proc-stat stat) :role (role (vec args) cwd))]
                         (catch Exception _ nil))))))
        (filter #(re-matches #"\d+" (fs/file-name %)) (fs/list-dir "/proc"))))

(defn read-threads!
  "{[pid tid] {:comm :ticks}} of every thread of the processes pids."
  [pids]
  (into {}
        (for [pid pids
              t (try (fs/list-dir (fs/path "/proc" (str pid) "task")) (catch Exception _ nil))
              :let [stat (slurp-or-nil (fs/file t "stat"))]
              :when stat]
          [[pid (parse-long (fs/file-name t))] (select-keys (proc-stat stat) [:comm :ticks])])))

(defn reading!
  "The machine now: {:at :cpus :cpu :loadavg :procs :threads}."
  []
  (let [procs (read-proc!)]
    {:at (System/currentTimeMillis)
     :cpus (.availableProcessors (Runtime/getRuntime))
     :cpu (cpu-times (slurp "/proc/stat"))
     :loadavg (slurp "/proc/loadavg")
     :procs procs
     :threads (read-threads! (keep (fn [[pid m]]
                                     (when (some->> (:role m)
                                                    (re-find watched))
                                       pid))
                                   procs))}))

(defn sh-out!
  "A command's output and error as one string; never throws."
  [& args]
  (try (let [r (apply p/shell {:out :string :err :string :continue true} args)]
         (str (:out r) (:err r)))
       (catch Exception e (str "failed: " (ex-message e)))))

(defn machine!
  "What the runner is, once, to out/machine.txt: lscpu, memory, the kernel, the cgroup's CPU limit."
  [out]
  (spit (str (fs/file out "machine.txt"))
        (str/join "\n"
                  [(sh-out! "lscpu") (sh-out! "free" "-m") (sh-out! "uname" "-a")
                   (str "cpu.max " (slurp-or-nil "/sys/fs/cgroup/cpu.max"))])))

(defn attach-jstat!
  "A jstat -gcutil per server and race JVM in procs not in attached (a set of pids), its lines to
   out/gc-<role>-<pid>.log; returns attached with them."
  [out procs attached]
  (reduce (fn [acc [pid m]]
            (p/process {:out (fs/file out (str "gc-" (:role m) "-" pid ".log")) :err :out}
                       "jstat"
                       "-gcutil"
                       "-t"
                       (str pid)
                       jstat-every)
            (conj acc pid))
          attached
          (filter (fn [[pid m]]
                    (and (not (contains? attached pid))
                         (some->> (:role m)
                                  (re-find jstat-roles))))
                  procs)))

(defn sample!
  "Sample until killed: one sample line every every-ms to out/cpu.edn, machine.txt first."
  [out]
  (fs/create-dirs out)
  (machine! out)
  (let [hz (parse-long (str/trim (sh-out! "getconf" "CLK_TCK")))
        f (str (fs/file out "cpu.edn"))]
    (loop [before (reading!)
           attached #{}]
      (Thread/sleep every-ms)
      (let [after (reading!)
            attached' (try (attach-jstat! out (:procs after) attached)
                           (catch Exception e (println "jstat:" (ex-message e)) attached))]
        (try (spit f (str (pr-str (sample before after hz)) "\n") :append true)
             (catch Exception e (println "sample:" (ex-message e))))
        (recur after attached')))))

(defn rcon!
  "One RCON command's reply (clojure -M:rcon)."
  [port pass & words]
  (apply sh-out! "clojure" "-M:rcon" "--port" (str port) "--pass" pass words))

(defn profile!
  "Profile server k (its RCON at port with pass, its world directory world) once into
   out/s<k>-min<minute>: the tick query, the server's debug profiler and a JFR recording over
   profile-s, the tick query again, perf start, the JFR views (jfr-views), and the server's debug/
   directory, where debug stop and perf write their reports."
  [k port pass world out minute]
  (let [dir (fs/file out (str "s" k "-min" minute))
        pid (str/trim (slurp (str (fs/file world "server.pid"))))
        jfr (str (fs/absolutize (fs/file dir "server.jfr")))
        put (fn [n text] (spit (str (fs/file dir n)) text))]
    (fs/create-dirs dir)
    (put "tick-before.txt" (rcon! port pass "tick" "query"))
    (put "debug-start.txt" (rcon! port pass "debug" "start"))
    (put "jfr-start.txt"
         (sh-out! "jcmd"
                  pid
                  "JFR.start"
                  "name=inspect"
                  "settings=profile"
                  (str "duration=" profile-s "s")
                  (str "filename=" jfr)))
    (Thread/sleep (* 1000 profile-s))
    (put "debug-stop.txt" (rcon! port pass "debug" "stop"))
    (put "tick-after.txt" (rcon! port pass "tick" "query"))
    (put "perf-start.txt" (rcon! port pass "perf" "start"))
    (Thread/sleep (* 1000 perf-s))
    (doseq [v jfr-views] (put (str "jfr-" v ".txt") (sh-out! "jfr" "view" "--width" "220" v jfr)))
    (when (fs/exists? (fs/file world "debug"))
      (fs/copy-tree (fs/file world "debug") (fs/file dir "debug") {:replace-existing true}))
    (println (str "inspect: server " k " profiled at minute " minute " into " dir))))

(defn profiles!
  "Profile server k at each of minutes after now (profile!); a failed profile is printed, never
   thrown, so the next still runs."
  [k port pass world out minutes]
  (let [t0 (System/currentTimeMillis)]
    (doseq [m minutes]
      (let [wait (- (+ t0 (* 60000 m)) (System/currentTimeMillis))]
        (when (pos? wait) (Thread/sleep wait))
        (try (profile! k port pass world out m)
             (catch Exception e (println "inspect: profile failed:" (ex-message e))))))))

(defn -main
  "bb inspect sample <out-dir> | bb inspect profile <k> <rcon-port> <pass> <world> <out-dir> <minute>..."
  [& [cmd & args]]
  (case cmd
    "sample" (sample! (first args))
    "profile"
    (let [[k port pass world out & minutes] args]
      (fs/create-dirs out)
      (profiles! k port pass world out (map parse-long minutes)))
    (do
      (println
       "usage: bb inspect sample <out-dir> | profile <k> <rcon-port> <pass> <world> <out-dir> <minute>...")
      (System/exit 2))))
