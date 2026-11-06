(ns clojurecraft.why-idle
  "Why the pearls planner answered nothing: fold a recording with this commit's code up to at-ms
   (the bot's clock) and print what each branch of the :pearls next-intent and the hunt saw then.
   A probe script: it runs on a runner, never the Mac.

     clojure -M:dev -m clojurecraft.why-idle <recording> <at-ms>"
  (:require [clojurecraft.blaze :as blaze]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.plan :as plan]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.record :as record]
            [clojurecraft.window :as window]))

(defn probe
  "What the pearls row's branches see in world w, as a map of name to value."
  [w]
  (let [safe (fn [f] (try (f) (catch Exception t (str "THROWS " (.getMessage t)))))]
    (into
     (sorted-map)
     (for [[k f] {:now #(:time/now w)
                  :feet #(intent/feet w)
                  :health #(:player/health w)
                  :intent #(:plan/intent w)
                  :plan-ended #(:plan/ended w)
                  :plan-waiting #(:plan/waiting w)
                  :plan-failed #(select-keys (:plan/failed w)
                                             [:intent/kind :intent/reason :intent/target])
                  :menaced? #(pearls/menaced? w)
                  :fresh-kill #(pearls/fresh-kill w)
                  :worn-out? #(pearls/worn-out? w)
                  :pickaxe? #(pearls/pickaxe? w)
                  :spare? #(pearls/spare? w)
                  :shelter #(pearls/shelter w)
                  :reserve-toss #(window/reserve-toss w)
                  :reserve-gather #(pearls/reserve-gather w)
                  :blaze-refuge #(pearls/blaze-refuge w)
                  :pocket-in? #(pocket/in? w)
                  :pocket-bore #(pearls/pocket-bore w)
                  :fortress-escaping? #(pearls/fortress-escaping? w)
                  :can-tunnel-out? #(pearls/can-tunnel-out? w)
                  :fortress-tunnel-target #(pearls/fortress-tunnel-target w)
                  :fortress-tunnel-out #(pearls/fortress-tunnel-out w)
                  :near-fortress? #(pearls/near-fortress? w)
                  :nearest-spawner #(blaze/nearest-spawner w)
                  :trail #(:fortress/trail w)
                  :hunt #(pearls/hunt w)
                  :pearl-fallback #(pearls/pearl-fallback w)
                  :next-intent #(plan/next-intent
                                 w
                                 (first (filter (fn [r] (= :ender-pearls (:goal/id r))) plan/goals))
                                 plan/goals)
                  :bricks-n #(count (fortress/bricks w))}]
       [k (safe f)]))))

(defn -main
  [path at-s]
  (let [at (parse-long at-s)
        world0 (game/init {:host "replay" :port 0 :name "Clj_why"})
        w (record/reduce-entries (fn [w e]
                                   (cond (not (:event/kind e)) w
                                         (> (:time/now w 0) at) (reduced w)
                                         :else (assoc (main/step w e) :bot/effects [])))
                                 world0
                                 path)]
    (doseq [[k v] (probe w)] (println k (pr-str v)))
    (shutdown-agents)))
