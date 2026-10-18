(ns clojurecraft.dragon-test
  "The dragon (issue #267): a crystal's approach beside its spike, the planner's pillar, wall,
   strike and way down, the climb's jump and block, the win as the server says it, and the whole
   bot against the model's End breaking a crystal and killing the perched dragon."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.dragon :as dragon]
            [clojurecraft.end :as end]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

;; ---------------------------------------------------------------- a spike on a floor

(def obsidian (blocks/default-state :obsidian))
(def bedrock (blocks/default-state :bedrock))
(def cobble (blocks/default-state :cobblestone))

(defn spike
  "{[x y z] state} of a spike of radius r round column [x z] on a floor whose top is y0, its top
   layer at y0 + height - 1 and its bedrock over the middle (SpikeFeature: the cells with
   dx² + dz² <= r² + 1)."
  [[x z] r y0 height]
  (merge (into {}
               (for [dx (range (- r) (inc r))
                     dz (range (- r) (inc r))
                     :when (<= (+ (* dx dx) (* dz dz)) (inc (* r r)))
                     y (range y0 (+ y0 height))]
                 [[(+ x dx) y (+ z dz)] obsidian]))
         {[x (+ y0 height) z] bedrock}))

(def cobblestone {0 {:item (recipe/item-id :cobblestone) :count 64}})

(defn standing
  "A bot in play at pos over the blocks (the overworld's floor, stone up to 63), holding inv,
   seeing the entities."
  [blocks pos inv entities]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos pos
             :player/loaded? true
             :player/on-ground? true
             :player/held-slot 0
             :time/now 1000
             :player/inventory inv
             :world/entities entities)
      (assoc :world/chunks {[0 0] (world/column blocks)})))

(def crystal-at [8.5 71.0 8.5])        ; on the bedrock of a spike six high round [8 8] on the floor at 64

(def crystal {7 {:entity/type (mob/type-id :end_crystal) :entity/pos crystal-at}})

(deftest the-approach-stands-three-out-behind-a-wall
  (testing "a spike of radius 2: the pillar's top is the stand"
    (let [w (standing (spike [8 8] 2 64 6) [8.5 64.0 0.5] cobblestone crystal)]
      (is (= {:column [8 64 5] :top 70 :stand [8 70 5] :wall [[8 70 6] [8 71 6]]}
             (dragon/best-approach w crystal-at))
          "along the axis toward the feet, the first column out from the top layer")))
  (testing "a spike of radius 4: the pillar beside it, the stand on its top"
    (let [w (standing (spike [8 8] 4 64 6) [8.5 64.0 0.5] cobblestone crystal)]
      (is (= {:column [8 64 3] :top 70 :stand [8 70 5] :wall [[8 70 6] [8 71 6]]}
             (dragon/best-approach w crystal-at))))))

(defn pillar
  "{[x y z] cobblestone} of a pillar in column [x z] from y0 up to y1 inclusive."
  [[x z] y0 y1]
  (into {} (for [y (range y0 (inc y1))] [[x y z] cobble])))

(deftest the-planner-walks-pillars-walls-strikes-and-digs-down
  (let [s (spike [8 8] 2 64 6)
        at #(dragon/toward-crystals (standing %1 %2 cobblestone %3))]
    (is (= {:intent/kind :walk :intent/target [8 64 5] :intent/for :build}
           (at s [8.5 64.0 0.5] crystal))
        "on the ground, the walk to the pillar's foot")
    (is (= {:intent/kind :pillar :intent/item :cobblestone :intent/top 70}
           (at s [8.5 64.0 5.5] crystal))
        "at its foot, the pillar up to the spike's top")
    (is (= {:intent/kind :pillar :intent/item :cobblestone :intent/top 70}
           (at (merge s (pillar [8 5] 64 66)) [8.5 67.0 5.5] crystal))
        "half way up, on up")
    (let [up (merge s (pillar [8 5] 64 69))]
      (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [8 70 6]}
             (at up [8.5 70.0 5.5] crystal))
          "at the stand, a block between at the feet's height")
      (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [8 71 6]}
             (at (assoc up [8 70 6] cobble) [8.5 70.0 5.5] crystal))
          "then at the eye's")
      (is (= {:intent/kind :strike :intent/target 7}
             (at (assoc up [8 70 6] cobble [8 71 6] cobble) [8.5 70.0 5.5] crystal))
          "the wall up, the strike")
      (is (= {:intent/kind :dig-down} (at up [8.5 70.0 5.5] {})) "the crystal gone, the way down")
      (is (nil? (at (merge s (pillar [8 5] 64 64)) [8.5 65.0 5.5] {}))
          "one block left under the feet: down"))
    (testing "on a wide spike's top with the crystal gone, back to the pillar's top"
      (let [wide (merge (spike [8 8] 4 64 6) (pillar [8 3] 64 69))]
        (is (= {:intent/kind :walk :intent/target [8 70 3] :intent/for :build}
               (at wide [8.5 70.0 5.5] {})))
        (is (= {:intent/kind :walk :intent/target [8 70 5] :intent/for :build}
               (at wide [8.5 70.0 3.5] crystal))
            "on the pillar's top the walk onto the spike: the pillar's own block is no spike")))
    (is (= {:plan/wait :no-blocks} (dragon/toward-crystals (standing s [8.5 64.0 5.5] {} crystal)))
        "nothing to pillar with")))

(deftest a-pillar-with-a-gap-under-it-is-still-the-way-down
  ;; gym dragon-c run 1: the fight's dragon smashed the pillar under the bot, which fell to a
  ;; stump with a gap under it and walked for the next crystal's foot from up there (:no-path)
  (let [gapped (merge (spike [8 8] 2 64 6) (pillar [8 5] 64 65) {[8 69 5] cobble})
        w (standing gapped [8.5 70.0 5.5] cobblestone {})]
    (is (dragon/on-pillar? w) "one pillar block under the feet, air under it")
    (is (= {:intent/kind :dig-down} (dragon/toward-crystals w)))))

(deftest the-wait-never-looks-an-enderman-in-the-eyes
  ;; gym dragon-c runs 2 and 3: the wait for the dragon followed it over the island's rim, stared
  ;; at an enderman there (its stared-at flag, then its target), and was slain four hits later
  (let [pos [6.5 64.0 0.5]
        w (standing {} [0.5 64.0 0.5] {} {9 {:entity/type (mob/type-id :enderman) :entity/pos pos}})
        at-head (physics/look-at (game/eye w) (mob/head :enderman pos))
        up [(first at-head) -60.0]]
    (is (= [(first at-head) 90.0] (dragon/look-away w at-head)) "into its eyes: straight down")
    (is (= up (dragon/look-away w up)) "over its head: as it was")
    (is (= at-head (dragon/look-away (assoc w :world/entities {}) at-head))
        "no enderman: as it was")))

(deftest the-climb-jumps-and-puts-the-block-in-once-clear
  (let [w (-> (standing {} [8.5 64.0 8.5] cobblestone {})
              (assoc :plan/intent
                     {:intent/kind :pillar
                      :intent/item :cobblestone
                      :intent/top 66
                      :intent/stage :climb
                      :intent/since 1000
                      :intent/status :active}))
        jumped (dragon/climb w (:plan/intent w))
        rising (dragon/climb (assoc jumped :player/pos [8.5 64.9 8.5] :player/on-ground? false)
                             (:plan/intent jumped))
        clear (dragon/climb (assoc jumped :player/pos [8.5 65.17 8.5] :player/on-ground? false)
                            (:plan/intent jumped))
        sent (filter #(= :use-item-on (:packet/name %)) (map :effect/packet (:bot/effects clear)))]
    (is (true? (get-in jumped [:player/controls :control/jump?])) "a jump from the ground")
    (is (= [8 64 8] (get-in jumped [:plan/intent :intent/under])) "the cell it leaves kept")
    (is (empty? (filter #(= :use-item-on (:packet/name (:effect/packet %))) (:bot/effects rising)))
        "nothing while the body is still in the cell")
    (is (= [{:pos [8 63 8] :face (packet/value :face :up)}]
           (map #(select-keys % [:pos :face]) sent))
        "clear of it, the block in against the top of the one under")
    (is (= 90.0 (second (get-in clear [:player/controls :control/look]))) "looking straight down")))

;; ---------------------------------------------------------------- the win

(def dragon-eid 40)

(defn with-dragon
  "A bot at [0.5 64 0.5] seeing the dragon at pos."
  [pos]
  (standing {}
            [0.5 64.0 0.5]
            {}
            {dragon-eid {:entity/type (mob/type-id :ender_dragon) :entity/pos pos}}))

(defn told
  "The world after the server sends pkt."
  [w pkt]
  (game/step w {:event/kind :packet :event/packet pkt}))

(def bar (random-uuid))

(deftest the-dragon-is-dead-by-the-servers-word
  (let [w (with-dragon [0.5 66.0 0.5])]
    (is (true? (:world/dragon-dead?
                (told w {:packet/name :entity-event :entity-id dragon-eid :status 3}))))
    (is (nil? (:world/dragon-dead?
               (told w {:packet/name :entity-event :entity-id dragon-eid :status 2})))
        "a hurt is not a death")
    (is (true? (:world/dragon-dead? (told w {:packet/name :remove-entities :ids [dragon-eid]})))
        "removed near the bot with no boss bar")
    (let [shown (told w {:packet/name :boss-event :uuid bar :action 0})]
      (is (= #{bar} (:world/boss-bars shown)))
      (is (nil? (:world/dragon-dead? (told shown
                                           {:packet/name :remove-entities :ids [dragon-eid]})))
          "removed with its boss bar still shown")
      (is (= #{} (:world/boss-bars (told shown {:packet/name :boss-event :uuid bar :action 1})))))
    (is (nil? (:world/dragon-dead? (told (with-dragon [200.5 80.0 0.5])
                                         {:packet/name :remove-entities :ids [dragon-eid]})))
        "removed out of sight: flown off, not dead")
    (let [won (told w {:packet/name :game-event :event 4 :param 1.0})]
      (is (true? (:world/dragon-dead? won)) "the credits")
      (is (some #(= {:packet/name :client-command :action 0} (:effect/packet %)) (:bot/effects won))
          "answered with the respawn"))))

(deftest the-dragon-perches-on-the-fountain
  (testing "by its phase when the server said it"
    (is (= {:entity/phase 6}
           (entity/metadata-facts (mob/type-id :ender_dragon) {entity/phase-index 6})))
    (is (= {} (entity/metadata-facts (mob/type-id :enderman) {entity/phase-index 6}))
        "index 16 is the dragon's phase only")
    (is (dragon/perched? {:entity/pos [2.5 66.0 -1.5] :entity/phase 6}))
    (is (not (dragon/perched? {:entity/pos [2.5 66.0 -1.5] :entity/phase 3}))
        "landing over the pillar: not yet sat"))
  (is (dragon/perched? {:entity/pos [2.5 66.0 -1.5]}))
  (is (not (dragon/perched? {:entity/pos [2.5 75.0 -1.5]})) "over y 70: flying")
  (is (not (dragon/perched? {:entity/pos [40.5 66.0 0.5]})) "away from the fountain"))

(deftest with-every-stand-under-a-breath-the-perch-is-waited-where-the-feet-are
  (let [d {dragon-eid {:entity/type (mob/type-id :ender_dragon) :entity/pos [0.5 90.0 0.5]}}
        cloud {50 {:entity/type (mob/type-id :area_effect_cloud) :entity/pos [0.5 64.0 0.5]}}]
    (is (= (dragon/standing-walk [2 64 0])
           (dragon/toward-dragon (standing {} [12.5 64.0 0.5] {} d)))
        "the stand in the basin beside the pillar")
    (is (= {:intent/kind :perch :intent/target dragon-eid}
           (dragon/toward-dragon (standing {} [12.5 64.0 0.5] {} (merge d cloud))))
        "the basin under a cloud: wait out here")
    (is (= {:intent/kind :back-off :intent/target 50}
           (dragon/toward-dragon (standing {} [3.5 64.0 0.5] {} (merge d cloud))))
        "in it: away")))

;; ---------------------------------------------------------------- against the model's End

(def floor
  "The End's floor: end stone up to y 47 and air from 48 to 63, by chunk-local cell."
  (into {}
        (for [x (range 16)
              z (range 16)
              y (range 40 64)]
          [[x y z] (if (< y 48) (blocks/default-state :end_stone) blocks/air)])))

(defn end-columns
  "{[cx cz] column} of the End's floor over the chunks cxs by czs, with extra (by world cell) in
   their chunks."
  [cxs czs extra]
  (into {}
        (for [cx cxs
              cz czs]
          [[cx cz]
           (world/column-bytes
            (merge floor
                   (into {}
                         (for [[[x y z] id] extra
                               :when (= [cx cz] [(math/floor-div x 16) (math/floor-div z 16)])]
                           [[(mod x 16) y (mod z 16)] id])))
            dimension/end)])))

(def through
  "The overworld's start: feet in a lit end portal, which takes the player to the End at once."
  (into {} (for [c (end/interior [8 64 8])] [c end/portal])))

(defn run
  "The whole bot against the model from the overworld's portal into the End (its columns, its
   dragon and crystals given) toward goals, holding inv; [world sim]."
  [end inv goals]
  (sim/run main/step
           (game/init fx/opts)
           (sim/init
            {:column (world/column-bytes through) :spawn [8.5 64.0 8.5] :inventory inv :end end})
           #(or (plan/done? %) (plan/failed? %))
           600000
           {:event/kind :go :go/goals goals}))

(def kit
  {[:hotbar 0] {:item (recipe/item-id :cobblestone) :count 64}
   [:hotbar 1] {:item (recipe/item-id :iron_pickaxe) :count 1}
   [:hotbar 2] {:item (recipe/item-id :iron_sword) :count 1}})

(deftest a-crystal-is-broken-from-behind-a-wall-against-the-model
  (let [[w sim] (run {:columns (end-columns [6] [-1 0] (spike [106 8] 2 48 6))
                      :crystals [[106.5 55.0 8.5]]}
                     kit
                     [:crystals])]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (not-any? #(= :end_crystal (:type %)) (vals (:sim/mobs sim))) "the crystal is gone")
    (is (= 20.0 (:sim/health sim)) "its blast never reached the bot")
    (is (= [:cobblestone :cobblestone]
           (map #(blocks/name-of (sim/block-at sim %)) [[106 54 6] [106 55 6]]))
        "the wall it struck from behind")
    (is (<= (second (:player/pos w)) 49.5) "back down the pillar")
    (is (empty? (:sim/violations sim)))))

(deftest the-perched-dragon-is-killed-against-the-model
  (let [[w sim] (run
                 {:columns (end-columns (range -1 7) [-1 0] {}) :dragon [0.5 52.0 0.5] :crystals []}
                 {[:hotbar 0] {:item (recipe/item-id :iron_sword) :count 1}}
                 [:dragon])
        [_ d] (first (filter #(= :ender_dragon (:type (val %))) (:sim/mobs sim)))]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (true? (:world/dragon-dead? w)) "the server said it died")
    (is (or (nil? d) (:dead-at d)) "dead in the model")
    (is (empty? (:sim/violations sim)))))
