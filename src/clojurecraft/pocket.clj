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
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
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
      {:pocket/cell pf :pocket/stand stand :pocket/dig (filterv #(solid-at? world %) [pf ph])})))

(defn valid?
  "Is the site still one: a pocket now (pocket?), or a dig-in from its stand toward its cell that
   still holds? A fire or a lava next to it, or a wall dug through, makes it none."
  [world at {:pocket/keys [cell stand] :as site}]
  (boolean (and site
                (or (pocket? world at cell)
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
  [world feet ok?]
  (let [at (path/cells world)]
    (loop [frontier [feet]
           seen #{feet}
           depth 0
           visited 0]
      (let [here (fn [c]
                   (when (and (pocket? world at c) (ok? c))
                     {:pocket/cell c :pocket/stand nil :pocket/dig []}))
            dug (fn [c]
                  (some (fn [s]
                          (let [site (dig-in world c s)]
                            (when (and site (ok? (:pocket/cell site))) site)))
                        sides))
            hit (or (some here frontier) (some dug frontier))]
        (cond hit hit
              (or (empty? frontier) (>= depth search-steps) (>= visited search-nodes)) nil
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
                (recur next (into seen next) (inc depth) (+ visited (count next)))))))))

(defn current
  "The site to use now: the one the world holds (:pocket/site) while it is valid?, else the nearest
   found (site-near), else nil."
  [world feet ok?]
  (let [held (:pocket/site world)]
    (if (valid? world (path/cells world) held) held (site-near world feet ok?))))

(defn in?
  "Are the feet in the held site's cell, and is it a pocket? The bot is in cover: one opening."
  [world]
  (let [{:pocket/keys [cell] :as site} (:pocket/site world)]
    (boolean (and site (= cell (intent/feet world)) (pocket? world (path/cells world) cell)))))

(defn ops
  "The site as build ops: a dig of each cell still to dig, from the stand, then standing in the
   cell. A pocket that exists is the one stand-in."
  [{:pocket/keys [cell stand dig]}]
  (conj (mapv #(build/dig-op % stand nil :pocket-dig) dig)
        {:build/op :stand-in
         :build/pos cell
         :build/stand cell
         :build/step :pocket
         :build/done [:feet cell]}))

;; ---------------------------------------------------------------- the :pocket intent

(defmethod intent/run :pocket
  ;; {:intent/kind :pocket :intent/site site}: dig the pocket's cells from the stand (build ops,
  ;; as :build works them) and walk in; done when the feet are in its cell. The site is held in
  ;; the world (:pocket/site) from the first tick. Failed :no-pocket when it stopped being one
  ;; (valid?): a fire, a lava, a wall gone.
  [world {:intent/keys [site] :as i} event]
  (let [at (path/cells world)
        world (assoc world :pocket/site site)]
    (cond (not (valid? world at site))
          (-> world
              (assoc :player/controls {})
              (dissoc :pocket/site)
              (intent/fail :no-pocket))
          :else
          (let [next (build/next-op world (ops site))]
            (if (nil? next)
              (-> world
                  (assoc :player/controls {})
                  intent/done)
              (build/work-op world i next event))))))
