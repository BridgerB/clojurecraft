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
           :intent/origin [100 64 200]
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
        "at the landing already: nothing to go back by")))

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
