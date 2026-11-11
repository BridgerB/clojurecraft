(ns clojurecraft.jfr
  "Where a race JVM's CPU goes, from a flight recording of it (race.yml, a lane whose ci/race.edn
   entry has :race/jfr): each execution sample is put to the part of the bot it was in (roles, by
   the frames on its stack: the step, the ledger, packet decoding ...) and to its thread's pool,
   and the summary prints each part's share with its hottest frames.

     bb jfr summary <recording.jfr> <out.txt>

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]))

(def stack-depth 200)                  ; frames jfr print keeps a sample: deep enough to reach the loop under a Datalog
(def top-frames 15)                    ; frames the summary lists for each part
(def roles
  "The part of the bot a sample was in, by the first of these frames found anywhere on its stack:
   [part frame-substring], most specific first. A sample matching none is put to its thread's pool."
  [[:ledger "clojurecraft.senses$ledger_BANG_"] [:senses "clojurecraft.senses$senses_BANG_"]
   [:recording "clojurecraft.record$tap_BANG_"] [:decode-chunk "clojurecraft.chunk$attach"]
   [:decode "clojurecraft.packet$decode"] [:send "clojurecraft.conn$write_frame"]
   [:send "clojurecraft.packet$encode"] [:plan "clojurecraft.plan$step_over"]
   [:protocol "clojurecraft.game$"] [:loop "clojurecraft.main$run_loop_BANG_"]
   [:race "clojurecraft.race$"]])

;; ---------------------------------------------------------------- values

(defn thread-group
  "A thread's name with its numbers as #: the pool it belongs to (async-mixed-#)."
  [name]
  (str/replace name #"\d+" "#"))

(defn frame-name
  "A stack line of jfr print as its method, without arguments or line: \"a.b$c.invoke\"."
  [line]
  (-> line
      str/trim
      (str/replace #"\(.*$" "")))

(defn role
  "The part a sample was in: the first of roles whose frame is on its stack, else :pool/<its
   thread's pool>."
  [{:keys [thread frames]}]
  (or (some (fn [[r s]] (when (some #(str/includes? % s) frames) r)) roles)
      (keyword "pool" (thread-group (or thread "?")))))

(defn bot-fns
  "The clojurecraft functions on a stack, each once: \"clojurecraft.plan$step_over\"."
  [frames]
  (distinct (keep #(second (re-find #"^(clojurecraft\.[\w.]+\$[\w]+?)(?:__\d+)?(?:\$|\.)" %))
                  frames)))

(defn tally
  "acc ({role {:n :self {frame n} :bot {fn n}}}) with one sample counted under its role: its leaf
   frame and each clojurecraft function on its stack."
  [acc {:keys [frames] :as sample}]
  (cond (empty? frames) acc
        :else
        (let [r (role sample)
              inc0 (fnil inc 0)]
          (reduce (fn [a fn-name] (update-in a [r :bot fn-name] inc0))
                  (-> acc
                      (update-in [r :n] inc0)
                      (update-in [r :self (first frames)] inc0))
                  (bot-fns frames)))))

(defn add-line
  "{:acc :cur} with one line of jfr print's output: a sample's head closes the open one into acc
   (tally) and opens a new one; a thread line names it; a stack line adds a frame."
  [{:keys [cur] :as st} line]
  (cond (str/starts-with? line "jdk.ExecutionSample")
        (-> st
            (update :acc tally (or cur {}))
            (assoc :cur {:frames []}))
        (nil? cur) st
        (str/includes? line "sampledThread = ")
        (assoc-in st [:cur :thread] (second (re-find #"sampledThread = \"([^\"]*)\"" line)))
        (re-find #"^\s{4,}[\w$.<>]+\(.*\)" line)
        (update-in st [:cur :frames] conj (frame-name line))
        :else st))

(defn tallies
  "jfr print's lines of execution samples, tallied by part (tally)."
  [lines]
  (let [{:keys [acc cur]} (reduce add-line {:acc {} :cur nil} lines)] (tally acc (or cur {}))))

(defn top
  "The n biggest of a map {x count}, [[x count] ...], biggest first."
  [n m]
  (vec (take n (sort-by (comp - val) m))))

(defn summary
  "Tallies as {:samples n :parts [{:role r :samples n :share pct :self [[frame n] ...] :bot [[fn
   n] ...]}]}, biggest part first, top-frames of each list."
  [acc]
  (let [n (reduce + (map :n (vals acc)))]
    {:samples n
     :parts (->> acc
                 (map (fn [[r {k :n :keys [self bot]}]]
                        {:role r
                         :samples k
                         :share (/ (math/round (/ (* 1000.0 k) (max 1 n))) 10.0)
                         :self (top top-frames self)
                         :bot (top top-frames bot)}))
                 (sort-by (comp - :samples))
                 vec)}))

(defn text
  "A summary as the lines a person reads."
  [{:keys [samples parts]}]
  (str/join "\n"
            (concat [(str "execution samples " samples)]
                    (for [{:keys [role share self bot] n :samples} parts]
                      (str/join "\n"
                                (concat [(str "\n== " (pr-str role) " " share "% (" n ")")]
                                        (for [[f c] self] (str "  self " c " " f))
                                        (for [[f c] bot] (str "  bot  " c " " f))))))))

;;;; I/O: jfr ;;;;

(defn summary!
  "Summarise the recording at jfr (jfr print, streamed) into out, with jfr's own views of its
   threads' CPU and its GCs beside it."
  [jfr out]
  (let [proc (p/process {:err :inherit}
                        "jfr"
                        "print"
                        "--events"
                        "jdk.ExecutionSample"
                        "--stack-depth"
                        (str stack-depth)
                        jfr)
        s (with-open [r (io/reader (:out proc))] (summary (tallies (line-seq r))))
        view (fn [v]
               (:out (p/shell {:out :string :err :string :continue true}
                              "jfr"
                              "view"
                              "--width"
                              "200"
                              v
                              jfr)))]
    (fs/create-dirs (fs/parent (fs/absolutize out)))
    (spit out
          (str (text s)
               "\n\n" (view "thread-cpu-load")
               "\n" (view "gc-pauses")
               "\n" (view "hot-methods")))
    (println (str "jfr: " (:samples s) " samples into " out))))

(defn -main
  "bb jfr summary <recording.jfr> <out.txt>"
  [& [cmd jfr out]]
  (case cmd
    "summary" (summary! jfr out)
    (do (println "usage: bb jfr summary <recording.jfr> <out.txt>") (System/exit 2))))
