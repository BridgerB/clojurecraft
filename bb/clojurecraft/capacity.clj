(ns clojurecraft.capacity
  "How many racing bots one runner carries, and how to split its memory (capacity.yml).

     bb capacity sample <out.edn> <runner>      the machine every 15 s, beside a ramping race
     bb capacity report <dir>                    from every runner's samples: the knee and the sizing

   The race ramps (ci/capacity.edn): ten bots, then ten more every five minutes. Two observers
   write facts beside it: the race's sampler (SAMPLE, samples.edn: bots joined and playing, their
   ticks per second, the server's tick times, the bots' heap) and this one (sys.edn: each JVM's
   resident memory, CPU and heap, the machine's free memory, swap and load). The report reads
   them all: for every runner and every load it judges the settled minutes against the limits,
   takes the most bots each runner carried with every load up to it healthy, fits each JVM's live
   heap per bot across all runners, and sizes the server's heap, the bots' heap and the memory
   left free for the recommended number of bots."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]))

(def every-ms 15000)                    ; how often the machine is sampled
(def settle-ms 120000)                  ; a load's first two minutes (landings, chunk generation) are not judged
(def runner-mb 16384)                   ; a GitHub runner's memory
(def free-floor-mb 1536)                ; memory left for the OS, its caches and the runner itself
(def fence "```")
(def heap-factor 2.0)                   ; a heap twice its live set: G1 collects calmly with half of it free
(def limits
  "What a healthy load is: the server's 95th-percentile tick inside its 50 ms, the slowest tenth of
   the bots at 95% of 20 ticks a second, no bot cut off, no swap, memory to spare."
  {:server-p95-ms 50.0 :bot-p10-tps 19.0 :swap-mb 256 :available-mb 1024})

;; ---------------------------------------------------------------- the report, as values

(defn median
  "The median of xs, nil when empty."
  [xs]
  (let [v (vec (sort (remove nil? xs)))] (when (seq v) (nth v (quot (count v) 2)))))

(defn nearest
  "The sys fact taken nearest to ms."
  [sys ms]
  (when (seq sys) (apply min-key #(abs (- (:sys/at %) ms)) sys)))

(defn levels
  "One runner's settled loads: its samples grouped by how many bots had joined, each group
   without its first settle-ms, each sample with the machine's fact nearest it."
  [samples sys]
  (->> (group-by :sample/joined samples)
       (map (fn [[n ss]]
              (let [t0 (apply min (map :sample/at ss))]
                [n
                 (for [s ss
                       :when (>= (- (:sample/at s) t0) settle-ms)]
                   (assoc s :sys (nearest sys (:sample/at s))))])))
       (remove (comp empty? second))
       (sort-by first)))

(defn judge
  "A runner at one load, from its settled samples: the worst of each measure and whether all are
   inside the limits."
  [n ss]
  (let [p95 (apply max 0 (keep (comp :p95 :sample/server-tick) ss))
        p10 (apply min 20.0 (keep (comp :p10 :sample/tick-rate) ss))
        cut (- (apply max (map :sample/cut-off ss)) (apply min (map :sample/cut-off ss)))
        swap (apply max 0 (keep (comp :sys/swap-used-mb :sys) ss))
        avail (apply min runner-mb (keep (comp :sys/mem-available-mb :sys) ss))
        minutes (/ (- (:sample/at (last ss)) (:sample/at (first ss))) 60000.0)
        playing (median (map :sample/playing ss))
        gained (- (:sample/milestones (last ss)) (:sample/milestones (first ss)))]
    {:bots n
     :server-p95-ms p95
     :bot-p10-tps p10
     :cut-off cut
     :swap-mb swap
     :available-mb avail
     :server-heap-low-mb (some->> (seq (keep (comp :heap-used-mb :sys/server :sys) ss))
                                  (apply min))
     :bot-heap-low-mb (some->> (seq (keep (comp :heap-used-mb :sys/bots :sys) ss))
                               (apply min))
     :server-overhead-mb (median (keep #(some-> %
                                                :sys
                                                :sys/server
                                                ((fn [j] (- (:rss-mb j) (:heap-cap-mb j)))))
                                       ss))
     :bot-overhead-mb (median (keep #(some-> %
                                             :sys
                                             :sys/bots
                                             ((fn [j] (- (:rss-mb j) (:heap-cap-mb j)))))
                                    ss))
     :cpu-busy-pct (median (keep (comp :sys/cpu-busy-pct :sys) ss))
     :milestones-per-bot-hour (when (and playing (pos? playing) (pos? minutes))
                                (/ (* 60.0 gained) (* playing minutes)))
     :healthy? (and (< p95 (:server-p95-ms limits))
                    (>= p10 (:bot-p10-tps limits))
                    (zero? cut)
                    (< swap (:swap-mb limits))
                    (> avail (:available-mb limits)))}))

(defn knee
  "The most bots a runner carried with every load up to it healthy, or 0."
  [judged]
  (or (:bots (last (take-while :healthy? judged))) 0))

(defn fit
  "The least-squares line [a b] through points [[x y] ...]: y = a + b·x."
  [pts]
  (let [n (count pts)
        sx (reduce + (map first pts))
        sy (reduce + (map second pts))
        sxx (reduce + (map #(* (first %) (first %)) pts))
        sxy (reduce + (map #(* (first %) (second %)) pts))
        d (- (* n sxx) (* sx sx))]
    (if (zero? d)
      [(/ sy (max 1 n)) 0.0]
      (let [b (/ (- (* n sxy) (* sx sy)) d)] [(/ (- sy (* b sx)) n) b]))))

(defn round-up "x rounded up to a multiple of m." [x m] (* m (long (math/ceil (/ (double x) m)))))

(defn sizing
  "The memory for n bots from the fitted live heaps ([a b] per JVM) and overheads: each heap
   heap-factor times its live set, rounded up to 512 MB, and what is left of the runner."
  [n [sa sb] [ba bb] server-over bot-over]
  (let [server (round-up (* heap-factor (+ sa (* sb n))) 512)
        bots (round-up (* heap-factor (+ ba (* bb n))) 512)]
    {:bots n
     :server-heap-mb server
     :bot-heap-mb bots
     :free-mb (long (- runner-mb server bots (or server-over 0) (or bot-over 0)))}))

(defn recommend
  "From every runner's judged loads: each runner's knee, the recommended bots (the lower quartile
   of the knees, so three runners in four carry it), the live heap per bot fitted over every
   healthy load, and the sizing for the recommended bots, lowered by ten until it leaves
   free-floor-mb free."
  [by-runner]
  (let [knees (sort (map (comp knee val) by-runner))
        n (nth knees (quot (count knees) 4) 0)
        healthy (filter :healthy? (mapcat val by-runner))
        server (fit (filter second (map (juxt :bots :server-heap-low-mb) healthy)))
        bots (fit (filter second (map (juxt :bots :bot-heap-low-mb) healthy)))
        so (median (map :server-overhead-mb healthy))
        bo (median (map :bot-overhead-mb healthy))
        sized (first (filter #(>= (:free-mb %) free-floor-mb)
                             (map #(sizing % server bots so bo) (range n 0 -10))))]
    {:knees knees
     :knee-median (median knees)
     :server-live-mb-per-bot (second server)
     :server-live-mb-base (first server)
     :bot-live-mb-per-bot (second bots)
     :bot-live-mb-base (first bots)
     :server-overhead-mb so
     :bot-overhead-mb bo
     :recommended sized}))

(defn fmt
  "A number to show."
  [x]
  (cond (nil? x) "-"
        (float? x) (format "%.1f" (double x))
        :else (str x)))

(defn markdown
  "The report: the recommendation, every load's medians across runners, and each runner's knee."
  [title by-runner rec]
  (let [by-n (group-by :bots (mapcat val by-runner))
        row (fn [& cs] (str "| " (str/join " | " (map fmt cs)) " |"))
        {:keys [bots server-heap-mb bot-heap-mb free-mb]} (:recommended rec)]
    (str/join
     "\n"
     (concat
      [title ""
       (str "**Recommended: "
            bots
            " bots per runner, server -Xmx"
            server-heap-mb
            "m, bots -Xmx"
            bot-heap-mb
            "m, "
            free-mb
            " MB left free** (x20 runners = "
            (* 20 (or bots 0))
            " racers)") ""
       (str "Knees (most bots with every load up to it healthy), one per runner: "
            (str/join ", " (:knees rec))
            "; median "
            (:knee-median rec)
            ". Recommended is the lower quartile.")
       (str "Live heap per bot: server "
            (fmt (:server-live-mb-per-bot rec))
            " MB (base "
            (fmt (:server-live-mb-base rec))
            "), bots "
            (fmt (:bot-live-mb-per-bot rec))
            " MB (base "
            (fmt (:bot-live-mb-base rec))
            "); outside the heap: server "
            (fmt (:server-overhead-mb rec))
            " MB, bots "
            (fmt (:bot-overhead-mb rec))
            " MB.")
       (str "Healthy: server p95 tick < "
            (:server-p95-ms limits)
            " ms, slowest tenth of bots >= "
            (:bot-p10-tps limits)
            " ticks/s, no bot cut off, swap < "
            (:swap-mb limits)
            " MB, > "
            (:available-mb limits)
            " MB available.") "" "**Every load, medians across runners**" ""
       (row "bots" "runners"
            "healthy" "server p95 ms"
            "bot p10 tps" "cpu %"
            "server heap low MB" "bot heap low MB"
            "available MB" "milestones/bot-hour")
       (row "---" "---" "---" "---" "---" "---" "---" "---" "---" "---")]
      (for [[n js] (sort-by first by-n)]
        (row n
             (count js)
             (count (filter :healthy? js))
             (median (map :server-p95-ms js))
             (median (map :bot-p10-tps js))
             (median (map :cpu-busy-pct js))
             (median (map :server-heap-low-mb js))
             (median (map :bot-heap-low-mb js))
             (median (map :available-mb js))
             (median (map :milestones-per-bot-hour js))))
      ["" (str fence "edn") (pr-str rec) fence]))))


;;;; I/O: /proc, jstat, the files, the GitHub API ;;;;

(defn sh
  "Run cmd, its output as a string, or nil when it fails."
  [& cmd]
  (let [r (apply p/shell {:out :string :err :string :continue true} cmd)]
    (when (zero? (:exit r)) (:out r))))

(defn pid
  "The pid of the process whose command line matches pattern, or nil."
  [pattern]
  (some-> (sh "pgrep" "-f" "-o" pattern)
          str/trim
          not-empty
          parse-long))

(defn proc-ticks
  "utime + stime of pid, in clock ticks."
  [pid]
  (let [f (str/split (slurp (str "/proc/" pid "/stat")) #"\s+")]
    (+ (parse-long (nth f 13)) (parse-long (nth f 14)))))

(defn rss-mb
  "Resident memory of pid, MB."
  [pid]
  (some->> (re-find #"VmRSS:\s+(\d+)" (slurp (str "/proc/" pid "/status")))
           second
           parse-long
           (#(quot % 1024))))

(defn heap
  "{:heap-used-mb :heap-cap-mb :gc-s} of JVM pid from jstat -gc."
  [pid]
  (when-let [out (sh "jstat" "-gc" (str pid))]
    (let [[hdr vals] (map #(str/split (str/trim %) #"\s+") (str/split-lines out))
          m (zipmap hdr (map parse-double vals))
          kb #(reduce + (map (fn [k] (get m k 0.0)) %))]
      {:heap-used-mb (long (/ (kb ["S0U" "S1U" "EU" "OU"]) 1024))
       :heap-cap-mb (long (/ (kb ["S0C" "S1C" "EC" "OC"]) 1024))
       :gc-s (get m "GCT")})))

(defn meminfo
  "{field kB} from /proc/meminfo."
  []
  (into {} (for [[_ k v] (re-seq #"(\w+):\s+(\d+)" (slurp "/proc/meminfo"))] [k (parse-long v)])))

(defn cpu-total
  "[busy total] jiffies of the whole machine."
  []
  (let [[_ & xs] (str/split (first (str/split-lines (slurp "/proc/stat"))) #"\s+")
        v (mapv parse-long xs)]
    [(- (reduce + v) (nth v 3) (nth v 4)) (reduce + v)]))

(defn sample!
  "Sample the machine every every-ms until killed: one sys fact per line in out."
  [out runner]
  (loop [prev nil]
    (let [now (System/currentTimeMillis)
          server (pid "server-26.1.2.jar")
          bots (pid "clojurecraft.race")
          cpu (cpu-total)
          ticks (into {}
                      (for [[k id] {:server server :bots bots}
                            :when id]
                        [k (try (proc-ticks id) (catch Exception _ nil))]))
          jvm (fn [k id]
                (when id
                  (try (merge {:rss-mb (rss-mb id)}
                              (heap id)
                              (when-let [[t0 at0] (get-in prev [:ticks k])]
                                {:cpu-pct (/ (* 100.0 (- (get ticks k) t0)) (/ (- now at0) 10.0))}))
                       (catch Exception _ nil))))
          mem (meminfo)
          fact {:sys/runner runner
                :sys/at now
                :sys/mem-total-mb (quot (get mem "MemTotal" 0) 1024)
                :sys/mem-available-mb (quot (get mem "MemAvailable" 0) 1024)
                :sys/swap-used-mb (quot (- (get mem "SwapTotal" 0) (get mem "SwapFree" 0)) 1024)
                :sys/load1 (parse-double (first (str/split (slurp "/proc/loadavg") #"\s+")))
                :sys/cpu-busy-pct (when-let [[b0 t0] (:cpu prev)]
                                    (/ (* 100.0 (- (first cpu) b0)) (max 1 (- (second cpu) t0))))
                :sys/server (jvm :server server)
                :sys/bots (jvm :bots bots)}]
      (spit out (str (pr-str fact) "\n") :append true)
      (Thread/sleep (long every-ms))
      (recur {:cpu cpu :ticks (into {} (for [[k t] ticks :when t] [k [t now]]))}))))

(defn read-edn-lines
  "Every EDN map in file f, one per line."
  [f]
  (if (.exists (io/file f))
    (into [] (keep #(try (edn/read-string %) (catch Exception _ nil))) (str/split-lines (slurp f)))
    []))

(defn report!
  "Read every runner's samples.edn and sys.edn under dir (one directory each), print the report,
   append it to the job summary, write capacity.edn, and post it on the experiment's pull
   request when GH_TOKEN is set."
  [dir title]
  (let [runs (for [d (.listFiles (io/file dir))
                   :when (.isDirectory ^java.io.File d)
                   :let [ss (read-edn-lines (io/file d "samples.edn"))
                         sys (read-edn-lines (io/file d "sys.edn"))]
                   :when (seq ss)]
               [(or (:sample/runner (first ss)) (.getName ^java.io.File d))
                (mapv (fn [[n xs]] (judge n xs)) (levels ss sys))])
        by-runner (into (sorted-map) runs)
        rec (recommend by-runner)
        md (markdown title by-runner rec)]
    (println md)
    (spit "capacity.edn" (pr-str {:recommendation rec :runs by-runner}))
    (when-let [summary (System/getenv "GITHUB_STEP_SUMMARY")] (spit summary md :append true))
    (when (and (System/getenv "GH_TOKEN") (System/getenv "GITHUB_REPOSITORY"))
      (let [repo (System/getenv "GITHUB_REPOSITORY")
            pr (some-> (sh "gh"
                           "api"
                           (str "repos/"
                                repo
                                "/pulls?head="
                                (first (str/split repo #"/"))
                                ":race-capacity&state=open"))
                       (json/parse-string true)
                       first
                       :number)]
        (when pr
          (sh "gh" "api" (str "repos/" repo "/issues/" pr "/comments") "-f" (str "body=" md)))))))

(defn -main
  "sample <out> <runner> | report <dir> [title]"
  [& [cmd a b]]
  (case cmd
    "sample" (sample! a (parse-long (str b)))
    "report" (report! a (or b "### Capacity"))))
