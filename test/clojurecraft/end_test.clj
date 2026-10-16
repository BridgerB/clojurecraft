(ns clojurecraft.end-test
  "Eyes of ender, the end portal and the End in the server model (issue #260): the rules
   (end.clj) and the model throwing an eye toward the stronghold, lighting a portal from twelve
   frames, taking the player through it onto the platform, and a perched dragon its crystals heal."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

;; ---------------------------------------------------------------- the rules

(deftest a-frames-eye-is-four-states-below-it
  (is (= 9473 (end/frame-state false :north)) "the frame the game places: no eye, facing north")
  (is (= 9469 (end/frame-state true :north)))
  (is (= 9476 (end/frame-state false :east)))
  (is (end/eyed? (end/with-eye (end/frame-state false :west))))
  (is (not (end/eyed? (end/frame-state false :west))))
  (is (not (end/eyed? (blocks/default-state :stone)))))

(def middle [8 64 8])

(defn ring
  "{cell state} of the twelve frames round the portal at middle, eyes in the first n."
  [n]
  (into {}
        (map-indexed (fn [i [ox oz]] [[(+ 8 ox) 64 (+ 8 oz)] (end/frame-state (< i n) :north)])
                     end/ring-offsets)))

(deftest twelve-frames-with-eyes-light-the-interior
  (let [at #(get (ring 12) % blocks/air)]
    (is (= (set (end/interior middle)) (set (end/lit at [10 64 7]))))
    (is (nil? (end/lit #(get (ring 11) % blocks/air) [10 64 7])) "eleven light nothing")))

(deftest an-eye-flies-twelve-blocks-toward-a-far-stronghold-and-rises-eight
  (let [to (end/eye-target [0.0 65.0 0.0] [300.0 30.0 400.0])]
    (is (< (abs (- 12.0 (math/hypot (first to) (last to)))) 1e-9))
    (is (= 73.0 (second to)))
    (is (< (abs (- (math/atan2 400.0 300.0) (math/atan2 (last to) (first to)))) 1e-9)
        "along the line to the stronghold"))
  (is (= [5.0 30.0 5.0] (end/eye-target [0.0 65.0 0.0] [5.0 30.0 5.0])) "a near one is flown to"))

(deftest ten-spikes-on-a-circle-of-forty-two
  (is (= 10 (count end/spikes)))
  (is (every? #(<= 40.0 (math/hypot (first (:spike/center %)) (second (:spike/center %))) 43.0)
              end/spikes))
  (is (= [76 103] ((juxt first last) (map :spike/height end/spikes)))))

;; ---------------------------------------------------------------- on the model

(defn in-play
  "A model in play over blocks (on the stone floor), the player at point at holding inv, with opts
   for init."
  [blocks at inv opts]
  (-> (sim/init (merge {:column (world/column-bytes blocks) :spawn at :inventory inv} opts))
      (assoc :sim/phase :play :sim/player-pos at)))

(defn ticks
  "The model after n game ticks, 50 ms apart."
  [sim n]
  (reduce (fn [sim _] (sim/step sim {:sim/kind :tick :sim/now (+ (:sim/now sim) physics/tick-ms)}))
          sim
          (range n)))

(defn sends
  "The model after the client sends pkt."
  [sim pkt]
  (sim/step sim {:sim/kind :packet :sim/packet pkt}))

(defn said
  "Every packet named n the model has queued."
  [sim n]
  (filter #(= n (:packet/name %)) (:sim/out sim)))

(def eyes {[:hotbar 0] {:item (recipe/item-id :ender_eye) :count 12}})

(def throw-eye
  {:packet/name :use-item :hand (packet/value :hand :main-hand) :sequence 1 :y-rot 0.0 :x-rot 0.0})

(defn flown
  "Where the client puts entity eid after every move the model told it: its add-entity's point
   plus each move-entity-pos's change."
  [sim eid]
  (let [{:keys [x y z]} (first (filter #(= eid (:entity-id %)) (said sim :add-entity)))]
    (reduce (fn [p {:keys [dx dy dz]}] (mapv + p [(/ dx 4096.0) (/ dy 4096.0) (/ dz 4096.0)]))
            [x y z]
            (filter #(= eid (:entity-id %)) (said sim :move-entity-pos)))))

(deftest a-thrown-eye-flies-toward-the-stronghold-and-drops-or-shatters
  (let [sim (sends (in-play {} [0.5 64.0 0.5] eyes {:stronghold [-300.0 30.0 400.0]}) throw-eye)
        [eid {:keys [from]}] (first (:sim/eyes sim))
        there (ticks sim 40)
        over (ticks sim 81)
        [px _ pz] (flown there eid)]
    (is (= [0.5 64.9 0.5] from) "from the middle of the body")
    (is (some #(and (= eid (:entity-id %)) (= sim/eye-type (:type %))) (said sim :add-entity))
        "the client sees an eye of ender")
    (is (= 11 (get-in sim [:sim/inv (inventory/slot [:hotbar 0]) :count])) "one eye spent")
    (is (< (abs (- (math/atan2 399.5 -300.5) (math/atan2 (- pz 0.5) (- px 0.5)))) 0.01)
        "the client's eye went toward the stronghold")
    (is (< (abs (- 12.0 (math/hypot (- px 0.5) (- pz 0.5)))) 0.01) "twelve blocks out")
    (is (empty? (:sim/eyes over)) "gone at the end of its life")
    (is (some #(= [eid] (:ids %)) (said over :remove-entities)))))

(deftest with-no-stronghold-the-eye-stays-in-hand
  (let [sim (sends (in-play {} [0.5 64.0 0.5] eyes {}) throw-eye)]
    (is (empty? (:sim/eyes sim)))
    (is (= 12 (get-in sim [:sim/inv (inventory/slot [:hotbar 0]) :count])))))

(defn use-on
  "The model after the client right-clicks the top of the block at pos with its held item."
  [sim pos]
  (sends sim
         {:packet/name :use-item-on
          :hand (packet/value :hand :main-hand)
          :pos pos
          :face (packet/value :face :up)
          :cursor-x 0.5
          :cursor-y 1.0
          :cursor-z 0.5
          :inside-block false
          :world-border-hit false
          :sequence 2}))

(deftest twelve-eyes-in-twelve-frames-light-the-portal
  (let [sim (in-play (ring 0) [8.5 64.0 12.5] eyes {})
        frames (keys (ring 0))
        eleven (reduce use-on sim (take 11 frames))
        twelve (use-on eleven (last frames))]
    (is (every? #(end/eyed? (sim/block-at twelve %)) frames) "every frame took its eye")
    (is (not-any? #(= end/portal (sim/block-at eleven %)) (end/interior middle))
        "eleven: no portal")
    (is (every? #(= end/portal (sim/block-at twelve %)) (end/interior middle)) "twelve: the portal")
    (is (nil? (get-in twelve [:sim/inv (inventory/slot [:hotbar 0])])) "every eye spent")
    (is (= 9 (count (filter #(= end/portal (:state %)) (said twelve :block-update))))
        "nine portal cells told")))

(defn end-columns
  "The End's floor: end stone from y 40 to 47 round the platform and air over it, the columns
   [6 -1] and [6 0], as column-bytes builds them for the End's sixteen sections."
  []
  (let [column (world/column-bytes
                (into {}
                      (for [x (range 16)
                            z (range 16)
                            y (range 40 64)]
                        [[x y z] (if (< y 48) (blocks/default-state :end_stone) blocks/air)]))
                dimension/end)]
    {[6 -1] column [6 0] column}))

(deftest stepping-into-the-portal-arrives-on-the-platform-with-the-dragon-and-its-crystals
  (let [portal-cells (into {} (for [c (end/interior middle)] [c end/portal]))
        sim (-> (in-play portal-cells [8.5 64.0 8.5] {} {:end {:columns (end-columns)}})
                (ticks 1))]
    (is (= dimension/end (:sim/dimension sim)))
    (is (some #(= dimension/end (:dimension %)) (said sim :respawn)))
    (is (= end/spawn (:sim/player-pos sim)))
    (is (every? (fn [[x z]] (= :obsidian (blocks/name-of (sim/block-at sim [x 48 z]))))
                (for [x (range 98 103) z (range -2 3)] [x z]))
        "the obsidian platform under the feet")
    (is (= :obsidian (blocks/name-of (sim/block-at sim [100 48 0]))))
    (is (= blocks/air (sim/block-at sim [100 49 0])))
    (let [dragon (filter #(= :ender_dragon (:type (val %))) (:sim/mobs sim))
          crystals (filter #(= :end_crystal (:type (val %))) (:sim/mobs sim))]
      (is (= [200.0] (map (comp :health val) dragon)) "the dragon perched with 200 health")
      (is (= 10 (count crystals)) "a crystal on every spike")
      (is (= (count (:sim/mobs sim))
             (count (filter #(#{(mob/type-id :ender_dragon) (mob/type-id :end_crystal)} (:type %))
                            (said sim :add-entity))))
          "each one told"))))

(defn the-end
  "A model in the End on its floor (end-columns), the player on the platform holding inv, the
   dragon at its perch with health and the crystals given."
  [inv health crystals]
  (let [sim (-> (in-play (into {} (for [c (end/interior middle)] [c end/portal]))
                         [8.5 64.0 8.5]
                         inv
                         {:end
                          {:columns (end-columns) :dragon [101.5 49.0 3.5] :crystals crystals}})
                (ticks 1))
        [eid] (first (filter #(= :ender_dragon (:type (val %))) (:sim/mobs sim)))]
    [(assoc-in sim [:sim/mobs eid :health] health) eid]))

(def sword {[:hotbar 0] {:item (recipe/item-id :iron_sword) :count 1}})

(deftest crystals-heal-the-dragon-until-a-hit-blasts-them
  (let [[sim dragon] (the-end sword 190.0 [[103.5 49.0 0.5]])
        healed (ticks sim 20)
        crystal (key (first (filter #(= :end_crystal (:type (val %))) (:sim/mobs sim))))
        hit (sends sim {:packet/name :attack :entity-id crystal})]
    (is (= 192.0 (get-in healed [:sim/mobs dragon :health])) "one every ten ticks")
    (is (nil? (get-in hit [:sim/mobs crystal])) "a hit blows the crystal up")
    (is (< (:sim/health hit) 20.0) "and its blast hurts the player beside it")
    (testing "with no crystal left the dragon heals no more"
      (is (= 190.0 (get-in (ticks hit 20) [:sim/mobs dragon :health]))))))

(deftest the-dragon-dies-to-the-last-hit-and-goes-after-its-death
  (let [[sim dragon] (the-end sword 5.0 [])
        dead (sends sim {:packet/name :attack :entity-id dragon})]
    (is (some? (get-in dead [:sim/mobs dragon :dead-at])))
    (is (some #(= [dragon 3] [(:entity-id %) (:status %)]) (said dead :entity-event)))
    (is (some? (get-in (ticks dead 199) [:sim/mobs dragon])) "still there through its death")
    (is (nil? (get-in (ticks dead 201) [:sim/mobs dragon])) "gone ten seconds on")))
