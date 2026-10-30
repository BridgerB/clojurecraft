(ns clojurecraft.pocket
  "A pocket to fight blazes from (issue #262): a cell with one opening and solid blocks on its
   other sides and overhead, so at most one blaze has a line on the bot at a time. Blaze-race-g
   and -i, 20 deaths: nine fought or backed off in the open while burning or under a volley, three
   stood frozen in a fire or a blaze's reach, and fireballs hit 3 to 10 times a run with the
   burning after them taking 7 to 45.

   A pocket is a fact (:pocket/site, a site map): the cell, the cell it is entered from (nil for a
   pocket that exists), and the solid cells still to dig. site-near finds one by walking from the
   feet: a cell that already is a pocket (pocket?), else a cell dug into a wall beside a standable
   cell (dig-in), its other five faces solid, loaded and not falling, never beside lava (every
   cell round the two dug is a block, so nothing flows in). plan is the site as build ops: the
   digs, then standing in the cell. The :pocket intent works that plan the way :build does."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.path :as path]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- geometry

(def sides "The four horizontal directions as [dx dz]." [[1 0] [-1 0] [0 1] [0 -1]])

(def search-steps 12) ; cells of walk from the feet a site is looked for within
(def search-nodes 400) ; cells one search visits at most

(def undiggable
  "Blocks a pocket is never dug out of: a dig of them fails or opens what must stay shut."
  #{:bedrock :obsidian :crying_obsidian :spawner :nether_portal :end_portal :end_portal_frame
    :barrier :reinforced_deepslate :command_block})

(defn plus "Cell c moved by [dx dy dz]." [[x y z] [dx dy dz]] [(+ x dx) (+ y dy) (+ z dz)])

(defn solid-at?
  "Is the cell a loaded solid block? An unloaded cell is not: nothing is known of it."
  [world c]
  (boolean (some-> (terrain/block-at world c)
                   blocks/solid?)))

(defn firm-at?
  "Is the cell a loaded solid block that does not fall when the cell under it opens (gravel,
   sand)? A roof of those drops on the bot."
  [world c]
  (let [id (terrain/block-at world c)]
    (boolean (and id (blocks/solid? id) (not (blocks/falls? id))))))

(defn diggable-at?
  "Can the cell be dug out or is it open already: a solid block not in undiggable and not falling,
   or a clear cell (a pocket half dug)? An unloaded cell is neither."
  [world c]
  (let [id (terrain/block-at world c)]
    (boolean (and id
                  (or (not (blocks/solid? id))
                      (and (not (contains? undiggable (blocks/name-of id)))
                           (not (blocks/falls? id))))))))

(defn shut?
  "Is the side [dx dz] of the cell closed at the feet and at the head: both cells beside it solid?"
  [world c [dx dz]]
  (and (solid-at? world (plus c [dx 0 dz])) (solid-at? world (plus c [dx 1 dz]))))

(defn openings
  "The sides of the cell that are not shut (shut?), as [dx dz]."
  [world c]
  (vec (remove #(shut? world c %) sides)))

(defn roofed?
  "Is the cell over the head a firm solid (firm-at?)?"
  [world c]
  (firm-at? world (plus c [0 2 0])))

(defn pocket?
  "Is cell c a pocket now: standable (at, the cells of the world, path/cells), roofed, and exactly
   one side open? A fire in the cell is not standable (path/classify-id)."
  [world at c]
  (and (path/standable? at c) (roofed? world c) (= 1 (count (openings world c)))))

(defn shape?
  "Is cell c shaped like a pocket or a sealed one: standable (at, the cells of the world), roofed
   and at most one side open? A pocket whose opening has a block placed in it has none."
  [world at c]
  (and (path/standable? at c) (roofed? world c) (<= (count (openings world c)) 1)))

(defn dig-in
  "The site of a pocket dug into the wall from the standable cell stand toward side [dx dz], or
   nil: the cell beside stand and the one over it each solid and diggable (or dug already), the
   other three sides of both shut, the roof firm, and a solid floor that is not lava. Nothing round
   the two cells is open, so digging them lets in no lava or fall. The site names the cells still
   to dig."
  [world stand [dx dz]]
  (let [pf (plus stand [dx 0 dz])
        ph (plus pf [0 1 0])
        back [(- dx) (- dz)]
        floor (plus pf [0 -1 0])]
    (when (and (diggable-at? world pf)
               (diggable-at? world ph)
               (every? #(shut? world pf %) (remove #{back} sides))
               (roofed? world pf)
               (solid-at? world floor)
               (not (contains? blocks/lava-states (terrain/block-at world floor))))
      {:pocket/cell pf
       :pocket/stand stand
       :pocket/dig (filterv #(solid-at? world %) [pf ph])
       :pocket/placed []})))

(defn valid?
  "Is the site still one: a cell shaped like a pocket (shape?: at most one side open, so a sealed
   one holds), a cell the bot stands in to be sealed in place (:pocket/here?, standable), or a
   dig-in from its stand toward its cell that still holds? A fire or a lava next to it, or a
   wall dug through, makes it none."
  [world at {:pocket/keys [cell stand here?] :as site}]
  (boolean (and site
                (or (shape? world at cell)
                    (and here? (path/standable? at cell))
                    (and stand
                         (path/standable? at stand)
                         (let [[sx _ sz] stand
                               [cx _ cz] cell]
                           (some? (dig-in world stand [(- cx sx) (- cz sz)]))))))))

(defn site-near
  "The nearest site to the feet by walking, or nil: of the cells reached (steps of walk within
   search-steps, search-nodes visited; at is path/cells) the first that is a pocket (a site with no
   dig), or else has a wall to dig a pocket into (dig-in), at each distance an existing pocket
   before a dug one. ok? is asked of the cell of a site: false drops it (outside a spawner's
   volume, within its wake)."
  ([world feet ok?] (site-near world feet ok? search-nodes))
  ([world feet ok? nodes]
   (let [at (path/cells world)]
     (loop [frontier [feet]
            seen #{feet}
            depth 0
            visited 0]
       (let [here (fn [c]
                    (when (and (pocket? world at c) (ok? c))
                      {:pocket/cell c :pocket/stand nil :pocket/dig [] :pocket/placed []}))
             dug (fn [c]
                   (some (fn [s]
                           (let [site (dig-in world c s)]
                             (when (and site (ok? (:pocket/cell site))) site)))
                         sides))
             hit (or (some here frontier) (some dug frontier))]
         (cond hit hit
               (or (empty? frontier) (>= depth search-steps) (>= visited nodes)) nil
               :else
               (let [next (into []
                                (comp (mapcat (fn [[x y z]]
                                                (for [[dx dz] sides
                                                      dy [0 -1 1]]
                                                  [(+ x dx) (+ y dy) (+ z dz)])))
                                      (remove seen)
                                      (filter #(path/standable? at %))
                                      (distinct))
                                frontier)]
                 (recur next (into seen next) (inc depth) (+ visited (count next))))))))))

(defn held-valid
  "The site the world holds (:pocket/site) while it is valid?, else nil."
  [world]
  (let [held (:pocket/site world)] (when (valid? world (path/cells world) held) held)))

(defn current
  "The site to use now: the one the world holds while it is valid? (held-valid) and ok? of its
   cell, else the nearest found (site-near, within nodes cells visited), else nil."
  ([world feet ok?] (current world feet ok? search-nodes))
  ([world feet ok? nodes]
   (let [held (held-valid world)]
     (if (and held (ok? (:pocket/cell held))) held (site-near world feet ok? nodes)))))

(defn in?
  "Are the feet in the held site's cell, and is it still a valid? one? The bot is in cover: one
   opening, none when sealed, or a cell it is to seal."
  [world]
  (let [{:pocket/keys [cell] :as site} (:pocket/site world)]
    (boolean (and site (= cell (intent/feet world)) (valid? world (path/cells world) site)))))

(defn here-site
  "The site that seals the cell the feet stand in, in place, or nil when it is not standable: a
   cell with no dig, sealed by seal-ops on every open side and over the head. What a :pocket falls
   back to when no way to a pocket is found (blaze-race-j geared 4 and race 2: three
   :pocket no-path failures in 0.4 s ended the run at 17 hp)."
  [world]
  (let [c (intent/feet world)]
    (when (path/standable? (path/cells world) c)
      {:pocket/cell c :pocket/stand nil :pocket/dig [] :pocket/placed [] :pocket/here? true})))

;; ---------------------------------------------------------------- sealing

(def seal-item "The block a pocket is sealed with." :cobblestone)

(defn seal-cells
  "The cells a seal of the pocket at cell c places a block in, in order: for each open side
   (openings) the cell beside c at the feet, then the one over it; then the cell over the head when
   it is no firm block."
  [world c]
  (vec (concat (for [s (openings world c)
                     dy [0 1]
                     :let [p (plus c [(first s) dy (second s)])]
                     :when (not (solid-at? world p))]
                 p)
               (when-not (roofed? world c) [(plus c [0 2 0])]))))

(defn sealed?
  "Is the site sealed: it names cells it placed blocks in (:pocket/placed) and every one is solid?"
  [world {:pocket/keys [placed]}]
  (boolean (and (seq placed) (every? #(solid-at? world %) placed))))

(defn seal-ops
  "The ops that seal the cell: a place of seal-item in each of cells, standing in the cell."
  [c cells]
  (mapv #(build/place-op % c nil :seal) cells))

(defn unseal-ops
  "The ops that open the pocket again: a dig of each placed cell, the highest first, from the cell."
  [c placed]
  (mapv #(build/dig-op % c nil :unseal) (reverse placed)))

(defn filler-held
  "How many of seal-item the bot holds."
  [world]
  (inventory/item-count world (recipe/item-id seal-item)))

(def seal-retry-ms 60000) ; ms after a seal failed anywhere before another is tried

(defn pickaxe-held?
  "Is a pickaxe in the inventory? A seal is opened by digging its blocks, ten seconds a block by
   hand: blaze-race-p job 2 was sealed in without one, `:unseal` dug four placed blocks for 44 s
   (10000 ms each) while fireballs came through the half-open pocket, and burned to death."
  [world]
  (boolean (some (fn [{:keys [item]}]
                   (some-> (recipe/item-name item)
                           name
                           (str/ends-with? "_pickaxe")))
                 (vals (:player/inventory world)))))

(defn can-seal?
  "Is the pocket at the feet sealable now: its cells (seal-cells) fewer than the filler held, a
   pickaxe to open it again (pickaxe-held?), no seal of this site failed (:pocket/unsealable?) and
   none anywhere within seal-retry-ms (:pocket/seal-failed-at: a block that could not be laid, no
   support or the body in the cell, is not tried at the next cell either while a blaze hits:
   blaze-race-p job 3 sealed, failed :no-support, walked 26 blocks and sealed again)?"
  [world {:pocket/keys [cell unsealable?]}]
  (let [n (count (seal-cells world cell))
        failed (:pocket/seal-failed-at world)]
    (and (not unsealable?)
         (or (nil? failed) (> (- (:time/now world 0) failed) seal-retry-ms))
         (pickaxe-held? world)
         (pos? n)
         (<= n (filler-held world)))))

(defn ops
  "The site as build ops: a dig of each cell still to dig, from the stand, then (unless prepare?:
   the pocket is dug ahead and not entered) standing in the cell. A pocket that exists is the one
   stand-in."
  ([site] (ops site false))
  ([{:pocket/keys [cell stand dig]} prepare?]
   (let [digs (mapv #(build/dig-op % stand nil :pocket-dig) dig)]
     (if prepare?
       digs
       (conj digs
             {:build/op :stand-in
              :build/pos cell
              :build/stand cell
              :build/step :pocket
              :build/done [:feet cell]})))))

;; ---------------------------------------------------------------- the intents

(defn finished
  "world with its running intent done, whatever its child did: a failure of a pocket's work is
   not the plan's failure, a pocket is a refuge and the run goes on without it. The site is the
   cell the feet stand in (here-site) to be sealed in place, or none."
  [world]
  (let [site (here-site world)]
    (-> world
        (assoc :player/controls {})
        (cond-> site (assoc :pocket/site site))
        (cond-> (nil? site) (dissoc :pocket/site))
        intent/done)))

(defmethod intent/run :pocket
  ;; {:intent/kind :pocket :intent/site site :intent/prepare? bool}: dig the pocket's cells from
  ;; the stand (build ops, as :build works them) and, unless prepare? (the pocket made ahead of the
  ;; tunnel's last blocks), walk in; done when the feet are in its cell. The site is held in the
  ;; world (:pocket/site) from the first tick. When it stops being one (valid?) or the work fails
  ;; (no way to the stand, a dig refused) the intent is done with the cell the feet stand in held to
  ;; be sealed there (finished), never a failure: blaze-race-j geared 4 and race 2 failed :pocket
  ;; three times in 0.4 s and the plan with it
  [world {:intent/keys [site prepare?] :as i} event]
  (let [at (path/cells world)
        world (cond-> (assoc world :pocket/site site)
                prepare? (assoc :pocket/prepared-at (:time/now world)))]
    (if-not (valid? world at site)
      (finished world)
      (let [plan (ops site (boolean prepare?))
            next (when (seq plan) (build/next-op world plan))]
        (if (nil? next)
          (-> world
              (assoc :player/controls {})
              intent/done)
          (let [w (build/work-op world i next event)]
            (if (intent/failed? (:plan/intent w)) (finished w) w)))))))

(defn work-ops
  "One tick of the running intent i working ops (a vector), through build/work-op; done when every
   one holds, and also when the work fails: a seal that cannot be laid is not the plan's failure.
   A failure notes the held site :pocket/unsealable? (when on-fail? is true), so the seal is not
   chosen again for it."
  ([world i ops event] (work-ops world i ops event false))
  ([world i ops event on-fail?]
   (let [next (build/next-op world ops)]
     (if (nil? next)
       (-> world
           (assoc :player/controls {})
           intent/done)
       (let [w (build/work-op world i next event)]
         (if (intent/failed? (:plan/intent w))
           (cond-> w
             true (assoc :player/controls {})
             (and on-fail? (:pocket/site w)) (assoc-in [:pocket/site :pocket/unsealable?] true)
             on-fail? (assoc :pocket/seal-failed-at (:time/now w 0))
             true intent/done)
           w))))))

(defmethod intent/run :seal
  ;; {:intent/kind :seal}: place seal-item in every open side of the pocket at the feet, at the feet
  ;; and the head, and over the head when it has no roof, standing in it; the cells are noted in the
  ;; held site (:pocket/placed) from the first tick, so sealed? and the unseal know them. Done when
  ;; they are all solid.
  [world i event]
  (let [site (:pocket/site world)
        cells (if (seq (:pocket/placed site))
                (:pocket/placed site)
                (seal-cells world (:pocket/cell site)))
        world (cond-> world
                (and site (empty? (:pocket/placed site)))
                (assoc-in [:pocket/site :pocket/placed] cells))]
    (if (and site (seq cells) (= (:pocket/cell site) (intent/feet world)))
      (work-ops world i (seal-ops (:pocket/cell site) cells) event true)
      (-> world
          (assoc :player/controls {})
          intent/done))))

(defmethod intent/run :unseal
  ;; {:intent/kind :unseal}: dig every cell the seal placed, the highest first, standing in the
  ;; pocket, and forget them (:pocket/placed empty) once none is solid. Done then.
  [world i event]
  (let [site (:pocket/site world)
        placed (:pocket/placed site)
        cell (:pocket/cell site)
        open? (every? #(not (solid-at? world %)) placed)]
    (cond (or (nil? site) (not= cell (intent/feet world)))
          (-> world
              (assoc :player/controls {})
              intent/done)
          open?
          (-> world
              (assoc :player/controls {})
              (assoc-in [:pocket/site :pocket/placed] [])
              intent/done)
          :else (work-ops world i (unseal-ops cell placed) event))))
