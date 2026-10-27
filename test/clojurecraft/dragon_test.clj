(ns clojurecraft.dragon-test
  "The dragon (issue #267): a crystal's approach beside its spike, the planner's pillar, wall,
   strike and way down, the climb's jump and block, the win as the server says it, the wait
   behind the sitting dragon's head and the way round it, every look away from an enderman's
   eyes, the meal in a lull, and the whole bot against the model's End breaking a crystal and
   killing the perched dragon, from behind its head when it faces the way in."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.dragon :as dragon]
            [clojurecraft.end :as end]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
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
      (is (= {:intent/kind :dig-down} (at (merge s (pillar [8 5] 64 64)) [8.5 65.0 5.5] {}))
          "one block left under the feet: that one too")
      (is (nil? (at s [8.5 64.0 5.5] {})) "on the ground: down"))
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
        "no enderman: as it was")
    (is (= [(first at-head) 90.0]
           (:control/look (:player/controls (dragon/looks-away (assoc w :player/look at-head)))))
        "no look set this tick: the standing look is turned down too")))

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
    (let [shown (told w {:packet/name :boss-event :uuid bar :action 0})
          updated (told shown {:packet/name :boss-event :uuid bar :action 2 :health 0.75})]
      (is (= #{bar} (:world/boss-bars shown)))
      (is (= 0.75 (get-in updated [:world/boss-health bar])) "boss bar health update is recorded")
      (is (nil? (:world/dragon-dead? (told shown
                                           {:packet/name :remove-entities :ids [dragon-eid]})))
          "removed with its boss bar still shown")
      (is (= #{} (:world/boss-bars (told shown {:packet/name :boss-event :uuid bar :action 1}))))
      (is (= {} (:world/boss-health (told updated {:packet/name :boss-event :uuid bar :action 1})))
          "boss bar removal clears its health"))
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
    (is (= (dragon/shelter-walk [7 64 0])
           (dragon/toward-dragon (standing {} [3.5 64.0 0.5] {} (merge d cloud))))
        "in it: the walk to the nearest cell out of its reach (escape)")
    (is (= {:intent/kind :back-off :intent/target 50}
           (dragon/toward-dragon
            (assoc (standing {} [3.5 64.0 0.5] {} (merge d cloud)) :world/chunks {})))
        "no ground loaded to walk to: away")))

(deftest a-breath-on-the-way-to-the-stand-is-waited-out-where-the-feet-are
  ;; gym dragon-d runs 1 and 3: the walk back to the stand went through a fireball's cloud lying
  ;; between (grown to 5.7) and died in it, six a second
  (let [d {dragon-eid {:entity/type (mob/type-id :ender_dragon) :entity/pos [0.5 90.0 0.5]}}
        cloud
        (fn [pos]
          {50 {:entity/type (mob/type-id :area_effect_cloud) :entity/pos pos :entity/radius 3.0}})
        w (fn [c] (standing {} [12.5 64.0 0.5] {} (merge d c)))]
    (is (= {:entity/radius 3.0}
           (entity/metadata-facts (mob/type-id :area_effect_cloud) {entity/radius-index 3.0}))
        "a cloud's radius from its metadata")
    (is (= {} (entity/metadata-facts (mob/type-id :zombie) {entity/radius-index 3.0}))
        "index 8 is a cloud's radius only")
    (is (= {:intent/kind :perch :intent/target dragon-eid}
           (dragon/toward-dragon (w (cloud [7.5 64.0 0.5]))))
        "the cloud on the straight way: the perch from here")
    (is (= (dragon/standing-walk [2 64 0]) (dragon/toward-dragon (w (cloud [7.5 64.0 9.5]))))
        "the cloud five off the way: the walk")
    (is (true? (dragon/breath-on-way? (w (cloud [7.5 64.0 4.9])) [12.5 64.0 0.5] [2.5 64.0 0.5]))
        "its reach, radius and margin, counts")
    (is (plan/interrupts? (w (cloud [10.5 64.0 0.5])) {:goal/plan :dragon} {:intent/kind :walk})
        "a breath near ends a walk")
    (is (not (plan/interrupts? (w (cloud [10.5 64.0 0.5]))
                               {:goal/plan :dragon}
                               {:intent/kind :back-off}))
        "never the back-off from it")
    (is (not
         (plan/interrupts? (w (cloud [7.5 64.0 9.5])) {:goal/plan :dragon} {:intent/kind :walk}))
        "none near: the walk goes on")))

;; ---------------------------------------------------------------- the sitting dragon's head

(deftest the-dragon-turns-by-the-servers-word
  (let [added (told (standing {} [0.5 64.0 0.5] {} {})
                    {:packet/name :add-entity
                     :entity-id 41
                     :uuid (random-uuid)
                     :type (mob/type-id :ender_dragon)
                     :x 0.5
                     :y 66.0
                     :z 0.5
                     :vel [0.0 0.0 0.0]
                     :pitch 0
                     :yaw 64
                     :head-yaw 64
                     :data 0})
        yaw #(get-in % [:world/entities 41 :entity/yaw])]
    (is (= 90.0 (yaw added)) "a quarter turn, in 1/256 turns")
    (is (= -90.0
           (yaw
            (told
             added
             {:packet/name :move-entity-rot :entity-id 41 :yaw -64 :pitch 0 :on-ground false}))))
    (is (= 0.0
           (yaw (told added
                      {:packet/name :move-entity-pos-rot
                       :entity-id 41
                       :dx 0
                       :dy 0
                       :dz 0
                       :yaw 0
                       :pitch 0
                       :on-ground false}))))
    (is (= 90.0
           (yaw (told added
                      {:packet/name :move-entity-pos-rot :entity-id 41 :dx 4096 :dy 0 :dz 0})))
        "a move recorded before the spec grew: the yaw kept")
    (is (= 45.0
           (yaw (told added
                      {:packet/name :entity-position-sync
                       :entity-id 41
                       :x 0.5
                       :y 66.0
                       :z 0.5
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 45.0
                       :pitch 0.0
                       :on-ground false}))))
    (is (= 100.0
           (yaw (told added
                      {:packet/name :teleport-entity
                       :entity-id 41
                       :x 0.0
                       :y 0.0
                       :z 0.0
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 10.0
                       :pitch 0.0
                       :flags 15
                       :on-ground false})))
        "a teleport's yaw relative by its flag")))

(defn near?
  "Are two [x z] pairs equal to within a billionth?"
  [a b]
  (every? #(< (abs %) 1.0e-9) (map - a b)))

(deftest the-sitting-dragons-head-is-along-its-yaw
  (is (near? [0.0 1.0] (mob/facing 180.0)) "EnderDragon.aiStep's head part at (sin yaw, −cos yaw)")
  (is (near? [1.0 0.0] (mob/facing 90.0)))
  (is (near? [2.0 -1.0] (mob/ahead [0.5 66.0 0.5] 90.0 [2.5 64.0 -0.5]))
      "along it, and across it to the right: negative")
  (is (near? [2.5 -0.5] (mob/beside [0.5 66.0 0.5] 90.0 [2.0 -1.0])) "and back")
  (is (mob/bitten? [0.5 66.0 0.5] 90.0 [2.5 64.0 0.5]) "two out in front: under the neck")
  (is (not (mob/bitten? [0.5 66.0 0.5] 90.0 [-1.5 64.0 0.5])) "two out behind: the tail")
  (is (not (mob/bitten? [0.5 66.0 0.5] 90.0 [2.5 64.0 4.0])) "beside, past the neck's box")
  (is (not (mob/bitten? [0.5 66.0 0.5] 90.0 [2.5 58.0 0.5])) "far under it"))

(defn round-fountain
  "A bot at pos seeing the entities, over the floor of the four chunks round the fountain."
  [pos entities]
  (assoc (standing {} pos {} entities)
         :world/chunks
         (into {} (for [cx [-1 0] cz [-1 0]] [[cx cz] (world/column {})]))))

(defn sitting
  "The dragon sitting on the fountain in its scanning turn (phase 6) facing yaw."
  [yaw]
  {dragon-eid {:entity/type (mob/type-id :ender_dragon)
               :entity/pos [0.5 66.0 0.5]
               :entity/phase 6
               :entity/yaw yaw}})

(defn attacking
  "The dragon sitting on the fountain about to breathe (phase 7), its yaw held, facing yaw."
  [yaw]
  (assoc-in (sitting yaw) [dragon-eid :entity/phase] 7))

(defn cloud
  "A breath cloud of radius r at pos, as entity 50."
  [pos r]
  {50 {:entity/type (mob/type-id :area_effect_cloud) :entity/pos pos :entity/radius r}})

(deftest the-wait-is-behind-the-sitting-dragons-head
  ;; gym dragon-e run 2 and dragon-f run 2: slain at the stand two out in front of the sitting
  ;; dragon's head, four touches of 6 in two seconds
  (let [at #(dragon/toward-dragon (round-fountain %1 %2))]
    (is (= (dragon/standing-walk [-2 64 0]) (at [-12.5 64.0 0.5] (sitting 90.0)))
        "behind it, the stand behind")
    (is (= (dragon/standing-walk [-2 64 0])
           (at [-12.5 64.0 0.5] (assoc-in (sitting 270.0) [dragon-eid :entity/phase] 0)))
        "flying: the nearest stand")
    (testing "facing the bot: round its head to the stand behind it"
      (is (= (dragon/standing-walk [-9 64 -5]) (at [-12.5 64.0 0.5] (sitting 270.0)))
          "out beside it first, short of its reach")
      (is (= (dragon/standing-walk [2 64 -5]) (at [-8.5 64.0 -4.5] (sitting 270.0)))
          "then along beside it, past its body")
      (is (= (dragon/standing-walk [2 64 0]) (at [2.5 64.0 -4.5] (sitting 270.0)))
          "then in, behind it"))
    (is (= {:intent/kind :perch :intent/target dragon-eid} (at [2.5 64.0 0.5] (sitting 270.0)))
        "there, the perch")
    (let [perch #(:plan/intent (intent/run (round-fountain %1 %2)
                                           {:intent/kind :perch :intent/target dragon-eid}
                                           {:event/kind :tick :event/now 1000 :event/rand 0.5}))]
      (is (intent/done? (perch [-1.5 64.0 0.5] (attacking 270.0)))
          "its head turned onto the feet: the perch hands back")
      (is (not (intent/done? (perch [2.5 64.0 0.5] (sitting 270.0)))) "behind it: on")
      (let [clouded (assoc (sitting 90.0)
                           50
                           {:entity/type (mob/type-id :area_effect_cloud)
                            :entity/pos [0.5 64.0 0.5]
                            :entity/radius 3.0})]
        (is (= {:intent/kind :perch :intent/target dragon-eid} (at [7.5 64.0 0.5] clouded))
            "every stand under a breath: the perch where the feet are, never out of the basin")
        (is (not (intent/done? (perch [7.5 64.0 0.5] clouded)))
            "and with no stand to go to it holds")))
    (is
     (= (dragon/standing-walk [2 64 0]) (at [-1.5 64.0 0.5] (attacking 270.0)))
     "the head turned onto the feet in the basin: straight to the stand behind, low under it
         (gym dragon2-a runs 1 and 3 walked out up the rim into the neck)")
    (is (plan/interrupts? (round-fountain [12.5 64.0 0.5] (sitting 90.0))
                          {:goal/plan :dragon}
                          {:intent/kind :walk :intent/target [-2 64 0]})
        "a walk whose way goes under the head ends")
    (is (not (plan/interrupts? (round-fountain [4.5 64.0 0.5] (sitting 90.0))
                               {:goal/plan :dragon}
                               {:intent/kind :walk :intent/target [4 64 5]}))
        "never the walk out from under it")
    (is (not (plan/interrupts? (round-fountain [-0.5 64.0 -1.5] (sitting 90.0))
                               {:goal/plan :dragon}
                               {:intent/kind :walk :intent/target [2 64 0]}))
        "nor a walk in the basin")))

(deftest the-bot-keeps-the-basins-floor-while-the-dragon-sits-or-lands
  ;; every head death of gym dragon2-a and dragon2-b was a bot up on the basin's rim (a way
  ;; round, a back-off from a breath, the landing dragon's head) while the bot behind the body on
  ;; the floor struck twenty times unhurt
  (let [at #(dragon/toward-dragon (round-fountain %1 %2))
        landing (assoc-in (sitting 270.0) [dragon-eid :entity/phase] 3)
        flying (assoc-in (sitting 270.0) [dragon-eid :entity/phase] 0)
        perch #(:plan/intent (intent/run (round-fountain %1 %2)
                                         {:intent/kind :perch :intent/target dragon-eid}
                                         {:event/kind :tick :event/now 1000 :event/rand 0.5}))
        cut? #(plan/interrupts? (round-fountain %1 %2) {:goal/plan :dragon} %3)]
    (testing "the landing dragon's head is where a sitting one's is"
      (is (dragon/grounded? (get landing dragon-eid)))
      (is (dragon/grounded? (get (sitting 270.0) dragon-eid)))
      (is (not (dragon/grounded? (get flying dragon-eid))))
      (is (dragon/grounded? {:entity/pos [0.5 66.0 0.5]}) "by place when no phase is said")
      (is (= (dragon/standing-walk [-9 64 -5]) (at [-12.5 64.0 0.5] landing))
          "from outside: round its head to the stand behind")
      (is (not (dragon/lull? (round-fountain [20.5 64.0 0.5] landing))) "no lull to eat in")
      (is (dragon/lull? (round-fountain [20.5 64.0 0.5] flying))))
    (testing "a breath near the feet in the basin, the dragon grounded"
      (let [reaching (merge (attacking 270.0) (cloud [0.5 64.0 4.5] 1.0))]
        (is (= (dragon/shelter-walk [2 64 0]) (at [0.5 64.0 2.5] reaching))
            "the walk to the stand behind, out of its reach, never the back-off up the rim")
        (is (not (cut? [0.5 64.0 2.5] reaching (dragon/shelter-walk [2 64 0])))
            "which the breath does not end")
        (is (cut? [0.5 64.0 2.5] reaching {:intent/kind :walk :intent/target [0 64 3]})
            "a walk to a cell in its reach ends"))
      (let [held (merge (attacking 270.0) (cloud [2.5 64.0 3.4] 1.5))]
        (is (= {:intent/kind :perch :intent/target dragon-eid} (at [2.5 64.0 0.5] held))
            "no stand out of its reach, the feet out of the breath itself: hold")
        (is (dragon/holds? (round-fountain [2.5 64.0 0.5] held)))
        (is (not (intent/done? (perch [2.5 64.0 0.5] held))) "the perch holds")
        (is (not (cut? [2.5 64.0 0.5] held {:intent/kind :perch :intent/target dragon-eid})))
        (let [fled (fn [pos es]
                     (let [w (round-fountain pos es)
                           {:intent/keys [kind for target]} (dragon/toward-dragon w)]
                       (and (= [:walk :shelter] [kind for])
                            (not (dragon/breathed? w target))
                            (< (physics/horizontal-distance pos (physics/centre target)) 4.0)
                            target)))]
          (is
           (= [4 64 -2] (fled [2.5 64.0 0.5] (merge (attacking 270.0) (cloud [2.5 64.0 1.5] 1.5))))
           "in the breath itself: the walk to the nearest cell out of its reach behind the
               head, never straight away into the pillar (gym dragon3-a run 2)")
          (is (fled [2.5 64.0 0.5] (merge flying (cloud [2.5 64.0 3.4] 1.5)))
              "the dragon flying: the walk out of its reach")
          (is (fled [7.5 64.0 0.5] (merge (attacking 270.0) (cloud [7.5 64.0 2.5] 1.0)))
              "outside the basin: the same")
          (is (not (cut? [2.5 64.0 0.5]
                         (merge (attacking 270.0) (cloud [2.5 64.0 1.5] 1.5))
                         (dragon/shelter-walk [4 64 -2])))
              "which the breath does not end"))))
    (testing "the scanning turn is held still through"
      (is (= {:intent/kind :perch :intent/target dragon-eid} (at [-1.5 64.0 0.5] (sitting 270.0)))
          "before its head as it scans: the perch where the feet are")
      (is (not (intent/done? (perch [-1.5 64.0 0.5] (sitting 270.0)))) "which holds")
      (is (= {:intent/kind :perch :intent/target dragon-eid} (at [0.5 64.0 2.5] (sitting 270.0)))
          "beside it as it scans: no new stand")
      (is (= (dragon/standing-walk [2 64 0]) (at [0.5 64.0 2.5] (attacking 270.0)))
          "its yaw held: the stand behind"))
    (testing
      ":defend leaves every hostile to the row while a breath reaches or the dragon is grounded"
      (let [angry {9 {:entity/type (mob/type-id :enderman)
                      :entity/pos [4.5 64.0 0.5]
                      :entity/creepy? true}}
            circling {dragon-eid {:entity/type (mob/type-id :ender_dragon)
                                  :entity/pos [20.5 80.0 0.5]
                                  :entity/phase 0}}
            w (fn [es]
                (assoc (round-fountain [2.5 64.0 0.5] es)
                       :player/dimension dimension/end
                       :plan/goals #{:dragon}))
            breathed (w (merge circling angry (cloud [2.5 64.0 3.4] 1.5)))
            perched (w (merge (attacking 270.0) angry))
            quiet (w (merge circling angry))]
        (is (contains? (combat/answering breathed :dragon) :enderman))
        (is (empty? (combat/threats breathed)) "the back-off from the breath comes first")
        (is (contains? (combat/answering perched :dragon) :enderman)
            "grounded dragon: stay in the basin")
        (is (empty? (combat/threats perched)))
        (is (= #{:ender_dragon} (combat/answering quiet :dragon))
            "circling and no breath: the dragon alone")
        (is (= #{9} (set (keys (combat/threats quiet)))) "and the enderman is fought")
        (let [hooded (assoc quiet
                            :player/inventory
                            {39 {:item (recipe/item-id :carved_pumpkin) :count 1}})]
          (is (empty? (combat/threats (assoc hooded :plan/goals #{:dragon})))
              "the pumpkin worn: an unprovoked enderman is not a threat")
          (is (= #{9}
                 (set (keys
                       (combat/threats
                        (assoc hooded :plan/goals #{:dragon} :player/hurt-at 10 :time/tick 15)))))
              "when hit by an enderman through the pumpkin, it is a threat at once"))))))

(deftest every-look-of-the-dragon-row-is-away-from-an-endermans-eyes
  ;; gym dragon-f run 3: three endermen stared at in turn on the walks to and from the stand
  (let [w (standing {}
                    [0.5 64.0 0.5]
                    {}
                    {9 {:entity/type (mob/type-id :enderman) :entity/pos [6.5 64.0 0.5]}})
        [yaw :as at-head] (physics/look-at (game/eye w) (mob/head :enderman [6.5 64.0 0.5]))
        walking
        (assoc w
               :player/controls {:control/forward? true :control/yaw yaw :control/look at-head}
               :plan/intent {:intent/kind :walk :intent/target [9 64 0] :intent/goal :dragon})
        table [{:goal/id :dragon :goal/plan :dragon} {:goal/id :wood}]]
    (is (= {:control/forward? true :control/yaw yaw :control/look [yaw 90.0]}
           (:player/controls (plan/steered walking table)))
        "a walk of the dragon row: straight down, the way kept")
    (is (= (:player/controls walking)
           (:player/controls (plan/steered (assoc-in walking [:plan/intent :intent/goal] :wood)
                                           table)))
        "another row's: as it was")))

(deftest a-carved-pumpkin-held-is-worn-in-the-end-before-anything-else
  ;; gym chain-a3's dragon row: three runs slain by endermen; a look through a worn carved
  ;; pumpkin never provokes one, so the End's rows put a held one on before walking anywhere
  (let [hood {:item (recipe/item-id :carved_pumpkin) :count 1}
        helmet {:item (recipe/item-id :iron_helmet) :count 1}
        end #(assoc (standing {} [0.5 64.0 0.5] % {}) :player/dimension dimension/end)]
    (is (= {:intent/kind :wear :intent/item :carved_pumpkin}
           (dragon/wear-pumpkin (end {5 hood 39 helmet})))
        "held, a helmet worn: swapped on")
    (is (nil? (dragon/wear-pumpkin (end {39 hood}))) "worn already: nothing")
    (is (nil? (dragon/wear-pumpkin (end {39 helmet}))) "none held: nothing")
    (is (= {:intent/kind :wear :intent/item :carved_pumpkin}
           (dragon/wear-pumpkin (assoc (end {39 helmet}) :window/cursor hood)))
        "left on the cursor by a wear that failed: worn still")
    (is (= {:intent/kind :wear :intent/item :carved_pumpkin}
           (plan/next-intent (end {5 hood}) {:goal/plan :crystals} plan/goals))
        "the crystals row first")
    (is (= {:intent/kind :wear :intent/item :carved_pumpkin}
           (plan/next-intent (end {5 hood}) {:goal/plan :dragon} plan/goals))
        "the dragon row first")
    (is
     (= {:plan/wait :other-dimension}
        (plan/next-intent (standing {} [0.5 64.0 0.5] {5 hood} {}) {:goal/plan :dragon} plan/goals))
     "outside the End: never worn")
    (let [w (assoc (end {39 hood})
                   :player/controls {:control/look [10.0 -20.0]}
                   :world/entities
                   {9 {:entity/type (mob/type-id :enderman) :entity/pos [6.5 64.0 0.5]}})]
      (is (= [10.0 -20.0] (:control/look (:player/controls (dragon/looks-away w))))
          "a look not at an enderman's head is kept"))))

(deftest hurt-under-retreat-health-the-dragon-is-not-fought
  ;; gym chain-w3's dragon runs 2 and 3: the perch fought on at ten, then four and a
  ;; half health, and the dragon slew both; under combat/retreat-health the row eats,
  ;; shelters or waits, and a running perch ends at once
  (let [steak {:item (recipe/item-id :cooked_beef) :count 4}
        hurt (fn [food inv]
               (assoc (round-fountain [2.5 64.0 0.5] (sitting 270.0))
                      :player/health 9.0
                      :player/food food
                      :player/inventory inv))]
    (is (= {:intent/kind :eat :intent/item :cooked_beef} (dragon/toward-dragon (hurt 10 {0 steak})))
        "food held and the bar short: the meal, not the perch")
    (is (not= :perch (:intent/kind (dragon/toward-dragon (hurt 20 {0 steak})))))
    (is (not= :perch (:intent/kind (dragon/toward-dragon (hurt 20 {}))))
        "nothing to eat: shelter or the wait, still never the perch")
    (is (plan/interrupts? (hurt 20 {}) {:goal/plan :dragon} {:intent/kind :perch})
        "a running perch ends under the line")
    (let [perching
          (assoc (hurt 20 {}) :plan/intent {:intent/kind :perch :intent/target dragon-eid})]
      (is (intent/done? (:plan/intent (intent/run
                                       perching
                                       (:plan/intent perching)
                                       {:event/kind :tick :event/now 1000 :event/rand 0.5})))
          "perch intent yields at once under retreat health"))
    (let [sheltered (assoc (hurt 20 {}) :player/pos [6.5 64.0 0.5])]
      (is (= {:plan/wait :low-health} (dragon/toward-dragon sheltered))
          "in shelter outside the basin: waiting for health back, not walking"))))

(deftest an-attacker-enderman-is-answered-when-pumpkin-worn-and-dragon-grounded
  ;; gym chain-w7 run 2 (at 2a3adb1): dragon grounded, carved pumpkin worn,
  ;; an enderman struck the bot down to 2.7 health because answering :dragon
  ;; suppressed all hostiles when grounded and threats excluded answered-kinds.
  ;; When damaged by an attacker or under retreat-health, the enderman is answered.
  (let [enderman {:entity/type (mob/type-id :enderman) :entity/pos [1.5 64.0 0.5]}
        w (-> (round-fountain [0.5 64.0 0.5] (sitting 270.0))
              (assoc :player/inventory {39 {:item (recipe/item-id :carved_pumpkin) :count 1}}
                     :world/entities (merge (sitting 270.0) {309 enderman})
                     :player/dimension dimension/end
                     :player/health 8.0
                     :player/attacker 309
                     :player/hurt-at 100
                     :time/tick 110))]
    (is (not (contains? (combat/answering w :dragon) :enderman))
        "under retreat health, answering :dragon does not suppress hostiles")
    (is (contains? (combat/threats w) 309)
        "an enderman attacking the bot is a threat even when carved pumpkin is worn")
    (is (= {:intent/kind :fight :intent/target 309 :intent/to-the-end? true}
           (plan/next-intent w {:goal/id :defend :goal/plan :defend} plan/goals))
        "defend fights the attacking enderman to the end")))

(deftest holds-in-basin-when-breath-cloud-is-outside-actual-radius
  ;; gym chain-w8: prematurely breaking holds? on breath clouds outside the basin drove
  ;; the bot up the rim into the sitting dragon's head and wings. holds? holds as long as
  ;; the breath cloud has not engulfed the feet.
  (let [cloud {:entity/type (blocks/entity-types :area_effect_cloud)
               :entity/pos [6.5 64.0 0.5]
               :entity/radius 5.7}
        w (-> (round-fountain [0.5 64.0 0.5] (sitting 270.0))
              (assoc :world/entities (merge (sitting 270.0) {258 cloud})))]
    (is (not (dragon/in-breath? w)) "feet outside cloud radius is not in-breath")
    (is (dragon/holds? w) "the bot holds in the basin rather than fleeing up the rim")))

(deftest when-dragon-attacks-the-bot-defend-never-claims-it-and-recover-takes-over
  ;; gym chain-w8: dragon struck the bot down to 6.4 health; dragon must never be claimed by
  ;; :defend as a threat (combat/threats excludes :ender_dragon), so the dragon row stays
  ;; in control and recover takes over when health is under combat/retreat-health.
  (let [w (-> (round-fountain [0.5 64.0 0.5] (sitting 270.0))
              (assoc :player/dimension dimension/end
                     :player/health 6.4
                     :player/food 20
                     :player/attacker 40
                     :player/hurt-at 100
                     :time/tick 110))]
    (is (empty? (combat/threats w)) "the dragon is never a threat in combat/threats")
    (is
     (contains? #{:walk :low-health}
                (let [i (dragon/toward-dragon w)] (or (:intent/kind i) (:plan/wait i))))
     "dragon row executes recover (shelter walk or wait :low-health), never :fight or :back-off from defend")))

(deftest escape-never-paths-through-the-sitting-dragons-head-or-bite-area
  ;; gym chain-w8 run 3: escaping from [4 63 -9] toward [7 62 -3] crossed the dragon's head
  ;; and bite path (bite-on-way?), dealing fatal contact damage in 0.35 s.
  ;; escape must reject cells whose straight way crosses before the sitting dragon's head.
  (let [d (sitting 270.0)
        w (-> (round-fountain [4.5 63.0 -9.5] d)
              (assoc :player/dimension dimension/end))]
    (when-let [flight (dragon/escape w)]
      (let [[_ dragon-ent] (dragon/dragon w)]
        (is (not (dragon/bite-on-way? dragon-ent (:player/pos w) (physics/centre flight)))
            "escape destination straight line never passes before the dragon's head")))))

(deftest when-stands-are-nil-outside-basin-toward-dragon-never-issues-perch
  ;; gym chain-w8 run 2: at [3 63 -8] on the rim, stands in the basin were nil;
  ;; toward-dragon must never issue :perch from outside the basin when stand is nil.
  (let [cloud {:entity/type (blocks/entity-types :area_effect_cloud)
               :entity/pos [0.5 64.0 0.5]
               :entity/radius 4.0}
        w (-> (round-fountain [3.5 63.0 -8.5] (sitting 270.0))
              (assoc :player/dimension dimension/end
                     :world/entities (merge (sitting 270.0) {258 cloud})))]
    (is (not (dragon/in-basin? w)) "player is outside the basin")
    (is (nil? (dragon/fountain-stand w)) "fountain stands are covered by breath")
    (let [i (dragon/toward-dragon w)]
      (is (not= :perch (:intent/kind i))
          "toward-dragon never perches outside the basin with nil stand"))))

(deftest in-a-lull-the-bot-eats-for-health-back
  ;; the hand-off of gym dragon-f: sixteen steak held, the food between 14 and 18, no healing
  (let [steak {0 {:item (recipe/item-id :cooked_beef) :count 16}}
        w (assoc (standing {} [20.5 64.0 0.5] steak {})
                 :player/dimension dimension/end
                 :player/health 12.0
                 :player/food 16)]
    (is (= :cooked_beef (dragon/heal-food w)))
    (is (= {:intent/kind :eat :intent/item :cooked_beef}
           (plan/next-intent w {:goal/plan :heal} plan/goals)))
    (is (not (plan/done-by w {:goal/done? :healed?})))
    (is (nil? (dragon/heal-food (assoc w :player/food 18))) "healing by itself")
    (is (nil? (dragon/heal-food (assoc w :player/health 20.0))) "nothing to heal")
    (is (nil? (dragon/heal-food (assoc w :player/dimension dimension/overworld))) "the End only")
    (is (nil? (dragon/heal-food (assoc w :world/entities (sitting 90.0))))
        "the dragon on its perch")))

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

(deftest the-sitting-dragon-is-killed-from-behind-its-head-against-the-model
  ;; gym dragon-e run 2 and dragon-f run 2: slain in front of the sitting dragon's head; the
  ;; model's dragon faces the way the bot comes in (+x) and hurts what is before its head
  ;; (mob/bitten?), so the bot goes round it to the stand behind
  (let [[w sim] (run {:columns (end-columns (range -1 7) [-1 0] {})
                      :dragon [0.5 52.0 0.5]
                      :dragon-yaw 90.0
                      :crystals []}
                     {[:hotbar 0] {:item (recipe/item-id :iron_sword) :count 1}}
                     [:dragon])]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (true? (:world/dragon-dead? w)) "the server said it died")
    (is (= 20.0 (:sim/health sim)) "never under its head")
    (is (empty? (:sim/violations sim)))))

(deftest a-climb-reads-no-click-as-a-placement-and-refills-an-emptied-hand
  ;; issue #178, sim R1-11-5: a swap into the hand left its send time on the intent and the climb
  ;; failed :no-answer three seconds on; a stack used up in the hand failed it :not-held with
  ;; more cobblestone in the pack
  (let [intent {:intent/kind :pillar
                :intent/item :cobblestone
                :intent/top 66
                :intent/stage :climb
                :intent/since 1000
                :intent/status :active
                :intent/awaiting 5
                :intent/awaiting-window 0
                :intent/clicks [1]
                :intent/sent-at 1000}
        w (assoc (standing {} [8.5 64.0 8.5] cobblestone {}) :plan/intent intent)
        cleared (dragon/equipped w)
        placing (dragon/equipped (assoc-in w [:plan/intent :intent/under] [8 63 8]))
        packed (-> w
                   (assoc :player/inventory {20 {:item (recipe/item-id :cobblestone) :count 64}})
                   (assoc-in [:plan/intent :intent/sent-at] nil))]
    (is (nil? (get-in cleared [:plan/intent :intent/sent-at])) "no send time with nothing placed")
    (is (nil? (get-in cleared [:plan/intent :intent/awaiting])) "the click's wait cleared")
    (is (= 1000 (get-in placing [:plan/intent :intent/sent-at]))
        "a placement pending keeps its send time")
    (is (= :equip (get-in (dragon/climb packed (:plan/intent packed)) [:plan/intent :intent/stage]))
        "the block in the pack, back to the equip")
    (is (= :not-held
           (:intent/reason (:plan/intent (dragon/climb (assoc packed :player/inventory {})
                                                       (:plan/intent packed)))))
        "none anywhere: failed")))

(deftest the-platform-in-the-end-bridges-to-the-island-before-crystals
  (let [obs (blocks/default-state :obsidian)
        stone (blocks/default-state :end_stone)
        void (into {} (for [lx (range 16) lz (range 16) y (range 40 64)] [[lx y lz] blocks/air]))
        plat (into {} (for [x (range 98 103) z (range -2 3)] [[x 48 z] obs]))
        shore (into {} (for [x (range 60 73) z (range -2 3) y (range 48 53)] [[x y z] stone]))
        cols (into {}
                   (for [cx [3 4 5 6]]
                     [[cx 0]
                      (world/column (merge void
                                           (into {}
                                                 (for [[[x y z] id] (merge plat shore)
                                                       :when (= cx (math/floor-div x 16))]
                                                   [[(mod x 16) y (mod z 16)] id]))))]))
        base (-> (standing {} [100.5 49.0 0.5] cobblestone {})
                 (assoc :player/dimension dimension/end
                        :world/chunks cols
                        :time/now 5000
                        :plan/since 0
                        :plan/reached-after {:enter-end 4000}))]
    (is (dragon/off-island? base) "on the obsidian platform at x 100")
    (is (not (dragon/off-island? (assoc base :player/pos [70.5 53.0 0.5]))) "on the island")
    (is (= [72 53 0] (dragon/island-shore base)) "the easternmost island column")
    (is (= {:intent/kind :walk :intent/target [98 49 0] :intent/for :build}
           (dragon/toward-island base))
        "walks to the platform's western edge first")
    (let [edge (assoc base :player/pos [98.5 49.0 0.5])]
      (is (= {:intent/kind :pillar :intent/item :cobblestone :intent/top 53}
             (dragon/toward-island edge))
          "below the shore's y: pillar up to it first"))
    (let [high (assoc base :player/pos [98.5 53.0 0.5])]
      (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [97 52 0]}
             (dragon/toward-island high))
          "at the shore's y with air ahead: places floor west"))
    (is (not (plan/done-by base {:goal/done? :crystals-gone?}))
        "never done while still off the island or within 3s of entering the End")))
