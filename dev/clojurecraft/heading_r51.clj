(ns clojurecraft.heading-r51
  "A probe script: Clj_r51's recording folded at its own commit (7f4d162), and at each of a list of
   times the heading's held leg beside the leg the heading-floor fix (fix/263-heading-floor) would
   hold, the fix's two functions copied in as pure functions (fixed-floor, fixed-target), with the
   fortress exit's surface and whether the exit holds. Runs on a runner (bb probe), never the Mac.

     clojure -M:dev -m clojurecraft.heading-r51 <recording> <from-ms> <to-ms> <every-ms>"
  (:require [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.record :as record]))

(defn fixed-floor
  "The fix's heading-floor: heading-min-y, the lava floor, and while a fortress is known the
   exit's surface, never over heading-max-y."
  [world]
  (min pparams/heading-max-y
       (max pparams/heading-min-y
            (pplace/lava-floor world)
            (if (seq (fexit/fortress-points world)) (long (fexit/fortress-surface-y world)) 0))))

(defn fixed-leg
  "The fix's leg-from: heading-leg ahead along dir at the feet's y held to fixed-floor..max-y."
  [world [fx fy fz] [dx dz]]
  [(+ fx (* pparams/heading-leg dx))
   (long (max (fixed-floor world) (min pparams/heading-max-y fy)))
   (+ fz (* pparams/heading-leg dz))])

(defn fixed-target
  "The leg the fix holds over world: the held target when it is at or over fixed-floor, else one
   taken again from the feet along the held dir."
  [world]
  (when-let [h (:pearls/heading world)]
    (let [t (:target h)]
      (if (< (second t) (fixed-floor world)) (fixed-leg world (intent/feet world) (:dir h)) t))))

(defn line
  "One time's line."
  [w t]
  (let [i (:plan/intent w)
        h (:pearls/heading w)
        surface (try (fexit/fortress-surface-y w) (catch Exception e (str "THREW " (ex-message e))))
        old (:target h)
        fixed (fixed-target w)]
    (str "AT " t
         " feet " (intent/feet w)
         " intent " (pr-str (select-keys i [:intent/kind :intent/target]))
         " why " (pr-str (:intent/why (meta i)))
         " escaping? " (fexit/fortress-escaping? w)
         " surface " surface
         " heading " (pr-str (select-keys h [:dir :from :target]))
         " old-y " (second old)
         " fixed " (pr-str fixed)
         " old-under? " (boolean (and old (number? surface) (< (second old) surface)))
         " fixed-under? " (boolean (and fixed (number? surface) (< (second fixed) surface))))))

(defn -main
  "Fold the recording once, printing a line at each time from from-ms to to-ms every every-ms."
  [path from-ms to-ms every-ms]
  (let [ts (range (parse-long from-ms) (inc (parse-long to-ms)) (parse-long every-ms))]
    (record/reduce-entries (fn [[w ts] e]
                             (cond (empty? ts) (reduced [w ts])
                                   (not (:event/kind e)) [w ts]
                                   (> (:time/now w 0) (first ts))
                                   (do (if (:player/pos w)
                                         (println (line w (first ts)))
                                         (println "AT" (first ts) "not in the world yet"))
                                       [w (rest ts)])
                                   :else [(assoc (main/step w e) :bot/effects []) ts]))
                           [(game/init {:host "replay" :port 0 :name "Clj_why"}) ts]
                           path)
    (shutdown-agents)))
