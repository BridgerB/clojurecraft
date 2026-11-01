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
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
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

(declare cannot-seal?)

(defn- feet-no-seal?
  "Is c the cell the feet stand in, and one a seal cannot finish (cannot-seal?)? site-near and
   current must not return it: here-site already refuses it, and a pocket-shaped cell can still
   be unsealable."
  [world c]
  (boolean (and c (:player/pos world) (= c (intent/feet world)) (cannot-seal? world c))))

(defn site-near
  "The nearest site to the feet by walking, or nil: of the cells reached (steps of walk within
   search-steps, search-nodes visited; at is path/cells) the first that is a pocket (a site with no
   dig), or else has a wall to dig a pocket into (dig-in), at each distance an existing pocket
   before a dug one. ok? is asked of the cell of a site: false drops it (outside a spawner's
   volume, within its wake). The cell the feet stand in is dropped when it cannot be sealed
   (feet-no-seal?)."
  ([world feet ok?] (site-near world feet ok? search-nodes))
  ([world feet ok? nodes]
   (let [at (path/cells world)]
     (loop [frontier [feet]
            seen #{feet}
            depth 0
            visited 0]
       (let [here (fn [c]
                    (when (and (pocket? world at c) (ok? c) (not (feet-no-seal? world c)))
                      {:pocket/cell c :pocket/stand nil :pocket/dig [] :pocket/placed []}))
             dug (fn [c]
                   (some (fn [s]
                           (let [site (dig-in world c s)]
                             (when (and site
                                        (ok? (:pocket/cell site))
                                        (not (feet-no-seal? world (:pocket/cell site))))
                               site)))
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
   (let [held (held-valid world)
         cell (:pocket/cell held)]
     (if (and held (ok? cell) (not (feet-no-seal? world cell)))
       held
       (site-near world feet ok? nodes)))))

(defn in?
  "Are the feet in the held site's cell, and is it still a valid? one? The bot is in cover: one
   opening, none when sealed, or a cell it is to seal."
  [world]
  (let [{:pocket/keys [cell] :as site} (:pocket/site world)]
    (boolean (and site (= cell (intent/feet world)) (valid? world (path/cells world) site)))))

(defn here-site
  "The site that seals the cell the feet stand in, in place, or nil when it is not standable or
   cannot be sealed (cannot-seal?): a cell with no dig, sealed by seal-ops on every open side and
   over the head. What a :pocket falls back to when no way to a pocket is found (blaze-race-j
   geared 4 and race 2: three :pocket no-path failures in 0.4 s ended the run at 17 hp). An open
   landing is standable and cannot be sealed (the roof has nothing to click). unlayable? misses
   that roof once the head reaches into it. Choosing the cell is the stall: z16 job 5 (plan
   41276280629) alternated that :pocket with :tunnel-to [2210 60 1793] from 233 s, still at
   [2148 72 1763], because steer drops the site the tick the bot lands and the row chooses it
   again."
  [world]
  (let [c (intent/feet world)]
    (when (and (path/standable? (path/cells world) c) (not (cannot-seal? world c)))
      {:pocket/cell c :pocket/stand nil :pocket/dig [] :pocket/placed [] :pocket/here? true})))

;; ---------------------------------------------------------------- sealing

(def seal-item "The block a pocket is sealed with." :cobblestone)

(defn clear-of-body
  "The cells that no part of the player's box at its exact position (place/inside-player?) and
   neither of the pocket's own cells (c and the one over it) are in: the server refuses a block
   into any player, and a standing one fills the feet and the head, a fractional x or z near a cell
   edge the neighbour too. Blaze-race-t job 5 stood sealing 68 minutes on `placing [2154 73 1750]
   refused with the body in it` at [2154.6 72.0 1750.6], blaze-race-m job 5 299 minutes on
   [2153 73 1752]; blaze-race-u job 5 five refusals of [2149 73 1760]."
  [world c cells]
  (let [own #{c (plus c [0 1 0])}]
    (filterv #(not (or (own %) (place/inside-player? world %))) cells)))

(defn open-seal-cells
  "The cells a seal of the pocket at cell c would fill, wherever the body stands: for each open side
   (openings) the cell beside c at the feet, then the one over it; then the cell over the head when it
   is no firm block. A cell the body reaches into is open all the same: blaze-race-z13 jobs 4 and 6
   stood off the middle of the cell (x 2341.7, z 2287.2), the side cell was in the body, the seal
   saw nothing to fill, `sealed?` read true and the bot held open under fireballs until dead."
  [world c]
  (vec (concat (for [s (openings world c)
                     dy [0 1]
                     :let [p (plus c [(first s) dy (second s)])]
                     :when (not (solid-at? world p))]
                 p)
               (when-not (roofed? world c) [(plus c [0 2 0])]))))

(defn wanted-seal-cells
  "The open-seal-cells that can be laid now: none of them in the body (clear-of-body). Some may not
   be placeable (placeable?)."
  [world c]
  (clear-of-body world c (open-seal-cells world c)))

(defn placeable?
  "Can a block be laid in cell p: a solid neighbour to click (place/support), or a cell under it
   that has one to build up from (build/foundation), and that cell not in the player? Blaze-race-z2
   landing 5 bridged at [2159.5 69 1746.5] over open air: the roof cell [2159 71 1746] had no
   neighbour and its foundation was the cell over the bot's own feet, refused with the body in
   it (the same cell t, u, x and y job 5 refused)."
  [world p]
  (let [f (build/foundation world p)] (boolean (and f (not (place/inside-player? world f))))))

(defn cannot-seal?
  "Does a seal of c need a block that has nothing to click, counting a cell the body currently
   fills? unlayable? asks only wanted-seal-cells, and clear-of-body drops a cell the box reaches
   into. The roof of an open landing is that cell once the head enters it (the box is 1.8 tall:
   feet at y 72.3 reach y 74), so unlayable? goes false while the roof still has no neighbour.
   A cell the body fills that already has a click (place/support) does not count: the body moves.
   z16 job 5's fold cut :tunnel-to for [2148 72 1763] at t=206299 on that miss."
  [world c]
  (boolean (some (fn [p] (and (not (placeable? world p)) (not (place/support world p))))
                 (open-seal-cells world c))))

(defn seal-cells
  "The cells a seal of the pocket at cell c places a block in: wanted-seal-cells that can be laid
   (placeable?)."
  [world c]
  (filterv #(placeable? world %) (wanted-seal-cells world c)))

(defn sealed?
  "Is the site sealed: it names cells it placed blocks in (:pocket/placed), every one is solid, and
   no cell a seal would still fill (wanted-seal-cells) is open? A wanted cell that cannot be laid (the roof over open air, nothing to click) keeps it false, and the site is no refuge: unlayable? says so, and the blaze row's steer drops the site and remembers it (:pocket/failed). Blaze-race-z7 job 2 sat 19 s in
   :pocket-hold under fireballs and burn with four placed cells solid and the four on its z sides
   open: a :seal that ended :done when its stale list was solid, the bot having moved, and this test
   said sealed (flip_trace2, run 41212534031)."
  [world {:pocket/keys [cell placed]}]
  (boolean (and (every? #(solid-at? world %) placed)
                (or (nil? cell) (empty? (open-seal-cells world cell)))
                ;; a site that names no cell and has no cell to seal is a cell shut on every side (a pocket
                ;; the bot dug into rock and stands in): sealed. Without a cell it is nothing (nil cell).
                (or (seq placed) (some? cell)))))

(defn unlayable?
  "Does the pocket at cell c want a block that cannot be laid (wanted-seal-cells that are not
   placeable?): the roof over open air, a side with nothing to click? Such a pocket cannot be
   sealed and against blazes, which fly and shoot down, is no refuge (blaze-race-z7 landing 1
   burned in one)."
  [world c]
  (not= (count (seal-cells world c)) (count (wanted-seal-cells world c))))

(defn seal-ops
  "The ops that seal the cell: a place of item (default seal-item) in each of cells, standing in
   the cell."
  ([c cells] (seal-ops c cells seal-item))
  ([c cells item] (mapv #(assoc (build/place-op % c nil :seal) :build/item item) cells)))

(defn unseal-ops
  "The ops that open the pocket again: a dig of each placed cell, the highest first, from the cell."
  [c placed]
  (mapv #(build/dig-op % c nil :unseal) (reverse placed)))

(defn seal-filler
  "The block to seal n cells with, or nil: the first of stairs/filler's list (cobblestone, then
   the Nether's own rock, which a tunnel carries by the hundred) the bot holds n of. Blaze-race-v
   job 5 laid its 64 cobblestone into a tunnel's floors in 8 minutes, held 160 netherrack, could
   not seal, and held open under fire until dead (hp 14 to 1 in 26 s)."
  [world n]
  (stairs/filler world n))

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
  (let [open (open-seal-cells world cell)
        ;; a cell the body reaches into is laid once the :seal has centred the bot
        n (count (filterv #(or (place/inside-player? world %) (placeable? world %)) open))
        failed (:pocket/seal-failed-at world)]
    (and (not unsealable?)
         (= n (count open))
         (or (nil? failed) (> (- (:time/now world 0) failed) seal-retry-ms))
         (pickaxe-held? world)
         (pos? n)
         (some? (seal-filler world n)))))

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

(def seal-limit-ms 20000) ; ms a seal may take before it is given up

(defn seal-overdue?
  "Has the running seal i been at it longer than seal-limit-ms? blaze-race-t job 5 stood sealing
   68 minutes: `build: placing [2154 73 1750] refused with the body in it` every 3 s, a block
   into the cell over its own feet, and no refusal count ever ended it."
  [world {:intent/keys [started]}]
  (boolean (and started (> (- (:time/now world 0) started) seal-limit-ms))))

(defn give-up-seal
  "The world with the running seal ended and noted failed (:pocket/unsealable?,
   :pocket/seal-failed-at), as work-ops does for a placement that failed."
  [world]
  (cond-> (assoc world :player/controls {} :pocket/seal-failed-at (:time/now world 0))
    (:pocket/site world) (assoc-in [:pocket/site :pocket/unsealable?] true)
    true intent/done))

(defn seal-at
  "One tick of a :seal whose site names cell. The held cells are filtered each tick, not only when
   first noted: blaze-race-x job 5 (build: placing [2151 73 1757] refused with the body in it)
   refused two seals in a row with seal-cells already clear of the body."
  [world i event site cell]
  (let [cells
        (clear-of-body world cell (distinct (concat (:pocket/placed site) (seal-cells world cell))))
        world (cond-> world
                (and site (not= cells (:pocket/placed site)))
                (assoc-in [:pocket/site :pocket/placed] cells))]
    (cond (and site
               (empty? cells)
               (= (:pocket/cell site) (intent/feet world))
               (seq (open-seal-cells world (:pocket/cell site))))
          ;; every open cell is in the body: centre in the cell, then they can be laid
          (if (seal-overdue? world i)
            (give-up-seal world)
            (-> world
                (intent/assoc-intent :intent/started (or (:intent/started i) (:time/now world 0)))
                (assoc :player/controls
                       (intent/toward world
                                      (assoc (physics/centre (:pocket/cell site))
                                             1
                                             (double (second (:pocket/cell site))))
                                      0))))
          (not (and site (seq cells) (= (:pocket/cell site) (intent/feet world))))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (seal-overdue? world i) (give-up-seal world)
          :else
          (-> world
              (intent/assoc-intent :intent/started (or (:intent/started i) (:time/now world 0)))
              (work-ops
               i
               (seal-ops (:pocket/cell site) cells (or (seal-filler world (count cells)) seal-item))
               event
               true)))))

(defmethod intent/run :seal
  ;; {:intent/kind :seal}: place seal-item in every open side of the pocket at the feet, at the feet
  ;; and the head, and over the head when it has no roof, standing in it; the cells are noted in the
  ;; held site (:pocket/placed) from the first tick, so sealed? and the unseal know them. Done when
  ;; they are all solid. No cell (steer has dropped :pocket/site, or it never had one) fails
  ;; :no-cell: seal-cells would call pocket/plus on nil and kill the bot's loop (z16 fold,
  ;; probes 41303425892 and 41304181213).
  [world i event]
  (let [site (:pocket/site world)
        cell (:pocket/cell site)]
    (if-not cell
      (-> world
          (assoc :player/controls {})
          (intent/fail :no-cell))
      (seal-at world i event site cell))))

(def reopen-ms 1500) ; ms after a hit within which an opening pocket is shut again, not opened further

(defn shot-open?
  "Is the pocket opened (a placed cell no longer solid) and the bot burning or struck within
   reopen-ms? The unseal then ends, so a :seal lays the open cells again. Blaze-race-v job 2
   unsealed at full health, was hit through the first opened cell, and the unseal (a defence,
   plan/holds?) dug on for 27 s while it burned: hp 20 to 0.4."
  [world placed]
  (boolean (and (some #(not (solid-at? world %)) placed)
                (or (:player/on-fire? world)
                    (<= (- (:time/now world 0) (:player/struck-at world -1000000)) reopen-ms)))))

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
          (shot-open? world placed)
          (-> world
              (assoc :player/controls {})
              intent/done)
          :else (work-ops world i (unseal-ops cell placed) event))))
