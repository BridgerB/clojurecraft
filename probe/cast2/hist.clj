(ns cast2.hist
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.memory :as memory]
            [datascript.core :as d]))

(defn -main
  [& paths]
  (doseq [p paths]
    (let [snap (capture/read-world p)
          w (update (:snapshot/world snap) :world/facts d/from-serializable)
          t (:t (:snapshot/moment snap))
          fs (memory/intentions w)]
      (println "HIST" p "moment" t "intentions" (count fs))
      (doseq [f fs
              :let [k (:intention/kind f)
                    at (:intention/at f 0)
                    ev (:intention/event f)]
              :when (or (>= at (- t 900000))
                        (#{:death :toss :equip :fight :smelt} k)
                        (#{:craft} k)
                        (#{:failed :abandoned} ev))]
        (println "H"
                 at
                 ev
                 k
                 (pr-str (:intention/target f))
                 (pr-str (:intention/reason f))
                 (pr-str (:intention/recipe f)))))))
