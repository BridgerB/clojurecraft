(ns clojurecraft.build-test
  "The portal plan as data (issues #8, #124): each cell a hole cut into the rock, water poured
   straight over its lava, and the properties that keep a cast from placing into its own stance,
   digging obsidian or working outside the cells a site checks."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.wood :as wood]
            [clojurecraft.world :as world]))

(def origin [100 64 200])

(defn ops-of [plan op] (filter #(= op (:build/op %)) plan))

(deftest the-plan-casts-the-ten-cells-in-layer-order
  (let [plan (build/portal-plan origin :south)
        lava (filter #(= :lava (:build/step %)) plan)
        water (filter #(= :water (:build/step %)) plan)
        scoop (filter #(= :scoop (:build/step %)) plan)]
    (is (= build/frame-cells (map :build/cell lava)) "never sorted by height")
    (is (= build/frame-cells (map :build/cell water)) "one water a cell")
    (testing "lava into the hole cut at the cell"
      (doseq [{[dx dy] :build/cell :as op} lava]
        (is (= [(+ 100 dx) (+ 64 dy) 200] (:build/into op)))))
    (testing "every water op targets the cell directly above its lava cell, never beside it"
      (doseq [[l w] (map vector lava water)]
        (is (= (update (:build/into l) 1 inc) (:build/into w)) (pr-str w))))
    (testing "and the water is scooped back from that same cell, from the same stand"
      (doseq [[w sc] (map vector water scoop)]
        (is (= (:build/into w) (:build/pos sc)))
        (is (= (:build/stand w) (:build/stand sc)))))
    (testing "every pour from the cell's stand, in front of the frame"
      (doseq [{c :build/cell :as op} (concat lava water)]
        (is (= (build/cell origin :south (build/stands c)) (:build/stand op)))))
    (is (= :use-on (:build/op (last (butlast plan)))) "lit")
    (is (= [:dimension "minecraft:the_nether"] (:build/done (last plan))) "then stepped through")))

(defn obsidian-at [p] {:world/chunks {} :world/blocks {p (blocks/default-state :obsidian)}})

(deftest no-dig-can-take-obsidian
  ;; ruststeve dug its own frame and restarted forever: every dig is done once its cell is
  ;; obsidian, so it is never worked
  (doseq [facing build/facings
          op (ops-of (build/portal-plan origin facing) :dig)]
    (is (build/satisfied? (obsidian-at (:build/pos op)) op) (pr-str op))))

(def gen-origin (gen/tuple (gen/choose -1000 1000) (gen/choose -40 200) (gen/choose -1000 1000)))
(def gen-facing (gen/elements (sort build/facings)))

(def gen-height (gen/choose 0 build/max-access))

(deftest the-plan-never-places-into-its-own-stance-nor-into-the-room
  ;; a block placed into the bot's own cell is a ghost (ruststeve); one placed into the room
  ;; would stand in the ray of a pour
  (let [r (tc/quick-check 200
                          (prop/for-all [o gen-origin facing gen-facing h gen-height]
                                        (let [plan (build/portal-plan o facing h)
                                              room (set (map #(build/cell o facing %)
                                                             (build/room-cells h)))]
                                          (and (every? (fn [{:build/keys [pos stand]}]
                                                         (and (not= pos stand)
                                                              (not= pos (update stand 1 inc))
                                                              (not (contains? room pos))))
                                                       (ops-of plan :place))
                                               (= plan (build/portal-plan o facing h))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest every-cell-the-plan-works-is-in-its-footprint
  ;; site-ok? checks the footprint for fluid and loaded chunks, so it must hold every cell an op
  ;; places, digs or pours into
  (let [r (tc/quick-check 200
                          (prop/for-all [o gen-origin facing gen-facing h gen-height]
                                        (every? #(contains? (build/footprint o facing h) %)
                                                (keep #(or (:build/pos %) (:build/into %))
                                                      (build/portal-plan o facing h)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest turning-the-facing-moves-every-cell-and-loses-none
  (let [cells (fn [facing] (set (keep :build/pos (build/portal-plan origin facing))))]
    (doseq [facing build/facings]
      (is (= (count (cells :south)) (count (cells facing))) (name facing)))
    (is (= #{[101 64 200] [99 64 200] [100 64 199] [100 64 201]}
           (set (for [f build/facings]
                  (:build/pos (first (ops-of (build/portal-plan origin f) :use-on))))))
        "the bottom cell the fire goes on turns about the origin")))

(deftest cast-one-casts-the-first-cell
  (let [plan (build/cast-one-plan origin :south)]
    (is (= [[1 0]] (keep :build/cell (filter #(= :lava (:build/step %)) plan))))))

(def hill-origin [2 63 0]) ; the frame far from the way to the stand: nothing on it is the frame's rock
(def hill-stand [6 63 8])

(def hill-op
  {:build/op :place
   :build/item :cobblestone
   :build/pos [6 62 8]
   :build/stand hill-stand
   :build/step :pad
   :build/done [:solid [6 62 8]]})

(def hill-intent
  {:intent/kind :build :intent/build :cast-one :intent/origin hill-origin :intent/facing :south})

(defn open-ground
  "Air at the feet's height and the head, over the stone floor the column fills below y 64."
  [extra]
  (merge (into {} (for [x (range 16) y [63 64] z (range 16)] [[x y z] blocks/air])) extra))

(defn standing-at
  [blocks pos]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
      (assoc :world/chunks {[0 0] (world/column blocks)})))

(deftest a-lit-portal-is-entered-without-rebuilding-or-fetching-tools
  (let [origin [4 63 4]
        at #(build/cell origin :south %)
        frame (into {}
                    (map (fn [[x y]] [(at [x y 0]) (blocks/default-state :obsidian)])
                         build/frame-cells))
        unlit (-> (standing-at (open-ground
                                (assoc frame (at [1 0 1]) (blocks/default-state :cobblestone)))
                               [5.5 63.0 6.5])
                  (memory/remember-site origin :south))
        lit (assoc-in unlit [:world/blocks (at [1 1 0])] (blocks/default-state :nether_portal))
        ops (build/plan-for :nether origin :south 1)
        goal (first (filter #(= :nether (:goal/id %)) plan/goals))]
    (is (= :room (:build/step (build/next-op unlit ops))) "an unfinished cast still opens the room")
    (is (= {:plan/wait :no-log} (plan/next-intent unlit goal plan/goals))
        "an unfinished cast still requires a pickaxe")
    (is (= (peek ops) (build/next-op lit ops))
        "the lit portal skips filled room cells and the rest of the casting work")
    (doseq [inventory [{} {0 {:item (recipe/item-id :oak_planks) :count 4}}]]
      (is (= :build
             (:intent/kind
              (plan/next-intent (assoc lit :player/inventory inventory) goal plan/goals)))
          "entry needs neither a replacement pickaxe nor a table for one"))
    (is (= {:plan/wait :no-log}
           (plan/next-intent
            (assoc-in lit [:world/blocks (at [1 2 0])] (blocks/default-state :stone))
            goal
            plan/goals))
        "a blocked entry still requires a tool to reopen its way")
    (is (nil? (build/next-op (assoc lit :player/dimension "minecraft:the_nether") ops))
        "entry remains done after the dimension changes")))

(deftest a-stand-inside-a-hill-is-dug
  ;; full run 4e59246 stood at the pond and the pad's stand was stone; the walk was :none and
  ;; three :no-path failures ended the plan
  (let [pos [2.5 63.0 8.5]
        flat (standing-at (open-ground {}) pos)
        hill (standing-at (open-ground (into {} (for [x (range 3 7) y [63 64]] [[x y 8] 1]))) pos)]
    (is (= {:intent/kind :walk :intent/target hill-stand :intent/for :build}
           (build/op-child flat hill-intent hill-op))
        "open ground is walked to")
    (is (= :none
           (:path/status
            (path/plan hill (path/feet-cell pos) {:goal/kind :block :goal/pos hill-stand})))
        "the stand is inside the hill, so a walk at it has no route")
    (is (= {:intent/kind :dig
            :intent/target [3 63 8]
            :intent/status :active
            :intent/exposes-ok? true
            :intent/drop-ok? true}
           (build/op-child hill hill-intent hill-op))
        "the first solid on the way, in reach, its drop not wanted (issue #70)")
    (let [opened (assoc-in hill [:world/blocks [3 63 8]] blocks/air)]
      (is (= [3 64 8] (:intent/target (build/op-child opened hill-intent hill-op)))
          "then the head over it"))
    (let [;; a frame facing east at [4 63 12] keeps the rock of x 3..4, z 8..13 (build/kept): the
          ;; stone at [4 63 8] is its frame's, the stone past it is the hill
          kept-intent (assoc hill-intent :intent/origin [4 63 12] :intent/facing :east)
          w (standing-at (open-ground {[4 63 8] 1 [5 63 8] 1 [5 64 8] 1}) pos)]
      (is (= [5 63 8] (:intent/target (build/op-child w kept-intent hill-op)))
          "the frame's rock stays; the stone past it is dug"))))

(def pond-stand [6 63 8])

(def pond-op
  {:build/op :place
   :build/item :cobblestone
   :build/pos [6 62 7]
   :build/stand pond-stand
   :build/step :pad
   :build/done [:solid [6 62 7]]})

(defn pond-at
  "open-ground with water sources in the stone floor."
  [pos cells]
  (standing-at (open-ground (into {} (map (fn [c] [c blocks/water-base]) cells))) pos))

(deftest a-pad-stand-over-water-is-placed-from-the-shore
  ;; full run 61abf8c stood on the bank. The first open pad cell was water and the stand was air
  ;; over the next water cell; the walk was :partial six times and three :no-path ended the plan
  (let [pos [5.5 63.0 8.5]
        w (pond-at pos [[6 62 7] [6 62 8]])]
    (is (not (path/standable? w pond-stand)) "air over water is no stance")
    (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [6 62 7]}
           (build/op-child w hill-intent pond-op))
        "the shore already reaches the cell, so it is placed from here")
    (let [far [2.5 63.0 8.5]
          out (pond-at far [[8 62 8] [8 62 9]])
          stand [8 63 9]
          op (assoc pond-op :build/pos [8 62 8] :build/stand stand :build/done [:solid [8 62 8]])
          child (build/op-child out hill-intent op)]
      (is (not= :found
                (:path/status
                 (path/plan out (path/feet-cell far) {:goal/kind :block :goal/pos stand})))
          "a walk onto the water stand does not arrive")
      (is (= :walk (:intent/kind child)))
      (is (path/standable? out (:intent/target child)) "the walk stops on ground")
      (is (build/reaches-from? out (:intent/target child) [8 62 8]))
      (let [boxed (pond-at far (for [x (range 4 13) z (range 4 13)] [x 62 z]))
            boxed-op (assoc op :build/pos [8 62 8] :build/stand [8 63 9])]
        (is (= :no-stand (build/op-child boxed hill-intent boxed-op))
            "with no ground in reach the plan says so, and does not walk onto the water")))))

(deftest a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches
  ;; race 38477665442 Clj_r25 (issue #45): the water was scooped from a sand cell at the lake's
  ;; edge under a bank; the cell nearest the pad's stand was the bank's top, a step up that only
  ;; cells over the water led to, and three builds failed :no-path walking at it, though the
  ;; sand cell itself reached the pad
  (let [pos [6.8 63.0 8.5] ; the feet over the water's edge, the box on the sand cell east
        water (concat (for [x (range 4 7)] [x 62 8]) (for [x (range 3 9) z (range 9 13)] [x 62 z]))
        bank (merge (into {} (for [x (range 4 7)] [[x 63 7] 1])) ; a step up north, over the water
                    (into {} (for [[x z] [[7 7] [8 7] [8 8]] y [63 64]] [[x y z] 1]))) ; two high beside the sand
        w (standing-at (open-ground (merge (into {} (map (fn [c] [c blocks/water-base]) water))
                                           bank))
                       pos)
        op (assoc pond-op :build/pos [4 62 9] :build/stand [4 63 10] :build/done [:solid [4 62 9]])
        from (path/feet-cell pos)]
    (is (not (path/standable? w from)) "the feet floor over the water")
    (is (= [7 63 8] (path/start-cell w from)) "the box rests on the sand")
    (is (= :none (:path/status (path/plan w from {:goal/kind :block :goal/pos [5 64 7]})))
        "the bank's top is nearest the stand, and no route climbs to it")
    (is (build/reaches-from? w [7 63 8] [4 62 9]) "the sand cell reaches the pad")
    (is (= {:intent/kind :walk :intent/target [7 63 8] :intent/for :build}
           (build/op-child w hill-intent op))
        "the sand cell, which a route reaches, is the stand")))
(def trench
  "Lava sources in the floor along z 15: a surface pool for a site west of it, with the floor at
   z 14 to stand on."
  (into {} (for [x (range 16)] [[x 62 15] blocks/lava-base])))

(def feet-pos [10.5 63.0 10.5])

(defn site-at
  "The site chosen standing at feet-pos over the plain, with blocks laid over it."
  [blocks]
  (build/choose-site (standing-at (open-ground blocks) feet-pos)))

(defn holds-solid? "Is the block at p solid in w?" [w p] (build/holds? w [:solid p]))

(deftest a-site-is-cut-into-the-rock-under-a-surface-pool
  ;; issue #124: no flat open ground is asked for; the frame goes into the rock under the pool's
  ;; level, the room dug in front, and the lava's stand over the room's floor so spilled water
  ;; never runs to it
  (let [{:keys [origin facing h blocks]} (site-at trench)
        w (standing-at (open-ground trench) feet-pos)]
    (is (some? origin) "a site beside the pool")
    (is (<= 1 h build/max-access))
    (is (<= blocks build/site-blocks) "a handful of blocks")
    (is (every? #(holds-solid? w (build/cell origin facing %))
                (for [[dx dy] build/frame-cells] [dx dy 0]))
        "every cell of the frame is in the rock, cut out by the cast")))

(deftest a-pool-sealed-in-the-rock-has-a-site-beside-it
  ;; the pool is underground with no open space round it: the site is beside it all the same,
  ;; the bot digging its way there
  (let [sealed (into {} (for [x [11 12 13] z (range 2 7)] [[x 40 z] blocks/lava-base]))
        {:keys [origin h]} (site-at sealed)]
    (is (some? origin))
    (is (= 41 (+ (second origin) h)) "the room's landing at the height of the lava's stand")))

(deftest no-site-touches-water
  ;; water beside the room spills onto the work, and water beside a hole runs into it: a site
  ;; whose cells water touches is passed over
  (let [pond (into {}
                   (for [x (range 16)
                         z (range 14)
                         y [61 62]
                         :when (not= [x z] [10 10])]
                     [[x y z] blocks/water-base]))]
    (is (nil? (site-at (merge trench pond))))))

(def cast-one-row (first (filter #(= :cast-one (:goal/id %)) plan/goals)))

(deftest a-build-row-holds-a-pickaxe-before-it-starts
  ;; sim race 38477665389 (issue #70, 150 runs at one landing): the cast dug for its stance with
  ;; no pickaxe left and failed :needs-tool three times. The rows hold one, and the needs
  ;; planner makes it first
  (let [w (standing-at (open-ground trench) feet-pos)
        pickaxe {0 {:item (recipe/item-id :stone_pickaxe) :count 1}}]
    (is (= {:plan/wait :no-log} (plan/next-intent w cast-one-row plan/goals))
        "nothing held and no tree known: the pickaxe waits for a log")
    (is (= :build
           (:intent/kind (plan/next-intent
                          (assoc w
                                 :player/inventory
                                 (assoc pickaxe 1 {:item (recipe/item-id :cobblestone) :count 8}))
                          cast-one-row
                          plan/goals)))
        "a pickaxe held and the blocks the plan places: the build")))

(deftest the-portal-kit-asks-for-no-constant-of-blocks
  ;; issue #124: the blocks a cast places are counted from the plan for the place chosen
  ;; (held-first), so the kit holds the buckets alone
  (let [kit (first (filter #(= :portal-kit (:goal/id %)) plan/goals))]
    (is (= {:item/water_bucket 1 :item/bucket 1} (:goal/provides kit)))))


(deftest a-lone-lava-source-is-no-site
  ;; sim race 38664661309 (issue #142): sites beside a lone source cast one cell and failed
  ;; :no-station with nine to go; a site needs a source for every cell its stands can reach
  (let [lone {[12 40 4] blocks/lava-base}
        nine (into {} (for [x [11 12 13] z [2 3 4]] [[x 40 z] blocks/lava-base]))
        ten (assoc nine [11 40 5] blocks/lava-base)]
    (is (nil? (site-at lone)) "one source: no site")
    (is (nil? (site-at nine)) "nine: one short of the frame")
    (is (some? (site-at ten)) "ten: a site")))

(deftest a-build-dig-puts-the-stowed-pickaxe-in-hand-first
  ;; sim race 38806318231 (issue #70): the pickaxe in the hotbar broke on the way to the landing,
  ;; the spare was in slot 35, the way was dug by hand and iron ore in it failed :needs-tool
  (let [ore (first (blocks/states-where (fn [[n]] (= n :iron_ore))))
        w (assoc (standing-at (open-ground {[3 63 8] ore}) [2.5 63.0 8.5])
                 :player/inventory
                 {35 {:item (recipe/item-id :stone_pickaxe) :count 1}})
        dig (build/way-dig [3 63 8])]
    (is (= {:intent/kind :equip :intent/item :stone_pickaxe} (build/tool-first w dig))
        "the spare to hand before the dig")
    (is (= dig
           (build/tool-first
            (assoc w :player/inventory {0 {:item (recipe/item-id :stone_pickaxe) :count 1}})
            dig))
        "in the hotbar already: the dig")))

(deftest the-lava-hunt-goes-down-past-lava-it-cannot-cast-from
  ;; sim race 38811561269 (issue #41): the hunt tunnelled toward a lone source and toward a
  ;; pool over the head, stopped under them with nothing left to cut, and waited :no-site with
  ;; the lava sea below
  (let [pos [10.5 41.0 10.5]
        room {[10 41 10] blocks/air [10 42 10] blocks/air}
        hunt #(build/toward-lava (standing-at (open-ground (merge room %)) pos))
        pool-at (fn [y] (into {} (for [x [11 12 13] z [2 3 4 5]] [[x y z] blocks/lava-base])))
        down {:intent/kind :stairs-down :intent/to-y 35}]
    (is (= :tunnel (:intent/kind (hunt (pool-at 40)))) "a pool level with the feet: a tunnel to it")
    (is (= down (hunt {[12 40 4] blocks/lava-base})) "a lone source: on down toward the sea")
    (is (= down (hunt (pool-at 50))) "a pool over the head: on down toward the sea")))

(deftest the-lava-hunt-leaves-a-boxed-pocket-by-the-way-out
  ;; sim race 38515210080 (issue #41, world R4/36): at y 16 in the iron mine, the feet cell
  ;; boxed by an earlier descent, the lava sea forty blocks under: no stair was cut and the
  ;; plan waited :no-site out
  (let [pos [10.5 41.0 10.5]
        w (-> (standing-at (open-ground {[10 41 10] blocks/air [10 42 10] blocks/air}) pos)
              (assoc :plan/boxed-at #{[10 41 10]}))]
    (is (some? (build/toward-lava w)) "no stair from the pocket: the way out")))

(deftest the-lava-hunt-passes-sources-with-no-safe-stand
  (let [lava (into {} (for [x [11 12 13] y (range 40 51) z [2 3 4 5]] [[x y z] blocks/lava-base]))
        pool (into {} (for [x [2 3 4] z [2 3 4 5]] [[x 40 z] blocks/lava-base]))]
    (doseq [pos [[10.5 41.0 10.5] [14.5 41.0 6.5]]]
      (let [[x y z] (path/feet-cell pos)
            room {[x y z] blocks/air [x (inc y) z] blocks/air}
            w (standing-at (open-ground (merge room lava)) pos)]
        (is (seq (build/sources-near w)) "the nearest lava is loaded")
        (is (nil? (build/hunt-target w)) "lava above the source leaves no safe refill stand")
        (is (= {:intent/kind :stairs-down :intent/to-y 35} (build/toward-lava w))
            "seek usable lava even when already beside the unusable source")
        (let [other (standing-at (open-ground (merge room lava pool)) pos)]
          (is (contains? (set (keys pool)) (build/hunt-target other))
              "a farther pool with a safe stand remains a hunt target"))))))

(deftest a-way-to-the-site-boxed-in-is-left-by-the-way-out
  ;; sim race 38806318231 (issue #130, world R3/11): the stairs down to the cast's landing met an
  ;; aquifer, every stair refused, and the build was sent down the same stairs from the same cell
  ;; three times, :boxed
  (let [w (standing-at (open-ground {}) feet-pos)
        feet [10 63 10]
        i {:intent/kind :build
           :intent/build :cast-one
           :intent/origin [8 40 4]
           :intent/facing :south
           :intent/access 1
           :intent/status :active
           :intent/child {:intent/kind :stairs-down :intent/status :failed :intent/reason :boxed}}
        op (first (build/plan-for :cast-one [8 40 4] :south 1))
        i (assoc i :intent/op op)
        w' (build/work-op (assoc w :plan/intent i) i op {:event/kind :tick :event/now 0})]
    (is (contains? (:plan/boxed-at w') feet) "the pocket remembered")
    (is (nil? (:intent/child (:plan/intent w'))) "the child dropped")
    (is (= :active (:intent/status (:plan/intent w'))) "the build goes on")
    (is (= :stairs-down (:intent/kind (build/to-height w 41))) "from elsewhere: stairs")
    (is (= :retreat (:intent/for (build/to-height w' 41)))
        "from the pocket: the way out, not the same stairs")))

(deftest a-landing-behind-the-frame-is-gone-round-to
  ;; the sim race at 76010d2 (issue #45, world R4/33): the bot stood behind its own frame with the
  ;; landing straight ahead through the frame's rock, which no way digs; three walks at the
  ;; landing failed :no-path
  (let [origin [6 63 6]
        facing :south
        at #(build/cell origin facing %)
        feet (at [2 0 -3])
        rock (into {} (for [x (range 16) y [63 64] z (range 16)] [[x y z] 1]))
        w (standing-at (open-ground (assoc rock feet blocks/air (update feet 1 inc) blocks/air))
                       (mapv + feet [0.5 0.0 0.5]))
        stand (at [2 0 2])
        child (build/toward-stand w stand :cast-one origin facing)
        corner (build/round-corner w stand origin facing)]
    (is (some? corner) "a corner round the rock")
    (is (build/clear-of? (build/kept origin facing) feet corner))
    (is (build/clear-of? (build/kept origin facing) corner stand))
    (is (= :dig (:intent/kind child)))
    (is (not ((build/kept origin facing) (:intent/target child))) "never the frame's rock")
    (is (not= (at [2 0 -2]) (:intent/target child)) "not the straight way into the rock")))

(deftest a-portal-behind-its-back-wall-is-entered-from-the-room
  (doseq [facing build/facings]
    (let [origin [6 63 6]
          at #(build/cell origin facing %)
          feet (at [1 1 -2])
          rock (into {} (for [x (range 16) y [63 64 65 66] z (range 16)] [[x y z] 1]))
          portal (blocks/default-state :nether_portal)
          terrain (merge rock
                         (into {} (for [c (build/room-cells 1)] [(at c) blocks/air]))
                         (into {} (for [c build/interior] [(at (conj c 0)) portal]))
                         {(at [1 0 0]) (blocks/default-state :obsidian)
                          feet blocks/air
                          (update feet 1 inc) blocks/air})
          w (standing-at terrain (mapv + feet [0.5 0.0 0.5]))
          i {:intent/kind :build
             :intent/build :nether
             :intent/origin origin
             :intent/facing facing
             :intent/access 1}
          op (build/enter-op at)
          child (build/op-child w i op)]
      (is (nil? (build/stand-walk w (:build/stand op))))
      (is (nil? (build/round-corner w (:build/stand op) origin facing)))
      (is (= (build/toward-stand w (at (build/landing 1)) :nether origin facing) child))
      (is (= :dig (:intent/kind child)))
      (is (not ((build/kept origin facing) (:intent/target child))))
      (let [room (assoc w :player/pos (build/point origin facing [1.5 0 2.5]))]
        (is (= {:intent/kind :walk :intent/target (at [1 1 0]) :intent/for :build}
               (build/op-child room i op))
            "once the room is reached, walk straight into the portal"))
      (let [inside (assoc w :player/pos (build/point origin facing [1.5 1 0.5]))]
        (is (= :await (:intent/kind (build/op-child inside i op)))
            "stay in the portal until the dimension changes")))))

(deftest a-corner-is-never-a-cell-of-the-room-the-cast-digs
  ;; sim race 38924530337 (issue #45, regressed, world R2/9): the corner round the frame's rock was
  ;; a cell of the room's upper row; the bridge laid cobblestone under it, the room op dug it out
  ;; again, and the walk to the corner failed :no-path
  (let [origin [6 63 6]
        facing :south
        at #(build/cell origin facing %)
        feet (at [2 0 -3])
        rock (into {} (for [x (range 16) y [63 64] z (range 16)] [[x y z] 1]))
        w (standing-at (open-ground (assoc rock feet blocks/air (update feet 1 inc) blocks/air))
                       (mapv + feet [0.5 0.0 0.5]))
        stand (at [2 0 2])
        corner (build/round-corner w stand origin facing)]
    (is (build/in-room? origin facing (at [1 2 2])) "a cell of the room's upper row")
    (is (not (build/in-room? origin facing (at [2 0 -3]))) "the feet's cell, behind the frame")
    (is (not (build/in-room? origin facing corner)) "the corner lies outside the room")))

(deftest a-way-filled-by-falling-gravel-is-cleared-again
  ;; sim race 38837004562 (issue #45, world R1/6): the level way to the landing ran under a bed
  ;; of gravel, each dug cell filled again, and three walks along it failed :no-path
  (let [gravel (first (blocks/states-where (fn [[n]] (= n :gravel))))
        walk
        {:intent/kind :walk :intent/target [10 63 4] :intent/status :failed :intent/reason :no-path}
        at (fn [b] (standing-at (open-ground {[10 63 7] b}) feet-pos))]
    (is (build/way-refilled? (at gravel) walk) "gravel in the way: dropped, cleared again")
    (is (not (build/way-refilled? (at 1) walk)) "stone in the way: a failure as before")
    (is (not (build/way-refilled? (at gravel) (assoc walk :intent/reason :stuck))))))

(deftest a-build-carries-a-crafting-table
  ;; sim races 38806318231 and 38843552659 (issue #186): the cast wore out every pickaxe, sticks
  ;; and cobblestone in hand and the table left at the furnace, and the bot climbed out for a log
  ;; and waited :no-log on treeless ground
  (let [kit {0 {:item (recipe/item-id :stone_pickaxe) :count 1}
             1 {:item (recipe/item-id :cobblestone) :count 8}}
        placed (let [w (standing-at (open-ground (assoc trench
                                                        [11 63 10]
                                                        (blocks/default-state :crafting_table)))
                                    feet-pos)]
                 (assoc (memory/remember-column w [0 0] (get-in w [:world/chunks [0 0]]))
                        :player/inventory
                        kit))
        bare (assoc (standing-at (open-ground trench) feet-pos) :player/inventory kit)
        planks (assoc-in bare [:player/inventory 2] {:item (recipe/item-id :oak_planks) :count 4})]
    (is (= :take-table (:intent/for (plan/next-intent placed cast-one-row plan/goals)))
        "the placed table within reach is taken along")
    (is (not= :build (:intent/kind (plan/next-intent planks cast-one-row plan/goals)))
        "planks enough and no table: one is made for the kit first")
    (is (= :build (:intent/kind (plan/next-intent bare cast-one-row plan/goals)))
        "nothing to make one from: no trip out for it, the build")))

(deftest a-build-carries-sticks-beside-its-table
  ;; world R3/12 seed 1 from spawn (Refs #178): the cast wore out the pickaxes, a table, 684
  ;; cobblestone and one plank in hand and no sticks, and the bot cut stairs up through deepslate
  ;; by hand for a log until its time ran out
  (let [kit {0 {:item (recipe/item-id :stone_pickaxe) :count 1}
             1 {:item (recipe/item-id :cobblestone) :count 8}
             2 {:item (recipe/item-id :crafting_table) :count 1}}
        bare (assoc (standing-at (open-ground trench) feet-pos) :player/inventory kit)
        planks (assoc-in bare [:player/inventory 3] {:item (recipe/item-id :oak_planks) :count 2})
        sticks (assoc-in bare [:player/inventory 3] {:item (recipe/item-id :stick) :count 2})]
    (is (build/carry-sticks? planks) "a table held and planks for sticks: sticks for the kit")
    (is (not= :build (:intent/kind (plan/next-intent planks cast-one-row plan/goals)))
        "made before the build")
    (is (not (build/carry-sticks? sticks)) "sticks held already")
    (is (not (build/carry-sticks? bare)) "nothing to make them from: no trip out for them")))

(deftest a-pit-deep-under-the-landing-is-climbed-out-of-for-wood
  ;; sim race 38843552659 (issue #186, world R1/18): on a ravine's floor sixty under the landing,
  ;; the sky open and no hunt target in its band, the bot waited :no-log
  (let [w (assoc (standing-at (open-ground {}) feet-pos) :plan/go-at [10.5 80.0 10.5])]
    (is (= {:plan/wait :no-log} (wood/gather-next w)) "nothing to set out for")
    (is (= (scoop/pit-climb w) (wood/gather-or-out w)) "the way out of the pit first")
    (is (some? (scoop/pit-climb w)))))

(deftest a-way-never-opens-a-cell-beside-lava
  ;; sim race 38843557186 runner 3 (issue #231, world R1/31): the way to a landing at the lava
  ;; sea's height was dug along its edge, a cell beside a source opened, and the lava flowed in
  (let [w (standing-at (open-ground {[12 63 10] 1 [13 63 10] blocks/lava-base}) feet-pos)
        none (constantly false)]
    (is (not (build/diggable? w [12 63 10] none none)) "stone beside lava is not dug for a way")
    (is (build/diggable? (standing-at (open-ground {[12 63 10] 1}) feet-pos) [12 63 10] none none)
        "the same stone with no lava by it is")))

(deftest a-block-is-never-placed-into-the-bot
  ;; sim race 38665691522 (issue #44): a floor laid from a stand whose head was in its cell, the
  ;; server refusing it three times, :rejected
  (let [w (standing-at (open-ground {[6 63 5] 1}) [5.5 63.0 5.5]) ; a step up beside the bot
        head [5 64 5]]
    (is (= [6 64 5] (build/reachable-stand w [5 65 5] head)) "the step beside reaches the cell")
    (is (not= [5 63 5] (build/reachable-stand w [5 65 5] head))
        "never the feet under it: the head would be in the block")
    (is (build/refused-for-the-body?
         w
         {:intent/kind :place :intent/reason :rejected :intent/cell head})
        "refused with the head in it: the stand was wrong, not the op")
    (is (not (build/refused-for-the-body?
              w
              {:intent/kind :place :intent/reason :rejected :intent/cell [8 63 5]})))))

(deftest a-build-never-digs-rock-by-hand
  ;; sim race 38669750355 (issue #135): the last pickaxe broke in the cast's room at y -20 and
  ;; the build dug deepslate by hand, fifteen seconds a block, for the last half hour
  (let [op {:build/op :dig :build/pos [5 62 6] :build/stand [5 63 5] :build/step :room}
        i {:intent/kind :build
           :intent/build :cast-one
           :intent/origin [4 64 4]
           :intent/facing :south
           :intent/status :active
           :intent/op op}
        kit {0 {:item (recipe/item-id :stick) :count 2}
             1 {:item (recipe/item-id :cobblestone) :count 3}
             2 {:item (recipe/item-id :crafting_table) :count 1}}
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        run (fn [inv & [blocks]]
              (build/work-op (assoc (standing-at (open-ground (or blocks {})) [5.5 63.0 5.5])
                                    :plan/intent i
                                    :player/inventory inv)
                             i
                             op
                             tick))]
    (is (= :no-pickaxe (get-in (run kit) [:plan/intent :intent/reason]))
        "no pickaxe held and one at hand: the row makes it before the stone is dug")
    (is
     (= :dig (get-in (run {}) [:plan/intent :intent/child :intent/kind]))
     "stone and nothing to make one from: the hand digs on, as a cast's last blocks are best dug")
    (is (= :no-pickaxe
           (get-in (run {} {[5 62 6] (blocks/default-state :deepslate)})
                   [:plan/intent :intent/reason]))
        "deepslate, fifteen seconds a block by hand: a trip out for a pickaxe beats it")))

(deftest a-build-replans-for-a-broken-pickaxe-before-the-last-attempt
  (let [origin [5 63 5]
        w (assoc (standing-at (open-ground {[7 63 7] 1}) [5.5 63.0 5.5])
                 :plan/attempts 2
                 :player/controls {:control/forward? true}
                 :player/inventory
                 {0 {:item (recipe/item-id :stick) :count 2}
                  1 {:item (recipe/item-id :cobblestone) :count 3}
                  2 {:item (recipe/item-id :crafting_table) :count 1}})
        op (build/next-op w (build/plan-for :portal origin :south 0))
        i {:intent/kind :build
           :intent/build :portal
           :intent/origin origin
           :intent/facing :south
           :intent/status :active
           :intent/op op}
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}]
    (doseq [child [nil
                   {:intent/kind :stairs-up
                    :intent/to-y 64
                    :intent/status :failed
                    :intent/reason :no-pickaxe}]]
      (let [i (cond-> i child (assoc :intent/child child))
            yielded (intent/run (assoc w :plan/intent i) i tick)
            replanned (plan/finish-intent yielded (:plan/intent yielded))]
        (is (intent/done? (:plan/intent yielded)))
        (is (nil? (get-in yielded [:plan/intent :intent/reason])))
        (is (= {} (:player/controls yielded)))
        (is (zero? (:plan/attempts replanned)))
        (is (= {:site/origin origin :site/facing :south} (memory/site replanned)))
        (is (= :place (:intent/kind (build/held-first replanned cast-one-row plan/goals nil)))
            "the table is placed to craft the replacement before the build resumes")))
    (let [i (assoc i
                   :intent/child
                   {:intent/kind :dig
                    :intent/target (:build/pos op)
                    :intent/status :failed
                    :intent/reason :rejected})
          failed (intent/run (assoc w :plan/intent i) i tick)]
      (is (intent/failed? (:plan/intent failed)))
      (is (= :rejected (get-in failed [:plan/intent :intent/reason])))
      (is (= :failed (:plan/status (plan/fail-intent failed (:plan/intent failed)))))
      (is (= :rejected (:plan/reason (plan/fail-intent failed (:plan/intent failed))))))))

(deftest a-room-cell-behind-a-wall-is-cut-toward
  ;; sim race 38660819204 (issue #74): the bot stood outside its own room, every cell that
  ;; reached the next room cell inside it, no route to any, and failed :no-stand three times.
  ;; The room: walls two high at x 10 and 15, z 1 and 9; the cell to dig, [14 63 5], more than
  ;; reach from every cell outside them
  (let [ring (into {}
                   (for [x (range 10 16)
                         z (range 1 10)
                         y [63 64]
                         :when (or (#{10 15} x) (#{1 9} z))]
                     [[x y z] 1]))
        w (standing-at (open-ground (assoc ring [14 63 5] 1)) [5.5 63.0 5.5])
        i {:intent/kind :build
           :intent/build :cast-one
           :intent/origin [100 64 200]
           :intent/facing :south}]
    (is (nil? (build/reachable-stand w [14 63 5] [14 63 5])) "walled in: no route reaches a stand")
    (is (= [13 63 5] (build/nearest-stand-cell w i [14 63 5]))
        "the stand nearest the cell, at the feet's height, the wall in the way to dig")))

(deftest a-reachable-dig-needs-no-new-stance
  (let [w (standing-at (open-ground {[5 63 5] blocks/water-base [5 64 6] 1}) [5.1 63.0 5.5])
        op (build/dig-op [5 64 6] [5 63 5] nil :open)]
    (is (not (intent/centred? w [5 63 5])))
    (is (not (path/standable? w [5 63 5])))
    (is (= (build/way-dig [5 64 6]) (build/op-child w {:intent/kind :tunnel-to} op))
        "a tunnel digs from its actual position, even off-centre in water")))

(deftest a-nearby-room-at-another-height-is-gone-back-to
  (let [roof (into {} (for [x (range 16) z (range 16)] [[x 64 z] 1]))
        w (standing-at (assoc roof [10 63 6] blocks/air [10 64 6] blocks/air) [10.8 63.0 6.5])
        i (assoc hill-intent :intent/origin [9 57 8] :intent/facing :west :intent/access 1)
        pos [6 62 10]
        op (build/dig-op pos pos nil :room)]
    (is (< (physics/distance (physics/centre (intent/feet w)) (physics/centre [6 58 8]))
           build/site-reach))
    (is (nil? (build/reachable-stand w pos pos)))
    (is (nil? (build/nearest-stand-cell w i pos)))
    (is (= {:intent/kind :stairs-down :intent/to-y 58} (build/op-child w i op))
        "near the site but above it: descend to the fixed landing, not no-stand")))

(deftest a-site-left-for-a-pickaxe-is-gone-back-to
  ;; sim race 38806318231 (issue #196, 75 runs in 37 rounds, world R2/3): the cast's room at y 23,
  ;; the bot climbed to y 64 for a pickaxe's wood and made it; the next op, a room cell 41 below,
  ;; had no stand and no cut at the feet's height, and the build failed :no-stand three times
  (let [i {:intent/kind :build
           :intent/build :portal
           :intent/origin [6 30 12]
           :intent/facing :south
           :intent/access 1}
        pos (build/cell [6 30 12] :south [1 0 1])
        op {:build/op :dig :build/pos pos :build/stand pos :build/step :room}
        far (standing-at (open-ground {}) [5.5 63.0 5.5])]
    (is (= {:intent/kind :stairs-down :intent/to-y 57} (build/approach-stand far i op))
        "far above the room: stairs down toward the landing's height")
    (is (nil? (build/way-back (standing-at (open-ground {}) [6.5 31.0 15.5]) i))
        "at the landing already: nothing to go back by")
    (is (= {:intent/kind :stairs-down :intent/to-y 57}
           (build/approach-stand far
                                 i
                                 {:build/op :place
                                  :build/pos pos
                                  :build/stand (build/cell [6 30 12] :south [1 1 1])
                                  :build/step :floor}))
        "a place op with no stance reachable goes back to the landing too, not :no-stand")))

(deftest a-site-out-of-sight-is-walked-toward-before-anything-is-dug
  ;; sim race 39076908669 (issue #196, world R3/13 seed 2): the bot died at its cast, woke at
  ;; spawn 190 blocks off, and the build's next op, a floor cell, found no stand at the feet:
  ;; :no-stand three times in three ticks
  (let [site [100 30 100]
        i {:intent/kind :build
           :intent/build :portal
           :intent/origin site
           :intent/facing :south
           :intent/access 1}
        [lx _ lz] (build/cell site :south (build/landing 1))
        op {:build/op :place
            :build/pos (build/cell site :south [1 0 1])
            :build/stand (build/cell site :south [1 1 1])
            :build/step :floor}
        far (standing-at (open-ground {}) [5.5 63.0 5.5])]
    (is (= {:intent/kind :walk :intent/target [lx 63 lz] :intent/for :site}
           (build/approach-stand far i op))
        "the landing's chunk is not loaded: a walk toward it, at the feet's height")
    (is (nil? (build/far-way (standing-at (open-ground {}) [5.5 63.0 5.5])
                             (assoc i :intent/origin [6 30 12])))
        "the landing's chunk is loaded: the stairs and the dug way are the build's own")
    (is (nil? (build/far-way far (assoc i :intent/origin [30 30 5])))
        "an unloaded chunk within far-site: the chunks of a start are still arriving")))

(deftest refill-scoops-a-source-under-flowing-lava
  ;; sim race 38715231137 (issue #142, regressed, 166 runs in 44 rounds): at the station the
  ;; nearest source's cell over it held flowing lava (the source there scooped before), the line
  ;; to it was clear, and the refill, asking for air over the source, failed :no-station three
  ;; times in three ticks with the pool half full. The bucket's ray passes flowing lava
  (let [feet [5.5 63.0 5.5]
        refill (fn [blocks]
                 (let [w (standing-at (open-ground blocks) feet)]
                   (build/scoop-at-station w
                                           (build/reach-sources (build/lava-sources w) [5 63 5]))))
        flowing (inc blocks/lava-base)]
    (is (= {:intent/kind :scoop
            :intent/target [7 62 5]
            :intent/fluid :lava
            :intent/aim (scoop/aim-point [7 62 5])}
           (refill {[7 62 5] blocks/lava-base [7 63 5] flowing}))
        "flowing lava over the source: scooped along the line")
    (is (= [2 62 5]
           (:intent/target (refill {[7 62 5] blocks/lava-base
                                    [7 63 5] (blocks/default-state :obsidian)
                                    [2 62 5] blocks/lava-base})))
        "obsidian on the line to the nearest, never dug: the next source is scooped")
    (is (= :no-station
           (refill {[7 62 5] blocks/lava-base [7 63 5] (blocks/default-state :obsidian)}))
        "no source the eyes can be given a line to")))

(deftest a-refill-reconsiders-a-scoop-whose-ray-filled
  ;; issue #227: gravel fell into the cleared line while the bucket was equipped, and the
  ;; build retried the same blocked scoop until :no-scoop.
  (let [src [7 62 5]
        wall [6 63 5]
        w (standing-at (open-ground {src blocks/lava-base}) [5.5 63.0 5.5])
        child (build/scoop-at-station w [src])
        op {:build/op :pour :build/item :lava_bucket}
        fallen (assoc-in w [:world/blocks wall] (blocks/default-state :gravel))
        event {:event/kind :tick :event/now 1000 :event/rand 0.5}]
    (is (= :scoop (:intent/kind child)))
    (is (empty? (scoop/ray-walls w (:intent/aim child))) "clear when the child was chosen")
    (is (not (build/scoop-blocked? w child)))
    (is (build/scoop-blocked? fallen child))
    (is (not (build/scoop-blocked? fallen (assoc child :intent/fluid :water)))
        "the cast's water scoop keeps its own side aim")
    (is (not (build/scoop-blocked? fallen (dissoc child :intent/aim))))
    (doseq [state [{:intent/stage :equip :intent/status :active}
                   {:intent/stage :aim :intent/since 0 :intent/still 3 :intent/status :active}
                   {:intent/stage :sent :intent/sent-at 0 :intent/before 0 :intent/status :active}
                   {:intent/stage :sent :intent/status :failed :intent/reason :no-scoop}]]
      (let [i (assoc hill-intent
                     :intent/status :active
                     :intent/op op
                     :intent/child (merge child state))
            result (build/work-op (assoc fallen :time/now 1000 :plan/intent i) i op event)]
        (is (nil? (get-in result [:plan/intent :intent/child])) (pr-str state))
        (is (= :active (get-in result [:plan/intent :intent/status])))
        (is (= {} (:player/controls result)))
        (is (empty? (filter #(= :send (:effect/kind %)) (:bot/effects result))))))
    (let [i (assoc hill-intent
                   :intent/op op
                   :intent/child (assoc child :intent/status :failed :intent/reason :no-scoop))
          result (build/work-op (assoc w :plan/intent i) i op event)]
      (is (= :no-scoop (get-in result [:plan/intent :intent/reason])))
      (is (intent/failed? (:plan/intent result))
          "an unanswered scoop with a clear ray still fails"))
    (let [next (build/scoop-at-station fallen [src])]
      (is (= :dig (:intent/kind next)))
      (is (= wall (:intent/target next)) "the next refill clears the new obstruction"))))

(deftest a-way-over-a-hole-gets-a-floor
  ;; sim race 38665691522 (issue #162, world R3/37): the refill's way out from the landing ran
  ;; over a cave's open cell, the line clear and nothing to dig, and the walk to the far side,
  ;; with no floor to stand on, failed :no-path three times. Here the stand two ahead is walled
  ;; in on three sides and the cell between is a hole two deep
  (let [walls (into {} (for [[x z] [[4 6] [6 6] [4 7] [6 7] [5 8]] y [63 64]] [[x y z] 1]))
        hole {[5 62 6] blocks/air [5 61 6] blocks/air}
        cobble {0 {:item (recipe/item-id :cobblestone) :count 8}}
        toward (fn [inv]
                 (build/toward-stand (assoc (standing-at (open-ground (merge walls hole))
                                                         [5.5 63.0 5.5])
                                            :player/inventory
                                            inv)
                                     [5 63 7]
                                     :portal
                                     [100 64 200]
                                     :south))]
    (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [5 62 6]} (toward cobble))
        "the hole's top cell is floored from the feet")
    (is (= :walk (:intent/kind (toward {}))) "nothing to place: the walk, as before")))

(deftest a-way-is-cut-round-water-and-awkward-footing
  (doseq [obstacle [{[5 63 8] blocks/water-base}
                    {[5 62 8] (blocks/default-state :magma_block) [5 61 8] blocks/lava-base}]]
    (let [origin [6 63 0]
          feet [2 63 8]
          stand [10 63 8]
          rock (into {} (for [x (range 16) y [63 64] z (range 16)] [[x y z] 1]))
          w (standing-at
             (merge (open-ground rock) {feet blocks/air (update feet 1 inc) blocks/air} obstacle)
             [2.5 63.0 8.5])
          barred? (build/barred w origin :south)
          corner (build/round-corner w stand origin :south)
          child (build/toward-stand w stand :portal origin :south)]
      (is (not (build/clear-of? barred? feet stand)))
      (is (some? corner))
      (is (build/clear-of? barred? feet corner))
      (is (build/clear-of? barred? corner stand))
      (is (= :dig (:intent/kind child)) "cut a dry detour, not a walk through the obstacle")
      (is (not= [3 63 8] (:intent/target child)) "never begin the barred straight way"))))

(deftest a-refill-stand-never-rests-on-magma
  (let [w (standing-at (open-ground trench) feet-pos)
        {:keys [origin facing h]} (site-at trench)
        stand (:stand (build/station w origin facing h))
        changed
        (assoc-in w [:world/blocks (update stand 1 dec)] (blocks/default-state :magma_block))
        other (:stand (build/station changed origin facing h))]
    (is (some? stand))
    (is (some? other))
    (is (not= stand other))
    (is (path/floor? changed (update other 1 dec)))))

(deftest an-unsafe-build-stance-gives-up-the-site
  (let [site [8 62 4]
        target [9 63 7]
        op (assoc hill-op :build/stand target)
        child
        {:intent/kind :walk :intent/target target :intent/status :failed :intent/reason :no-path}
        i (assoc hill-intent :intent/origin site :intent/op op :intent/child child)]
    (doseq [obstacle [{target blocks/water-base} {(update target 1 inc) blocks/water-base}
                      {(update target 1 dec) (blocks/default-state :magma_block)}]]
      (let [w (assoc (standing-at (open-ground obstacle) [8.5 63.0 7.5]) :plan/intent i)
            out (build/work-op w i op {:event/kind :tick})]
        (is (build/lost-way? w i child))
        (is (not (build/lost-way? w i (assoc child :intent/pickup? true)))
            "a walk beside a work block does not require standing in that block")
        (is (intent/done? (:plan/intent out)))
        (is (= #{site} (memory/given-up-sites out)))))))

(deftest a-pool-without-a-safe-station-is-left
  (let [site [8 62 4]
        i (assoc hill-intent :intent/origin site :intent/op hill-op)
        w (assoc (standing-at (open-ground {}) [8.5 63.0 7.5]) :plan/intent i)
        out (with-redefs [build/op-child (fn [_ _ _] :no-station)]
              (build/work-op w i hill-op {:event/kind :tick}))]
    (is (intent/done? (:plan/intent out)))
    (is (= #{site} (memory/given-up-sites out)))))

(deftest an-exhausted-walk-to-a-nearby-build-stand-gives-up-the-site
  (let [site [8 62 4]
        target [10 63 7]
        walls (into {} (for [[x z] [[9 7] [11 7] [10 6] [10 8]] y [63 64 65]] [[x y z] 1]))
        op (assoc hill-op :build/stand target)
        child {:intent/kind :walk
               :intent/for :build
               :intent/target target
               :intent/replans intent/max-replans
               :intent/status :failed
               :intent/reason :no-path}
        i (assoc hill-intent :intent/origin site :intent/op op :intent/child child)
        w (assoc (standing-at (open-ground walls) [8.5 63.0 7.5])
                 :plan/intent i
                 :plan/attempts 2
                 :player/controls {:control/forward? true})
        out (build/work-op w i op {:event/kind :tick})]
    (is (path/standable? w target) "the fixed stance is dry and has sound footing")
    (is (not (intent/routable? w child)) "but no route reaches it through the surrounding rock")
    (is (intent/done? (:plan/intent out)) "an exhausted route does not spend the last attempt")
    (is (= 2 (:plan/attempts out)))
    (is (= {} (:player/controls out)))
    (is (= #{site} (memory/given-up-sites out)) "the next build must choose another site")
    (testing "nearby walks that are not exhausted fixed-stance approaches retain their handling"
      (is (not (build/lost-way? w i (assoc child :intent/pickup? true))))
      (is (not (build/lost-way? w i (assoc child :intent/reason :stuck))))
      (is (not (build/lost-way? w (assoc i :intent/kind :tunnel-to) child))))))

(deftest an-unreachable-retreat-waypoint-is-not-retried
  (let [target [10 63 7]
        child {:intent/kind :walk
               :intent/for :retreat
               :intent/target target
               :intent/status :failed
               :intent/reason :no-path}
        i (assoc hill-intent :intent/op hill-op :intent/child child)
        w (assoc (standing-at (open-ground {}) feet-pos)
                 :plan/intent i
                 :plan/attempts 2
                 :player/controls {:control/forward? true})
        out (build/work-op w i hill-op {:event/kind :tick})]
    (is (= #{target} (:plan/retreat-unreachable out)))
    (is (nil? (get-in out [:plan/intent :intent/child])))
    (is (not (intent/done? (:plan/intent out))))
    (is (= 2 (:plan/attempts out)))
    (is (= {} (:player/controls out)))
    (is (empty? (memory/given-up-sites out)))))

(deftest the-lava-hunt-passes-over-a-given-up-pool
  (let [deeper [8 40 12]
        w (standing-at (open-ground (assoc trench deeper blocks/lava-base)) feet-pos)
        left (memory/give-up-site w [8 62 12])]
    (is (seq (build/sources-near w)))
    (is (= [deeper] (build/sources-near left)) "sources outside the rejected site are retained")
    (is (nil? (build/lava-walk left)))
    (is (nil? (build/uncap left)))
    (is (= :stairs-down (:intent/kind (build/lava-hunt left))))
    (is (< (:intent/to-y (build/lava-hunt left)) (second (intent/feet left)))
        "seek deeper lava, not another walk round the rejected pool")))

(deftest the-roof-over-lava-is-broken-though-its-ore-drops-nothing
  ;; real race 39076909951 (issue #163, bot Clj_r02): the roof over the lava sea at y -59 was
  ;; deepslate diamond ore, a stone pickaxe in hand, and the dig failed :needs-tool three times
  (let [roof [8 63 8]
        w (standing-at (open-ground {[8 62 8] blocks/lava-base
                                     roof (blocks/default-state :deepslate_diamond_ore)})
                       [7.5 63.0 6.5])
        c (build/uncap w)]
    (is (= roof (:intent/target c)) "the roof over the source is the dig")
    (is (true? (:intent/drop-ok? c))
        "its drop is not wanted: ore the pickaxe cannot take is broken")
    (is (= :lava (:intent/for c)))))

(deftest the-cells-a-build-places-in-are-those-of-every-access
  ;; sim race 39076908669 (issue #178, world R1/11 seed 5): access 4's floor was not among the
  ;; cells kept, and the way to the lava's stand dug it as fast as it was placed
  (let [cells (build/place-cells :portal origin :south)]
    (doseq [h (range (inc build/max-access))
            op (build/plan-for :portal origin :south h)
            :when (= :place (:build/op op))]
      (is (contains? cells (:build/pos op)) (str "access " h " places in " (:build/pos op))))))

(deftest a-stand-within-reach-is-not-walked-to
  ;; real race 39076909951 (issue #163): a stand within the walk's reach but beyond two blocks was
  ;; walked to again every tick, the walk ending as it began
  (let [walk-at (fn [pos] (build/lava-walk (standing-at (open-ground trench) pos)))]
    (is (= :lava (:intent/for (walk-at [10.5 63.0 8.5]))) "far from every stand: walked to")
    (is (nil? (walk-at [10.5 63.0 11.5])) "the eye within reach of the stands: already there")))

(deftest a-build-fetches-its-kit-wood-only-from-the-surface
  ;; sim race 39076908669 (issue #178, world R2/21 seed 2): the pickaxe broke at the cast with no
  ;; table, one stick and no planks, and the hand-dug climb for a log took the run's last hour
  (let [w (standing-at (open-ground {}) [5.5 63.0 5.5])
        trunk {:intent/kind :walk :intent/target [9 63 9] :intent/for :log}]
    (with-redefs [wood/near-walk (constantly trunk)]
      (is (true? (build/carry-table? w)) "out of the ground with a trunk near: a table is fetched")
      (with-redefs [stairs/out? (constantly false)]
        (is (not (build/carry-table? w)) "underground the build goes on without it")))
    (with-redefs [wood/near-walk (constantly nil)]
      (is (not (build/carry-table? w)) "no trunk a route reaches: nothing to fetch"))))

(deftest a-refill-never-opens-lava-over-its-stand
  ;; Issue #231, R1/32: the ray to a low source opened the wall under higher lava,
  ;; which flowed onto the stand before the next dig finished.
  (doseq [leak [[7 64 5] [7 63 6]]]
    (let [src [7 62 5]
          wall [7 63 5]
          w (standing-at (open-ground {src blocks/lava-base wall 1 leak blocks/lava-base})
                         [5.5 63.0 5.5])]
      (is (= wall (build/sight-block w src)))
      (is (= :no-station (build/scoop-at-station w [src]))
          "a low source does not make the rock over it safe to open")
      (let [other [2 62 5]
            w (assoc-in w [:world/blocks other] blocks/lava-base)]
        (is (= other (:intent/target (build/scoop-at-station w [src other])))
            "another source is used instead"))
      (let [station (build/station-among w [src] [2 62 1] :south 1)]
        (is (not= [4 63 5] (:stand station)) "the unsafe stand is rejected before walking to it"))
      (let [safe (assoc-in w [:world/blocks leak] blocks/air)]
        (is (= wall (:intent/target (build/scoop-at-station safe [src]))))
        (is (= [4 63 5] (:stand (build/station-among safe [src] [2 62 1] :south 1)))
            "a lid over lava below the feet may still be opened")))))

(deftest a-refill-checks-the-eyes-at-the-stand
  ;; Issue #380, R3/32: a stand's projected ray was safe, but the feet slightly off its
  ;; centre saw a wall holding back higher lava and failed :no-station three times.
  (let [src [3 61 6]
        stand [5 63 5]
        origin [13 62 1]
        w (standing-at
           (open-ground
            {src blocks/lava-base [4 63 5] 1 [4 63 6] 1 [4 64 5] 1 [4 65 5] blocks/lava-base})
           [5.474 63.0 5.506])
        centred (assoc w :player/pos [5.5 63.0 5.5])]
    (is (intent/centred? w stand))
    (is (build/safe-source? centred src) "the projected eyes accept this stand")
    (is (not (build/safe-source? w src)) "the actual eyes must not open the higher lava")
    (is (= stand (:stand (build/station-among centred [src] origin :west 1))))
    (let [station (build/station-among w [src] origin :west 1)]
      (is (some? station) "another stand can reach the source")
      (is (not= stand (:stand station)) "the unsafe stand is reconsidered before refilling"))
    (is (= stand
           (:stand
            (build/station-among (assoc w :player/pos [12.5 63.0 5.5]) [src] origin :west 1)))
        "a stand not yet reached still uses its projected eyes")
    (let [w (assoc w :player/inventory {0 {:item (recipe/item-id :bucket) :count 1}})
          i {:intent/kind :build
             :intent/build :cast-one
             :intent/origin origin
             :intent/facing :west
             :intent/access 1}]
      (is (map? (build/refill w i)) "an already active refill replans instead of failing"))))

(deftest a-climb-finishes-before-the-build-repairs-its-headroom
  (let [w (standing-at (open-ground {}) [5.5 63.0 5.5])
        climb {:intent/kind :stairs-up
               :intent/to-y 66
               :intent/to-height? true
               :intent/for :build
               :intent/status :active
               :intent/stage :open
               :intent/started-y 63.0
               :intent/started-tick 0
               :intent/dir [1 0]
               :intent/cells [[5 65 5] [6 65 5] [6 64 5]]}
        i (assoc hill-intent :intent/op hill-op :intent/child climb)
        floor (build/place-op [5 65 5] [5 66 5] nil :floor)
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        advance (fn [i] (build/work-op (assoc w :plan/intent i) i floor tick))
        climbing (advance i)]
    (is (= hill-op (get-in climbing [:plan/intent :intent/op])))
    (is (= :step (get-in climbing [:plan/intent :intent/child :intent/stage]))
        "opening a planned floor for headroom must not cancel the climb and replace it")
    (doseq [status [:done :failed]]
      (let [finished (advance (assoc-in i [:intent/child :intent/status] status))]
        (is (= floor (get-in finished [:plan/intent :intent/op])))
        (is (nil? (get-in finished [:plan/intent :intent/child]))
            "after the climb ends, re-read the plan and repair the missing floor")))
    (let [walking (advance (assoc i :intent/child {:intent/kind :walk :intent/status :active}))]
      (is (= floor (get-in walking [:plan/intent :intent/op])))
      (is (nil? (get-in walking [:plan/intent :intent/child]))
          "other children still give way when the world changes"))))

(deftest a-site-no-way-reaches-is-given-up
  ;; sim race 38839665459 (issue #222, world R1/11): the build kept a site 70 blocks off across an
  ;; aquifer; the walk there failed :no-path, the swim out of the water it let in :stuck, and the
  ;; escape from that water :stuck: three failures toward the one site
  (let [site [8 40 4]
        i {:intent/kind :build
           :intent/build :cast-one
           :intent/origin site
           :intent/facing :south
           :intent/access 1
           :intent/status :active
           :intent/child {:intent/kind :walk :intent/status :failed :intent/reason :no-path}}
        op (first (build/plan-for :cast-one site :south 1))
        i (assoc i :intent/op op)
        tick {:event/kind :tick :event/now 0 :event/rand 0.5}
        run (fn [pos]
              (build/work-op (assoc (standing-at (open-ground {}) pos) :plan/intent i) i op tick))
        far (run feet-pos)]
    (is (intent/done? (:plan/intent far)) "the build ends, it does not fail")
    (is (= #{site} (memory/given-up-sites far)) "and its site is given up")
    (is (build/given-up? #{site} [11 40 6]) "the same pool's other facings with it")
    (is (not (build/given-up? #{site} [40 40 4])))
    (testing "at the site a failed walk is the op's, and fails the build as before"
      (let [near (run (physics/centre (build/cell site :south (build/landing 1))))]
        (is (= :no-path (get-in near [:plan/intent :intent/reason])))
        (is (empty? (memory/given-up-sites near)))))))

(deftest a-boxed-climb-back-gives-up-the-distant-site
  (let [site [8 75 4]
        op (first (build/plan-for :portal site :south 1))
        i {:intent/kind :build
           :intent/build :portal
           :intent/origin site
           :intent/facing :south
           :intent/access 1
           :intent/status :active
           :intent/op op
           :intent/child {:intent/kind :stairs-up :intent/status :failed :intent/reason :boxed}}
        w (assoc (standing-at (open-ground {}) feet-pos)
                 :plan/intent i
                 :plan/attempts 2
                 :player/controls {:control/forward? true})
        result (build/work-op w i op {:event/kind :tick :event/now 0})]
    (is (intent/done? (:plan/intent result)) "the exhausted climb does not fail the last attempt")
    (is (= 2 (:plan/attempts result)))
    (is (= {} (:player/controls result)))
    (is (= #{site} (memory/given-up-sites result)) "the next plan cannot retry this site")
    (testing "other uses of the shared executor keep their own failure handling"
      (is (not (build/lost-way? w (assoc i :intent/kind :tunnel-to) (:intent/child i)))))
    (testing "a nearby climb is not a lost route back to the site"
      (is (not (build/lost-way?
                (assoc w :player/pos (physics/centre (build/cell site :south (build/landing 1))))
                i
                (:intent/child i)))))))

(deftest a-stand-still-in-the-rock-is-cut-to-not-walked-near
  ;; sim race 38855091523 (issue #178, world R1/34 seed 2): the lava's stand was stone, a route
  ;; led beside it, the walk ended a block under it, and the refill went back to the landing and
  ;; out again, three walks every five seconds until the run's time ran out
  (let [w (standing-at (open-ground {[8 63 5] 1 [8 64 5] 1}) [5.5 63.0 5.5])]
    (is (nil? (build/stand-walk w [8 63 5])) "stone where the feet would stand: no walk")
    (is (= {:intent/kind :walk :intent/target [7 63 5] :intent/for :build}
           (build/stand-walk w [7 63 5]))
        "open with a floor: walked to")
    (is (build/beside-stand? w [6 64 5]) "a block up beside the feet: the way is to its height")
    (is (not (build/beside-stand? w [9 64 5])) "four off: by the landing, as before")))

(deftest the-way-out-is-not-taken-across-lava-nor-from-beside-the-stand
  ;; sim race 38855091523 (issue #178, world R1/34 seed 2): the next lava stand lay past the pool,
  ;; the way out along it crossed the lava, round-corner sent the feet back toward the frame and
  ;; the refill out again, and from beside the stand itself out again, until the time ran out
  (let [origin [2 63 2]
        stand [12 63 9]
        out [5 63 9]
        at (fn [blocks feet] (standing-at (open-ground blocks) feet))]
    (is (build/out-first? (at {} [5.5 63.0 5.5]) origin :south stand out 1 4)
        "nearer the frame, the way out clear: out first")
    (is (not (build/out-first? (at {[8 62 9] blocks/lava-base} [5.5 63.0 5.5])
                               origin
                               :south
                               stand
                               out
                               1
                               4))
        "lava under the way out: straight for the stand instead")
    (is (not (build/out-first? (at {} [11.5 63.0 9.5]) origin :south stand out 1 4))
        "beside the stand: no way out first")
    (is (not (build/out-first? (at {} [5.5 63.0 5.5]) origin :south stand out 4 4))
        "already as far out as the stand")))

(deftest a-block-in-the-way-is-dug-from-a-hole-under-the-stands-height
  ;; sim race 38855091523 (issue #178, world R1/34 seed 2, after #250): the walk to the block in
  ;; the way of the lava's stand ended a block under it in a hole of the floor, and the refill
  ;; sent the feet back to the landing, two walks every two seconds until the time ran out
  (let [blocks {[5 62 5] blocks/air [6 63 5] 1 [6 64 5] 1}
        stand [9 63 5]
        dig #(build/dig-from-under % stand :portal [100 64 200] :south)]
    (is (= [6 63 5] (:intent/target (dig (standing-at (open-ground blocks) [5.5 62.0 5.5]))))
        "in the hole, the block in the way in reach: dug from there")
    (is (nil? (dig (standing-at (open-ground blocks) [5.5 63.0 4.5])))
        "at the stand's height it is clear-stand's, not this")))
