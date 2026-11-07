(ns clojurecraft.lanes
  "Which start and page kind a lane plays, from ci/lanes.edn: pure functions over that value, with
   no dependency, so Babashka (`bb lane`) and the workflows read the same file the same way. A lane
   is a runner number of the real race (race.yml) or of the sim race (simrace.yml)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn lane-of
  "The lane n of kind (:real or :sim) in cfg (ci/lanes.edn's value): the first entry of that kind
   whose :lane/runners hold n, else kind's :default; always a map with :lane/page, and with
   :lane/start when an entry named it."
  [cfg kind n]
  (or (first (filter #(and (= kind (:lane/kind %)) (contains? (set (:lane/runners %)) n))
                     (:lanes cfg)))
      (get-in cfg [:default kind])))

(defn outputs
  "The lane as the lines a workflow appends to $GITHUB_OUTPUT: start= (empty when the table's
   default start is to be used) and kind= (the page kind)."
  [lane]
  [(str "start="
        (some-> (:lane/start lane)
                name)) (str "kind=" (:lane/page lane))])

;;;; I/O: the file and the command line ;;;;

(defn -main
  "bb lane <real|sim> <n>: print the lane's outputs, one key=value a line, for $GITHUB_OUTPUT."
  [& [kind n]]
  (let [cfg (edn/read-string (slurp "ci/lanes.edn"))]
    (println (str/join "\n" (outputs (lane-of cfg (keyword kind) (parse-long n)))))))
