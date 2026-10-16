(ns clojurecraft.real-test
  "Real failures as snapshots (issue #299): a run's own recording, folded, becomes a snapshot of
   its failure that the model judges as it judges the run's sim snapshot; the model joined to a
   bot's world holds what the server held; a Nether moment plays in a Nether model."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.real :as real]
            [clojurecraft.record :as record]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn bare
  "A world of one column of bare stone, no tree, of dimension dim, landing at x z on its floor: a
   run for wood waits :no-log and fails."
  [x z dim]
  (assoc (simrace/prepared {:capture/set "test"
                            :capture/landing [x z 64]
                            :capture/radius 0
                            :capture/columns {[0 0] (world/column-bytes {} dim)}})
         :capture/file
         "bare.edn.gz"))

(defn recorded-run
  "A run of world w from its landing to until as simrace/run plays it (the model given extra
   init options too), every event the bot folded kept as the recorder keeps it (record/wire),
   the RESULT line last: {:result :moments :last :world :lines}."
  [w seed until max-ms extra]
  (let [lines (volatile! [])
        step (fn [world e] (vswap! lines conj (record/wire e)) (simrace/step world e))
        s0 (sim/init (merge {:columns (:capture/columns w)
                             :decoded (:capture/decoded w)
                             :spawn (simrace/spawn-of w)
                             :seed seed
                             :tick-seed seed}
                            extra))
        {:keys [world moments last]} (sim/play-from step
                                                    (sim/start-moment step (game/init fx/opts) s0)
                                                    #(or (plan/done? %) (plan/failed? %))
                                                    max-ms
                                                    (simrace/go-of w until)
                                                    sim/moment-every-ms)
        r (failure/mark (main/result world until (main/ok? world until)))]
    {:result r
     :moments moments
     :last last
     :world world
     :lines (conj @lines {:record/commit "abc"} {:record/result r})}))

(defn sim-snapshot
  "The snapshot the sim race keeps of a recorded run of w (simrace/play!'s choice)."
  [w {:keys [result moments last world]}]
  (let [sig (:run/signature result)]
    (simrace/snapshot
     w
     3
     (:until result)
     "abc"
     sig
     (simrace/chosen-moment moments last (simrace/decision world sig) (:time/now world)))))

(defn real-snapshot
  "The real snapshot of a recorded run, from its lines alone (real/fold, real/convert), written
   and read back as the check reads it."
  [{:keys [lines]} difficulty]
  (let [folded (real/fold lines)
        r (:result folded)
        snap (real/convert folded
                           {:seed 3
                            :until (:until r)
                            :commit (:commit folded)
                            :signature (:run/signature r)
                            :difficulty difficulty
                            :recording "test.edn"
                            :bot "Clj_test"})
        path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-real-test.edn.gz")]
    (capture/write-world! path snap)
    (capture/read-world path)))

(deftest a-runs-own-recording-judges-as-its-sim-snapshot-does
  ;; issue #299: a recording holds everything a sim snapshot needs (the bot's world at every
  ;; moment, every column the server sent), so the snapshot built from a run's own recording, the
  ;; model joined to the bot there, must judge as the sim snapshot of the same run: both recur,
  ;; with the run's signature, inside their windows
  (let [r (tc/quick-check
           4
           (prop/for-all [seed (gen/choose 1 1000) x (gen/choose 1 14) z (gen/choose 1 14)]
                         (let [w (bare x z dimension/overworld)
                               run (recorded-run w seed "nether" 300000 {})
                               sim-snap (sim-snapshot w run)
                               real-snap (real-snapshot run nil)
                               sim-played (simrace/judged sim-snap w)
                               real-played (simrace/judged real-snap (simrace/own-world real-snap))]
                           (and (= [:wood :no-log] (:run/signature (:result run)))
                                (= :real (:snapshot/source real-snap))
                                (= "abc" (:snapshot/commit real-snap))
                                (= (:snapshot/signature sim-snap) (:snapshot/signature real-snap))
                                (simrace/recurs? sim-snap sim-played)
                                (simrace/recurs? real-snap real-played)
                                (nil? (real/gap real-snap real-played))
                                (= (:run/signature sim-played) (:run/signature real-played))
                                (<= (- (:run/ms real-played) (:run/from real-played))
                                    (simrace/window-of real-snap))))))]
    (is (:pass? r) (pr-str (select-keys r [:shrunk :result])))))

(deftest a-nether-moment-plays-in-a-nether-model
  ;; the Nether's columns from a recording (16 sections from y 0) make a Nether in the model,
  ;; where the sim's own Nether is one column: the bot holds them as it did, the model reads its
  ;; blocks from them, and the moment plays on and recurs there
  (let [w (bare 8 8 dimension/nether)
        run (recorded-run w 5 "nether" 300000 {:dimension dimension/nether})
        snap (real-snapshot run nil)
        [bw s] (simrace/moment snap (simrace/own-world snap))
        played (simrace/judged snap (simrace/own-world snap))]
    (is (= [:wood :no-log] (:run/signature (:result run))))
    (is (= dimension/nether (:player/dimension bw) (:sim/dimension s)))
    (is (= 0 (get-in bw [:world/chunks [0 0] :chunk/min-y])) "the bot's column is the Nether's")
    (is (= :stone (blocks/name-of (sim/block-at s [8 10 8]))) "the model reads the Nether column")
    (is (= :air (blocks/name-of (sim/block-at s [8 70 8]))))
    (is (simrace/recurs? snap played) (pr-str (:run/signature played)))))

(def zombie (:zombie blocks/entity-types))
(def bat (:bat blocks/entity-types))

(defn bot-world
  "A bot's world in play as the recording's fold leaves it, at 1000000 ms (a real clock): a
   column held, a block changed and a guessed one, items in its hands, its grid and armour, a
   zombie, a dropped item and a bat."
  []
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :time/now 1000000
             :plan/status :active
             :plan/go-at [8.5 64.0 8.5]
             :player/loaded? true
             :player/pos [8.5 64.0 8.5]
             :player/look [90.0 10.0]
             :player/on-ground? true
             :player/entity-id 389
             :player/held-slot 2
             :player/health 15.0
             :player/food 17
             :player/saturation 2.5
             :player/air 300
             :window/state-id 7
             :player/inventory
             {0 {:item 5 :count 3} 9 {:item 6 :count 1} 39 {:item 7 :count 1} 40 {:item 8 :count 2}}
             :window/grid {1 {:item 9 :count 1}}
             :world/chunks {[0 0] (world/column {})}
             :world/blocks {[1 64 1] 1 [2 64 2] 0}
             :world/unconfirmed {[2 64 2] 999000}
             :world/entities
             {500 {:entity/type zombie :entity/pos [3.0 64.0 3.0] :entity/health 12.0}
              501 {:entity/type blocks/item-entity-type :entity/pos [4.0 64.0 4.0] :entity/item 5}
              502 {:entity/type bat :entity/pos [5.0 70.0 5.0]}})))

(deftest the-model-joins-holding-what-the-server-held
  ;; issue #299: the model joined mid-play, no handshake: the bot's window 0 at the slots window
  ;; 0 shows it, its health, food and held place, the blocks the server changed (never a guess),
  ;; the mobs and items it carries under the bot's ids (a pickup counts only for the bot's own
  ;; entity id), what it does not carry told gone, and only the columns near the bot
  (let [far (world/column-bytes {})
        s (real/model {:world (bot-world)
                       :columns {[0 0] (world/column-bytes {}) [1 0] far [40 40] far}}
                      {:seed 1 :difficulty :normal})
        inv (inventory/layouts :inventory)]
    (is (= :play (:sim/phase s)))
    (is (= 1000000 (:sim/now s)))
    (is (= [8.5 64.0 8.5] (:sim/player-pos s)))
    (is (= {(inventory/slot [:hotbar 0]) {:item 5 :count 3}
            (inventory/slot [:main 0]) {:item 6 :count 1}
            (:armour inv) {:item 7 :count 1}
            (:offhand inv) {:item 8 :count 2}
            1 {:item 9 :count 1}}
           (:sim/inv s)))
    (is (= [2 7 15.0 17 2.5]
           ((juxt :sim/held :sim/state-id :sim/health :sim/food :sim/saturation) s)))
    (is (= 389 (sim/player-eid-of s)))
    (is (= {[1 64 1] 1} (:sim/placed s)) "the server's word, never the bot's guess")
    (is (= #{[0 0] [1 0]} (set (keys (:sim/columns s))))
        "the columns near the bot, not the far one")
    (is (= #{[0 0]} (:sim/view s)) "the client holds what the bot held")
    (is (= {:type :zombie :pos [3.0 64.0 3.0] :health 12.0} (get-in s [:sim/mobs 500])))
    (is (= {:item 5 :count 1} (get-in s [:sim/items 501 :item])))
    (is (= [{:packet/name :remove-entities :ids [502]}] (:sim/out s)) "the bat it does not carry")
    (is (< 502 (:sim/next-eid s)) "its own entities never take a tracked id")
    (is (= real/noon (:sim/day-time s)) "no undead under the sky says night")
    (let [s' (sim/step s {:sim/kind :tick :sim/now 1000050})]
      (is (= :play (:sim/phase s')) "it ticks on from the bot's clock"))))

(deftest a-moment-is-kept-only-where-the-model-can-join
  ;; a container's window lives in the server the model never was: no moment with one open, or
  ;; with something on the cursor, before the bot is placed or after it died
  (let [w (bot-world)]
    (is (real/joinable? w))
    (is (not (real/joinable? (assoc w :window/open {:window/id 3 :window/menu-type 12}))))
    (is (not (real/joinable? (assoc w :window/cursor {:item 5 :count 1}))))
    (is (not (real/joinable? (assoc w :player/dead? true))))
    (is (not (real/joinable? (dissoc w :plan/status))) "before its goals came")))

(deftest a-dimension-change-drops-the-columns-sent-before-it
  ;; the columns the model is joined with are the bot's dimension's: a respawn into the Nether
  ;; drops the overworld's, as the bot drops its own
  (let [acc {:world (assoc (bot-world) :player/dimension dimension/overworld)
             :columns {[0 0] (world/column-bytes {})}
             :moments []}
        after (real/fold-step acc
                              (fx/packet
                               (assoc fx/respawn :dimension dimension/nether :dimension-type 3)))]
    (is (= dimension/nether (:player/dimension (:world after))))
    (is (= {} (:columns after)))
    (is (= [[0 0]]
           (keys (:columns (real/fold-step acc
                                           (fx/packet {:packet/name :level-chunk-with-light
                                                       :x 0
                                                       :z 0
                                                       :heightmaps []
                                                       :data (world/column-bytes {})}))))))))

(deftest a-portal-at-the-moment-leads-to-the-real-nether
  ;; race 38929353563 (issue #299): Clj_r17 and Clj_r05 failed in the Nether three seconds after
  ;; arriving, so their moments stood at the portal; the model joined there sends the bot to the
  ;; Nether the recording holds (near where the bot arrived), never to a copy of the overworld
  (let [nether-column (world/column-bytes {} dimension/nether)
        folded {:world (assoc (bot-world) :player/dimension dimension/nether)
                :columns {[0 0] nether-column [40 40] nether-column}
                :nether-at [8.5 64.0 8.5]}
        m {:world (assoc (bot-world) :player/dimension dimension/overworld)
           :columns {[0 0] (world/column-bytes {})}}
        nether (real/nether-of folded m)
        s (sim/arrive (real/model m {:seed 1 :nether nether}))]
    (is (= #{[0 0]} (set (keys (:columns nether)))) "the Nether near the arrival")
    (is (= [8.5 64.0 8.5] (:sim/player-pos s)))
    (is (= 0 (:chunk/min-y (get (:sim/decoded s) [0 0]))) "the Nether's column, decoded on arrival")
    (is (= :stone (blocks/name-of (sim/block-at s [8 10 8]))))
    (is (empty? (:sim/mobs s)) "the overworld's zombie stays behind")
    (is (nil? (real/nether-of folded (assoc-in m [:world :player/dimension] dimension/nether)))
        "a moment already in the Nether goes nowhere new")))
