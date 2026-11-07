(ns clojurecraft.why
  "Why a bot did what it did at one moment of its recording (the observability plan, part F): the
   recording folded with this commit's code up to at-ms (the bot's clock), then the moment there
   (senses/moment: the whole mind) and the planner's answers: the row chosen (plan/choose), its
   next intent, and for the pearl stage each branch the hunt reads. A probe script: it runs on a
   runner (bb probe why), never the Mac.

     clojure -M:dev -m clojurecraft.why <recording .edn[.zst|.gz]> <at-ms>

   The fold is exact only at the commit that made the recording (record/header names it); a
   later commit's planner answers what it would do now, which is what a fix is judged by."
  (:require [clojure.pprint :as pp]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.plan :as plan]
            [clojurecraft.record :as record]
            [clojurecraft.senses :as senses]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(defn at
  "The world of the recording at path folded up to at-ms (the first event past it stops the fold)."
  [path at-ms]
  (record/reduce-entries (fn [w e]
                           (cond (not (:event/kind e)) w
                                 (> (:time/now w 0) at-ms) (reduced w)
                                 :else (assoc (main/step w e) :bot/effects [])))
                         (game/init {:host "replay" :port 0 :name "Clj_why"})
                         path))

(defn answers
  "What the planner answers in world w, as a map of name to value, each guarded: a throw is shown,
   never fatal. The chosen row and its next intent always; the pearl stage's branches too."
  [w]
  (let [safe (fn [f] (try (f) (catch Exception e (str "THROWS " (ex-message e)))))
        row (plan/choose w plan/goals)]
    (into
     (sorted-map)
     (for [[k f] {:choose #(:goal/id row)
                  :choose-next #(plan/next-intent w row plan/goals)
                  :menaced? #(pearls/menaced? w)
                  :shelter #(pearls/shelter w)
                  :hunt #(pearls/hunt w)
                  :pearl-fallback #(pearls/pearl-fallback w)
                  :pocket-bore #(pearls/pocket-bore w)
                  :blaze-refuge #(pearls/blaze-refuge w)
                  :fortress-escaping? #(pearls/fortress-escaping? w)
                  :angry-tracked? #(pearls/angry-tracked? w pearls/hold-radius)
                  :perched? #(pearls/perched? w)
                  :reserve-toss #(window/reserve-toss w)
                  :travel/in-forest? #(pearls/in-forest? w)
                  :travel/nearest-forest #(pearls/nearest-forest w)
                  :travel/warped-chunks #(count (memory/warped-chunks w))
                  :travel/travel #(pearls/travel w)
                  :travel/explore #(pearls/explore w)
                  :travel/far-ends #(vec (pearls/far-ends w 0 (memory/arrived w)))
                  :travel/far-ends-unspent #(vec (pearls/far-ends w 0 #{}))
                  :travel/arrived #(count (memory/arrived w))
                  :travel/pickaxe? #(pearls/pickaxe? w)
                  :travel/can-tunnel-out? #(pearls/can-tunnel-out? w)
                  :travel/near-fortress? #(pearls/near-fortress? w)
                  :travel/swarmed? #(pearls/swarmed? w)
                  :travel/recently-shot? #(pearls/recently-shot? w)
                  :travel/endermen-seek #(count (pearls/endermen w pearls/seek-radius))
                  :travel/wandering? #(pearls/wandering? w)
                  :travel/wander #(pearls/wander w)
                  :travel/caged? #(pearls/travel-caged? w)
                  :travel/blacklist #(:plan/blacklist w)
                  :dig/digs-left #(pearls/digs-left w)
                  :dig/spare? #(pearls/spare? w)
                  :dig/boxed-at #(:plan/boxed-at w)
                  :dig/stuck? #(pearls/stuck? w)
                  :dig/tunnel-out #(pearls/tunnel-out w)
                  :dig/climb-out #(pearls/climb-out w)
                  :dig/dig-out #(pearls/dig-out w)
                  :dig/uncage #(pearls/uncage w)
                  :dig/last-intent #(select-keys (:plan/last w) [:intent/kind :intent/reason])
                  :dig/worn-out? #(pearls/worn-out? w)
                  :dig/relocate #(:pearls/relocate w)
                  :exit/surface-y #(pearls/fortress-surface-y w)
                  :exit/fresh-target #(pearls/fresh-tunnel-target w)
                  :exit/held #(:pearls/exit-target w)
                  :exit/dir #(pearls/fortress-exit-dir w)
                  :exit/spawner #(blaze/nearest-spawner w)
                  :exit/points #(count (pearls/fortress-points w))
                  :relocate/walk #(pearls/relocate-walk w)
                  :relocate/state #(:pearls/relocate w)
                  :relocate/walk-dones #(mapv (juxt :intention/at :intention/target)
                                              (memory/newest-dones w :walk 8))
                  :relocate/endermen-128 #(count (pearls/endermen (dissoc w :plan/blacklist) 128.0))
                  :relocate/last-intents #(mapv (juxt :intent/kind :intent/for :intent/target)
                                                (take 4 (:plan/history w)))}]
       [k (safe f)]))))

(defn -main
  "Print the moment and the planner's answers at at-ms of the recording at path."
  [path at-ms]
  (let [w (at path (parse-long at-ms))]
    (println "HEADER" (pr-str (record/header path)))
    (println "MOMENT")
    (pp/pprint (senses/moment w))
    (println "ANSWERS")
    (pp/pprint (answers w))
    (shutdown-agents)))
