(ns clojurecraft.pocket-test
  "The pocket to fight blazes from (issue #262): a cell with one opening, a roof and solid blocks
   round it; the site of one that exists or is dug into a wall; the ops that make it; and the
   :pocket intent. Worlds are one chunk column, stone below y 64, air above, feet at [8.5 64 8.5]."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def stone (blocks/default-state :stone))

(defn solid "A block map of stone at each cell." [& cells] (into {} (map (fn [c] [c stone])) cells))

(defn world-of
  "A play world over one chunk column of blocks, the feet at pos."
  ([blocks] (world-of blocks [8.5 64.0 8.5]))
  ([blocks pos]
   (assoc (game/init fx/opts)
          :bot/phase :play
          :player/pos pos
          :player/loaded? true
          :player/on-ground? true
          :player/health 20.0
          :world/chunks {[0 0] (world/column blocks)})))

(def walled
  "A cell [8 64 8] shut on the east, south and north at the feet and the head, roofed, open west."
  (solid [9 64 8] [9 65 8] [8 64 9] [8 65 9] [8 64 7] [8 65 7] [8 66 8]))

(def wall
  "A wall to dig a pocket into from [5 64 8] toward the east: the cells [6 64 8] and [6 65 8] to
   dig, solid blocks round them and over them."
  (solid [6 64 8] [6 65 8] [7 64 8] [7 65 8] [6 64 7] [6 65 7] [6 64 9] [6 65 9] [6 66 8]))

(deftest a-pocket-has-one-opening-and-a-roof
  (let [w (world-of walled)
        at (path/cells w)]
    (is (pocket/pocket? w at [8 64 8]))
    (is (= [[-1 0]] (pocket/openings w [8 64 8])))
    (testing "two openings is a tunnel, not a pocket"
      (let [w (world-of (dissoc walled [9 64 8] [9 65 8]))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "no roof is none"
      (let [w (world-of (dissoc walled [8 66 8]))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "a roof that falls is none"
      (let [w (world-of (assoc walled [8 66 8] (blocks/default-state :gravel)))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))
    (testing "fire in the cell is none: nothing stands in it"
      (let [w (world-of (assoc walled [8 64 8] (blocks/default-state :fire)))]
        (is (not (pocket/pocket? w (path/cells w) [8 64 8])))))))

(deftest a-pocket-is-dug-into-a-wall-whose-other-faces-are-solid
  (let [w (world-of wall [5.5 64.0 8.5])]
    (is (= {:pocket/cell [6 64 8]
            :pocket/stand [5 64 8]
            :pocket/dig [[6 64 8] [6 65 8]]
            :pocket/placed []}
           (pocket/dig-in w [5 64 8] [1 0])))
    (is (nil? (pocket/dig-in w [5 64 8] [-1 0])) "toward open air: nothing to dig")
    (testing "a side that is open lets lava or a fall in: none"
      (let [w (world-of (dissoc wall [7 64 8]) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "a roof of gravel falls on the bot: none"
      (let [w (world-of (assoc wall [6 66 8] (blocks/default-state :gravel)) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "bedrock is never dug"
      (let [w (world-of (assoc wall [6 64 8] (blocks/default-state :bedrock)) [5.5 64.0 8.5])]
        (is (nil? (pocket/dig-in w [5 64 8] [1 0])))))
    (testing "a pocket half dug is still one to dig"
      (let [w (world-of (dissoc wall [6 64 8]) [5.5 64.0 8.5])]
        (is (= [[6 65 8]] (:pocket/dig (pocket/dig-in w [5 64 8] [1 0]))))))))

(deftest the-site-is-the-nearest-pocket-or-a-wall-to-dig-into
  (let [any (constantly true)
        w (world-of walled)]
    (is (= {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}
           (pocket/site-near w [8 64 8] any))
        "a pocket the feet stand in")
    (is (= [8 64 8] (:pocket/cell (pocket/site-near (world-of walled [5.5 64.0 8.5]) [5 64 8] any)))
        "a pocket three cells off, walked to")
    (is (= [6 64 8] (:pocket/cell (pocket/site-near (world-of wall [5.5 64.0 8.5]) [5 64 8] any)))
        "a wall to dig into when there is no pocket")
    (is (nil? (pocket/site-near (world-of wall [5.5 64.0 8.5]) [5 64 8] (constantly false)))
        "the caller's predicate drops a cell")
    (is (nil? (pocket/site-near (world-of {}) [8 64 8] any)) "open ground: none")))

(deftest the-ops-dig-then-stand
  (let [dig (pocket/ops
             {:pocket/cell [6 64 8] :pocket/stand [5 64 8] :pocket/dig [[6 64 8] [6 65 8]]})
        have (pocket/ops {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig []})]
    (is (= [:dig :dig :stand-in] (map :build/op dig)))
    (is (= [[6 64 8] [6 65 8] [6 64 8]] (map :build/pos dig)))
    (is (= [:stand-in] (map :build/op have)))
    (is (= [:feet [8 64 8]] (:build/done (last have))))))

(deftest the-pocket-intent-is-done-in-the-pocket
  (let [site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig []}
        w (assoc (world-of walled) :plan/intent {:intent/kind :pocket :intent/site site})
        i (:plan/intent (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))]
    (is (intent/done? i))
    (is (pocket/in? (assoc w :pocket/site site)))
    (is (not (pocket/in? (assoc (world-of walled [5.5 64.0 8.5]) :pocket/site site))))
    (testing
      "a site that stopped being one is done, not failed: the cell stood in is held to be sealed"
      (let [w (assoc (world-of (dissoc walled [8 66 8]))
                     :plan/intent
                     {:intent/kind :pocket :intent/site site})
            i (:plan/intent (intent/run w (:plan/intent w) {:event/kind :tick :event/now 1000}))]
        (is (intent/done? i))
        (is (nil? (:intent/reason i)))))))

(deftest a-pocket-is-sealed-with-blocks-in-its-opening-and-opened-again
  (let [w (world-of walled)
        site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}]
    (is (= [[7 64 8] [7 65 8]] (pocket/seal-cells w [8 64 8]))
        "the opening at the feet, then the head")
    (is (not (pocket/sealed? w site)))
    (testing
      "the cells placed, all solid: sealed; the pocket is still a pocket (at most one side open)"
      (let [placed [[7 64 8] [7 65 8]]
            sealed (world-of (merge walled (solid [7 64 8] [7 65 8])))
            site (assoc site :pocket/placed placed)]
        (is (pocket/sealed? sealed site))
        (is (empty? (pocket/openings sealed [8 64 8])))
        (is (pocket/valid? sealed (path/cells sealed) site))
        (is (pocket/in? (assoc sealed :pocket/site site)))
        (is (not (pocket/sealed? w site)) "the placed cells open: not sealed")))
    (testing "the ops place the feet cell first, dig the head cell first"
      (is (= [[7 64 8] [7 65 8]] (map :build/pos (pocket/seal-ops [8 64 8] [[7 64 8] [7 65 8]]))))
      (is (= [[7 65 8] [7 64 8]]
             (map :build/pos (pocket/unseal-ops [8 64 8] [[7 64 8] [7 65 8]])))))
    (testing "filler and a pickaxe to open it again: sealable only with both held"
      (is (not (pocket/can-seal? w site)))
      (let [w (assoc-in w [:player/inventory 9] {:item (recipe/item-id :cobblestone) :count 62})
            picked
            (assoc-in w [:player/inventory 10] {:item (recipe/item-id :iron_pickaxe) :count 1})]
        (is (not (pocket/can-seal? w site)) "no pickaxe: sealed in, ten seconds a block by hand")
        (is (pocket/can-seal? picked site))
        (testing "none within a minute of a seal that failed, anywhere"
          (is (not (pocket/can-seal? (assoc picked :pocket/seal-failed-at 1000 :time/now 30000)
                                     site)))
          (is (pocket/can-seal? (assoc picked :pocket/seal-failed-at 1000 :time/now 70000)
                                site)))))))

(deftest a-seal-that-never-finishes-is-given-up
  ;; blaze-race-t job 5: :seal ran 68 minutes on a placement refused with the body in the cell
  (let [site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed [[7 64 8]]}
        at (fn [started now]
             (let [w (assoc (world-of walled)
                            :pocket/site site
                            :time/now now
                            :plan/intent {:intent/kind :seal :intent/started started})]
               (intent/run w (:plan/intent w) {:event/kind :tick :event/now now})))]
    (is (not (intent/done? (:plan/intent (at 0 5000)))) "within the limit it keeps sealing")
    (let [w (at 0 (inc pocket/seal-limit-ms))]
      (is (intent/done? (:plan/intent w)))
      (is (true? (get-in w [:pocket/site :pocket/unsealable?])) "not sealed again at this site")
      (is (= (inc pocket/seal-limit-ms) (:pocket/seal-failed-at w))))))

(deftest a-seal-never-targets-a-cell-the-body-fills
  ;; blaze-race-t job 5: the player at [2154.6 72.0 1750.6] stood sealing 68 minutes on a block
  ;; into [2154 73 1750], the cell over its own feet
  (let [w (assoc (world-of {}) :player/pos [2154.6 72.0 1750.6])
        c [2154 72 1750]]
    (is (= [[2153 72 1750]]
           (pocket/clear-of-body w c [[2154 73 1750] [2153 72 1750] [2154 72 1750]]))
        "the head cell and the feet cell are the body's")
    (testing "a neighbour a fractional position reaches into is the body's too"
      (let [edge (assoc w :player/pos [2154.8 72.0 1750.5])]
        (is (= [[2153 72 1750]] (pocket/clear-of-body edge c [[2155 72 1750] [2153 72 1750]]))
            "x 2154.8 spans 2154.5 to 2155.1: [2155 72 1750] is in the box")))))

(deftest a-seal-is-laid-from-netherrack-when-the-cobblestone-is-spent
  ;; blaze-race-v job 5: 64 cobblestone gone in 8 minutes of tunnel floors, 160 netherrack held,
  ;; no seal, held open under fire until dead
  (let [site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}
        w (-> (world-of walled)
              (assoc-in [:player/inventory 9] {:item (recipe/item-id :netherrack) :count 40})
              (assoc-in [:player/inventory 10] {:item (recipe/item-id :iron_pickaxe) :count 1}))]
    (is (= :netherrack (pocket/seal-filler w 2)))
    (is (pocket/can-seal? w site) "no cobblestone: the Nether's own rock seals")
    (is (every? #(= :netherrack (:build/item %))
                (pocket/seal-ops [8 64 8] [[7 64 8] [7 65 8]] :netherrack)))
    (is (every? #(= :cobblestone (:build/item %)) (pocket/seal-ops [8 64 8] [[7 64 8]]))
        "the default is still cobblestone")))

(deftest an-unseal-that-is-shot-open-ends-so-the-seal-is-laid-again
  ;; blaze-race-v job 2: dug on for 27 s while burning, hp 20 to 0.4
  (let [placed [[7 64 8] [7 65 8]]
        w (assoc (world-of walled) :time/now 100000)]
    (is (not (pocket/shot-open? w placed)) "calm: opened as asked")
    (is (pocket/shot-open? (assoc w :player/on-fire? true) placed) "burning, an opening")
    (is (pocket/shot-open? (assoc w :player/struck-at 99000) placed) "struck 1 s ago")
    (is (not (pocket/shot-open? (assoc w :player/struck-at 90000) placed)) "struck long ago")
    (is (not (pocket/shot-open?
              (assoc (world-of (merge walled (solid [7 64 8] [7 65 8]))) :player/on-fire? true)
              placed))
        "still sealed: nothing is open to shut")))

(deftest a-roof-over-open-air-has-no-foundation-but-the-bots-own-cell
  ;; blaze-race-z2 landing 5, bridging at [2159.5 69 1746.5]: the seal's roof cell had no neighbour and its
  ;; foundation was the cell over the bot's feet, refused with the body in it (t, u, x, y job 5 too)
  (let [w (world-of {})
        site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed []}
        held (-> w
                 (assoc-in [:player/inventory 9] {:item (recipe/item-id :cobblestone) :count 62})
                 (assoc-in [:player/inventory 10] {:item (recipe/item-id :iron_pickaxe) :count 1}))]
    (is (true? (pocket/placeable? w [9 64 8])) "beside the feet on the ground: the floor holds it")
    (is (false? (pocket/placeable? w [8 66 8]))
        "the roof over open air: its foundation is the head cell")
    (is (not (contains? (set (pocket/seal-cells w [8 64 8])) [8 66 8])))
    (is (contains? (set (pocket/wanted-seal-cells w [8 64 8])) [8 66 8]))
    (is (not (pocket/can-seal? held site))
        "a pocket that cannot be roofed is not sealed over air")))

(deftest a-pocket-with-its-placed-cells-solid-and-others-open-is-not-sealed
  ;; blaze-race-z7 job 2 (flip_trace2): :pocket/placed 4 cells all solid, the 4 on the z sides open
  (let [site {:pocket/cell [8 64 8] :pocket/stand nil :pocket/dig [] :pocket/placed [[7 64 8]]}
        half (world-of (merge walled (solid [7 64 8])))
        full (world-of (merge walled (solid [7 64 8] [7 65 8])))]
    (is (= [[7 65 8]] (pocket/wanted-seal-cells half [8 64 8])) "the head cell is still open")
    (is (false? (pocket/sealed? half site)) "the placed one solid is not a seal")
    (is (true? (pocket/sealed? full (assoc site :pocket/placed [[7 64 8] [7 65 8]]))))))

(deftest a-pocket-with-a-wanted-cell-that-cannot-be-laid-is-not-sealed-and-no-refuge
  ;; review of 4cbdea744 and of the lead: eight sides solid, the roof over open air wanted but not
  ;; placeable: sealed? stays false (blazes fly and shoot down: z7 landing 1 burned in one), no :seal
  ;; is re-issued for it, and the site is dropped and remembered (blaze steer)
  (let [c [8 64 8]
        sides [[7 64 8] [9 64 8] [8 64 7] [8 64 9] [7 65 8] [9 65 8] [8 65 7] [8 65 9]]
        w (assoc-in (world-of (apply solid sides))
           [:player/inventory 9]
           {:item (recipe/item-id :cobblestone) :count 62})
        held (assoc-in w [:player/inventory 10] {:item (recipe/item-id :iron_pickaxe) :count 1})
        site {:pocket/cell c :pocket/stand nil :pocket/dig [] :pocket/placed sides}]
    (is (contains? (set (pocket/wanted-seal-cells w c)) [8 66 8]) "the roof is wanted")
    (is (empty? (pocket/seal-cells w c)) "and cannot be laid")
    (is (true? (pocket/unlayable? w c)))
    (is (false? (pocket/sealed? w site)) "not sealed")
    (is (not (pocket/can-seal? held site)) "and no :seal to re-issue")))
