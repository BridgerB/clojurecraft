(ns clojurecraft.probe-dragon-test
  "A probe, not a test: prints what the dragon row answers in two fixture worlds."
  (:require [clojure.test :refer [deftest]]
            [clojurecraft.dragon :as dragon]
            [clojurecraft.dragon-test :as dt]
            [clojurecraft.mob :as mob]))

(defn show
  "Append a line to out/answers.txt: label, the answer, its why, the breath, the escape cell."
  [label w]
  (let [a (dragon/toward-dragon w)]
    (.mkdirs (java.io.File. "out"))
    (spit "out/answers.txt"
          (str "ANSWER "
               label
               " "
               (pr-str a)
               " why "
               (pr-str (:intent/why (meta a)))
               " breath "
               (pr-str (some-> (dragon/breath-near w)
                               (select-keys [:entity/pos])))
               " escape "
               (pr-str (dragon/escape w))
               " in-basin "
               (dragon/in-basin? w)
               "\n")
          :append true)))

(deftest answers
  (let [d {dt/dragon-eid {:entity/type (mob/type-id :ender_dragon) :entity/pos [0.5 90.0 0.5]}}
        cloud
        (fn [pos]
          {50 {:entity/type (mob/type-id :area_effect_cloud) :entity/pos pos :entity/radius 3.0}})
        w (fn [c] (dt/standing {} [12.5 64.0 0.5] {} (merge d c)))]
    (show "breath 10.5 flying dragon" (w (cloud [10.5 64.0 0.5])))
    (show "breath 7.5" (w (cloud [7.5 64.0 0.5])))
    (show "basin behind the sitting head" (dt/round-fountain [-0.5 64.0 -1.5] (dt/sitting 90.0)))
    (show "out from under at 4.5" (dt/round-fountain [4.5 64.0 0.5] (dt/sitting 90.0)))))
