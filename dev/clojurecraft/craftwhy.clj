(ns clojurecraft.craftwhy
  "The spare-picks craft at moments of a recording: the window, its grid and cursor, what is held,
   the free slots and the spare-picks row's answers, on the world as held and with the grid laid
   back (sword/with-laid). A probe script: it runs on a runner, never the Mac.

     clojure -M:dev -m clojurecraft.craftwhy <recording> <at-ms> ..."
  (:require [clojure.pprint :as pp]
            [clojurecraft.picks :as picks]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sword :as sword]
            [clojurecraft.why :as why]
            [clojurecraft.window :as window]))

(defn report
  "The craft facts of world w as a map."
  [w]
  (let [row (plan/row-of plan/goals :spare-picks)
        laid (sword/with-laid w)
        safe (fn [f] (try (f) (catch Exception e (str "THROWS " (ex-message e)))))]
    {:intent (select-keys (:plan/intent w) [:intent/kind :intent/goal :intent/stage :intent/target])
     :window/id (:window/id w)
     :window/menu-type (:window/menu-type w)
     :window/grid (into (sorted-map)
                        (for [[k v] (:window/grid w)]
                          [k (when (:item v) [(recipe/item-name (:item v)) (:count v)])]))
     :window/cursor (some-> (:window/cursor w)
                            :item
                            recipe/item-name)
     :held (into (sorted-map) (recipe/counts (:player/inventory w)))
     :free (safe #(window/free-count w))
     :serviceable (safe #(picks/serviceable-count w))
     :materials? (safe #(picks/materials? w))
     :materials?-laid (safe #(picks/materials? laid))
     :spare-wood? (safe #(picks/spare-wood? w))
     :spare-wood?-laid (safe #(picks/spare-wood? laid))
     :pending? (safe #(picks/pending? w))
     :done-by (safe #(plan/done-by w row))
     :choose (safe #(:goal/id (plan/choose w plan/goals)))}))

(defn -main
  "Print the craft facts at each at-ms of the recording at path."
  [path & ats]
  (doseq [a ats]
    (println "AT" a)
    (pp/pprint (report (why/at path (parse-long a)))))
  (shutdown-agents))
