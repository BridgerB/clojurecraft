(ns clojurecraft.profile
  "Which functions take a frozen bot's seconds: fold a recording with the code that made it, time
   every event, and run the JDK flight recorder over the window [from to] (ms of the bot's clock)
   only. Prints the slowest events of the window (ms, kind, the intent then) and the methods the
   samples were in: self (the top clojurecraft frame of each sample) and inclusive (every
   clojurecraft frame a sample passes through). A probe script: it runs on a runner, never the Mac.

     clojure -M:dev -m clojurecraft.profile <recording> <from-ms> <to-ms>"
  (:require [clojure.string :as str]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.record :as record])
  (:import (java.time Duration)
           (jdk.jfr Recording)
           (jdk.jfr.consumer RecordingFile)))

(defn frame-name [f] (str (.getName (.getType (.getMethod f))) "/" (.getName (.getMethod f))))

(defn ours? [n] (str/starts-with? n "clojurecraft."))

(defn -main
  [path from-s to-s]
  (let [from (parse-long from-s)
        to (parse-long to-s)
        rec (doto (Recording.)
              (-> (.enable "jdk.ExecutionSample")
                  (.withPeriod (Duration/ofMillis 2))))
        slow (atom [])
        started (atom false)
        world0 (game/init {:host "replay" :port 0 :name "Clj_profile"})]
    (record/reduce-entries
     (fn [w e]
       (cond (not (:event/kind e)) w
             (> (:time/now w 0) to) (reduced w)
             :else
             (let [now (:time/now w 0)]
               (when (and (not @started) (>= now from)) (reset! started true) (.start rec))
               (let [t0 (System/nanoTime)
                     w' (assoc (main/step w e) :bot/effects [])
                     ms (quot (- (System/nanoTime) t0) 1000000)]
                 (when (and @started (>= ms 50))
                   (swap! slow conj
                     [ms (:event/kind e)
                      (some-> e
                              :event/packet
                              :packet/name)
                      (select-keys (:plan/intent w') [:intent/kind :intent/for :intent/goal])
                      (:time/now w')]))
                 w'))))
     world0
     path)
    (.stop rec)
    (let [out (java.nio.file.Path/of "out/profile.jfr" (make-array String 0))]
      (.dump rec out)
      (println "slowest events in the window (ms kind packet intent now):")
      (doseq [s (take 30 (sort-by first > @slow))] (println " " s))
      (println "events over 50 ms:" (count @slow) "total ms" (reduce + (map first @slow)))
      (let [events (RecordingFile/readAllEvents out)
            stacks (keep #(some-> (.getStackTrace %)
                                  .getFrames)
                         events)
            self (frequencies (keep #(some (fn [f] (let [n (frame-name f)] (when (ours? n) n))) %)
                                    stacks))
            incl (frequencies (mapcat #(distinct (filter ours? (map frame-name %))) stacks))]
        (println "samples:" (count stacks))
        (println "self, top clojurecraft frame:")
        (doseq [[n c] (take 40 (sort-by val > self))] (println " " c n))
        (println "inclusive:")
        (doseq [[n c] (take 60 (sort-by val > incl))] (println " " c n))))
    (shutdown-agents)))
