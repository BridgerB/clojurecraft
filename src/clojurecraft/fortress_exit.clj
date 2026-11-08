(ns clojurecraft.fortress-exit
  "The bot's exit from the blaze fortress into the pearl hunt: the sealed tunnel out, the pocket bore, the blaze refuge and the answer to a strike on the way."
  (:require [clojure.math :as math]
            [clojurecraft.avoided :as avoided]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.combat :as combat]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(defn room-waypoint
  "The first walk target for getting from the feet to target without going through the blaze room
   (room-boxes), or target itself when the line is clear or no better is found. From inside the
   room: the newest fortress trail cell outside every box, the way the bot came in. From outside
   with the line through it: the newest trail cell outside the boxes from which the line to
   target is clear, else the nearest corner of a box, a block past its margin, whose two legs
   are clear."
  [world target]
  (let [boxes (pcover/room-boxes world)
        feet (intent/feet world)]
    (cond (empty? boxes) target
          (pcover/in-room? boxes feet)
          (or (->> (pcover/trail-cells world)
                   (remove #(pcover/in-room? boxes %))
                   first)
              (let [corners (for [[x0 _ z0 x1 _ z1] boxes
                                  cx [(dec x0) (inc x1)]
                                  cz [(dec z0) (inc z1)]
                                  :let [c [cx (nth feet 1) cz]]]
                              c)
                    total #(+ (physics/distance feet %) (physics/distance % target))]
                (first (sort-by total corners)))
              target)
          (not (pcover/crosses-room? boxes feet target)) target
          :else
          (or (->> (pcover/trail-cells world)
                   (remove #(pcover/in-room? boxes %))
                   (filter #(and (not (pcover/crosses-room? boxes feet %))
                                 (not (pcover/crosses-room? boxes % target))))
                   first)
              (let [corners (for [[x0 _ z0 x1 _ z1] boxes
                                  cx [(dec x0) (inc x1)]
                                  cz [(dec z0) (inc z1)]
                                  :let [c [cx (nth feet 1) cz]]
                                  :when (and (not (pcover/in-room? boxes c))
                                             (not (pcover/crosses-room? boxes feet c)))]
                              c)
                    total #(+ (physics/distance feet %) (physics/distance % target))]
                ;; a corner both legs clear of the room, else the one whose first leg is clear and
                ;; the whole way shortest: the next call, from the corner, picks the next corner
                (or (->> corners
                         (filter #(not (pcover/crosses-room? boxes % target)))
                         (sort-by total)
                         first)
                    (first (sort-by total corners))))
              target))))

(defn fortress-points
  "The positions of known spawners and fortress bricks in the bot's dimension."
  [world]
  (concat (memory/positions-now world [blaze/spawner]) (fortress/bricks world)))

(defn exit-anchors
  "The positions the tunnel-out leaves: the known spawners when any is known, else every
   fortress point. A fortress runs a hundred blocks and more, so the bricks seen through the
   tunnel would keep the bot near it: the blaze room, the spawner, is what the exit leaves."
  [world]
  (let [spawners (memory/positions-now world [blaze/spawner])]
    (if (seq spawners) spawners (fortress-points world))))

(defn escape-near?
  "Is the bot within fortress-escape-reach of any exit-anchors position (the spawner, else any fortress point)?"
  [world]
  (let [feet (intent/feet world)]
    (boolean (some #(or (<= (physics/distance feet %) pparams/fortress-escape-reach)
                        (<= (physics/horizontal-distance feet %) pparams/fortress-escape-reach))
                   (exit-anchors world)))))

(defn near-fortress?
  "Is the bot within fortress-escape-reach of any known spawner or fortress brick?"
  [world]
  (let [feet (intent/feet world)
        pts (fortress-points world)]
    (boolean (some #(or (<= (physics/distance feet %) pparams/fortress-escape-reach)
                        (<= (physics/horizontal-distance feet %) pparams/fortress-escape-reach))
                   pts))))

(defn surrounded-by-lava?
  "Is the bot surrounded by lava on every side: every lateral heading from feet has lava
   at body or floor level, leaving no dry direction to tunnel into?"
  [world feet]
  (let [lava? (fn [c] (contains? blocks/lava-states (terrain/block-at world c)))
        horizontal-dirs [[1 0] [-1 0] [0 1] [0 -1]]]
    (every? (fn [[dx dz]]
              (or (lava? (fortress/plus feet [dx 0 dz]))
                  (lava? (fortress/plus feet [dx 1 dz]))
                  (lava? (fortress/plus feet [dx -1 dz]))))
            horizontal-dirs)))

(defn can-tunnel-out?
  "Can the bot tunnel out of the fortress: holds a serviceable pickaxe and is not
   surrounded by lava on every side?"
  [world]
  (let [feet (intent/feet world)]
    (boolean (and (pupkeep/pickaxe? world) (not (surrounded-by-lava? world feet))))))

(defn fortress-surface-y
  "The surface y to climb to once clear of the fortress: the forest height if known,
   else the spawner or starting height."
  [world]
  (or (some-> (psense/nearest-forest world)
              second
              inc)
      (some-> (blaze/nearest-spawner world)
              second)
      (second (intent/feet world))
      64))

(defn enclosed?
  "Is cell [x y z] enclosed: solid overhead ([x (+ y 2) z]) and solid on both lateral sides
   (either (dec x) and (inc x), or (dec z) and (inc z), at standing or head level)?"
  [world [x y z]]
  (let [roof? (boolean (some-> (terrain/block-at world [x (+ y 2) z])
                               blocks/solid?))
        x-walls? (and (blaze/walled? world [(dec x) y z]) (blaze/walled? world [(inc x) y z]))
        z-walls? (and (blaze/walled? world [x y (dec z)]) (blaze/walled? world [x y (inc z)]))]
    (boolean (and roof? (or x-walls? z-walls?)))))

(defn side-boreable?
  "Is candidate cell cand beside an open corridor suitable to bore a 1x2 tunnel through
   at the same y: solid floor below, solid ceiling above, solid wall (or already bored enclosed cell)
   at feet/eye levels, and no lava?"
  [world cand cy]
  (let [[x y z] cand
        floor [x (dec cy) z]
        roof [x (+ cy 2) z]]
    (and (= y cy)
         (boolean (some-> (terrain/block-at world floor)
                          blocks/solid?))
         (boolean (some-> (terrain/block-at world roof)
                          blocks/solid?))
         (or (boolean (some-> (terrain/block-at world cand)
                              blocks/solid?))
             (boolean (some-> (terrain/block-at world [x (inc cy) z])
                              blocks/solid?))
             (enclosed? world cand))
         (not (stairs/over-fluid? world floor))
         (not= :lava (terrain/fluid-kind world cand))
         (not= :lava
               (some-> (terrain/block-at world cand)
                       blocks/name-of))
         (not-any? #(or (= :lava (terrain/fluid-kind world %))
                        (= :lava
                           (some-> (terrain/block-at world %)
                                   blocks/name-of)))
                   (fortress/neighbours cand)))))

(defn side-bore-cell
  "The cell in the solid netherrack or brick wall beside open corridor cell c at the same y,
   or nil when no solid wall is found. Never a cell within 2 blocks of the feet: the bot's own
   bored tunnel cell beside the corridor is enclosed and boreable, and a tunnel to it (or to an
   adjacent cell, inside :intent/reach 1.5) ends :done at once without a step, so the planner
   would choose it again every tick."
  [world [cx cy cz]]
  (let [feet (intent/feet world)
        offsets (for [d [1 2 3 4] [dx dz] [[-1 0] [1 0] [0 -1] [0 1]]] [(* d dx) (* d dz)])]
    (when (and cx cy cz)
      (->> offsets
           (map (fn [[dx dz]] [(+ cx dx) cy (+ cz dz)]))
           (filter #(side-boreable? world % cy))
           (filter #(>= (physics/distance feet %) 2.0))
           (sort-by #(physics/distance feet %))
           first))))

(defn nearest-trail-index
  "The index in trail of the cell closest to feet, or nil."
  [feet trail]
  (when (seq trail)
    (let [indexed (map-indexed vector trail)]
      (first (apply min-key (fn [[_ c]] (physics/distance feet c)) indexed)))))

(defn pocket-bore-heading
  "The fixed cardinal exit heading [dx dz] for the pocket bore, or nil with no spawner known:
   from the spawner toward the start of the inbound trail (the oldest :plan/trail cell, where
   the bot entered the fortress ground), else toward the nearest known forest, else east of
   the spawner (never explore's target: its eight route searches are costly and it moves with the
   feet). Derived only from facts that do not move as the bot steps (the spawner, the trail's
   start, the forest), never from the feet, so the heading is the same at every cell of the bore
   and cannot flip between two targets."
  [world]
  (when-let [sp (blaze/nearest-spawner world)]
    (let [[sx _ sz] sp
          start (first (:plan/trail world []))
          forest (psense/nearest-forest world)
          [tx tz] (cond (and start (not= [sx sz] [(first start) (last start)]))
                        [(first start) (last start)]
                        forest [(first forest) (last forest)]
                        :else [(inc sx) sz])
          dx (- (long tx) (long sx))
          dz (- (long tz) (long sz))]
      (cond (and (zero? dx) (zero? dz)) [1 0]
            (>= (abs dx) (abs dz)) [(long (math/signum (double dx))) 0]
            :else [0 (long (math/signum (double dz)))]))))

(defn pocket-bore-enclosed?
  "Is stepping from feet into the next cell by heading [dx dz] a bore through solid rock:
   the two cells the body would open and every lateral neighbour of both (except the cell
   the bot stands in) is a loaded solid, the floor under and the roof over them solid, and
   no neighbour is lava? A step that would open into air, a corridor or a room is not."
  [world feet [dx dz]]
  (let [to [(+ (first feet) dx) (second feet) (+ (last feet) dz)]
        cells [to (update to 1 inc)]
        others (fn [c]
                 (remove #{feet (update feet 1 inc)}
                         (mapcat (fn [[ox oy oz]] [(update c 0 + ox) (update c 1 + oy)
                                                   (update c 2 + oz)])
                          [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])))]
    (boolean (and (every? #(pocket/solid-loaded? world %) cells)
                  (pocket/solid-loaded? world (update to 1 dec))
                  (pocket/solid-loaded? world (update to 1 + 2))
                  (every? #(pocket/solid-loaded? world %) (mapcat others cells))
                  (not-any? #(= :lava
                                (some-> (terrain/block-at world %)
                                        blocks/name-of))
                            (concat cells (mapcat others cells)))))))

(defn pocket-bore-target
  "The [x y z] target of the pocket bore from the feet, or nil: bore-reach ahead along the
   fixed heading (pocket-bore-heading) at the feet's own y when the next step bores through solid
   rock (pocket-bore-enclosed?); when that step would open into air or a corridor, a side-step
   target bore-reach along a perpendicular heading whose next step is enclosed, at the same
   y; when neither is enclosed, the reverse heading as a last resort, so a pocket beside a
   fortress room whose heading and side steps all border the room's air still bores through
   the rock behind it instead of holding forever. A target the instant-churn guard (#719) has
   blacklisted (:plan/blacklist, fail-intent on :instant-churn/:blocked) is passed over for the
   next enclosed heading, so the guard never rules out the bore's own steps. Nil when no heading
   is enclosed and unblacklisted. Never below the feet."
  [world]
  (let [feet (intent/feet world)
        [_ fy _] feet
        bl (avoided/all-of world :blacklist)]
    (when-let [[dx dz] (pocket-bore-heading world)]
      (let [along (fn [[hx hz]] [(+ (first feet) (* pparams/bore-reach hx)) fy
                                 (+ (last feet) (* pparams/bore-reach hz))])
            headings [[dx dz] [(- dz) dx] [dz (- dx)] [(- dx) (- dz)]]]
        (some (fn [h]
                (let [t (along h)]
                  (when (and (pocket-bore-enclosed? world feet h) (not (contains? bl t))) t)))
              headings)))))

(defn fortress-retrace-target
  "The next target cell along the inbound trail heading toward the exit, or nil:
   retraces enclosed cells directly, or bores a parallel 1x2 tunnel through solid
   netherrack/brick wall beside an open corridor cell at the same y. Never steps down (dy >= 0)."
  [world]
  (let [feet (intent/feet world)
        [_ fy _] feet
        trail (distinct (pcover/trail-cells world))]
    (when (seq trail)
      (let [idx (nearest-trail-index feet trail)
            remaining (when idx (drop (inc idx) trail))
            ;; only a cell 2 or more away: one nearer is inside the tunnel's reach (1.5) and
            ;; ends it :done without a step, and the same cell is chosen again;
            ;; a blacklisted cell (#719's guard, fail-intent) is passed over the same way
            target-c (some #(when (and (>= (physics/distance feet %) 2.0)
                                       (not (contains? (avoided/all-of world :blacklist) %)))
                              %)
                           remaining)]
        (when target-c
          (let [[cx cy cz] target-c
                target-y (max (long fy) (long cy))]
            (cond (enclosed? world target-c) [cx target-y cz]
                  :else (or (side-bore-cell world [cx target-y cz]) [cx target-y cz]))))))))

(defn fortress-exit-dir
  "The horizontal unit direction [dx dz] away from the fortress: toward nearest-forest if known,
   else away from the nearest spawner or brick. Never explore's: its eight route searches
   (far-ends) are costly, several a tick through fortress-escaping?, and a bored tunnel goes
   through rock, so a walk's route end says nothing of its way."
  [world]
  (let [feet (intent/feet world)
        [fx _ fz] feet
        target (psense/nearest-forest world)
        sp (when-not target (or (blaze/nearest-spawner world) (fortress/nearest-brick world)))
        [tx tz] (cond target [(first target) (last target)]
                      sp
                      (let [dx (- fx (first sp))
                            dz (- fz (last sp))]
                        (cond (and (zero? dx) (zero? dz)) [1 0]
                              :else [(+ fx dx) (+ fz dz)]))
                      :else [(inc fx) fz])
        ddx (double (- tx fx))
        ddz (double (- tz fz))
        d (math/sqrt (+ (* ddx ddx) (* ddz ddz)))]
    (if (> d 0.001) [(/ ddx d) (/ ddz d)] [1.0 0.0])))

(defn fortress-exit-horizontal
  "A 2D point [exit-x exit-z] along dir that is > fortress-escape-reach from every
   known exit anchor (exit-anchors: the spawners, else the fortress points)."
  [world [fx fz] [dir-x dir-z]]
  (let [pts (exit-anchors world)]
    (loop [dist 52.0]
      (let [candidate [(math/round (+ (double fx) (* (double dist) (double dir-x))))
                       (math/round (+ (double fz) (* (double dist) (double dir-z))))]]
        (if (or (>= dist 120.0)
                (every? #(> (physics/horizontal-distance [(first candidate) 0 (second candidate)]
                                                         [(first %) 0 (last %)])
                            pparams/fortress-escape-reach)
                        pts))
          candidate
          (recur (+ dist 8.0)))))))

(defn fresh-tunnel-target
  "The [x y z] target for the sealed fortress tunnel-out, derived from the feet now: the pocket bore along the fixed
   heading (pocket-bore-target) through solid rock at the same y, else a retrace of the inbound
   path through enclosed cells, boring parallel to open corridors at the same y. Once
   beyond 48 blocks from the fortress, surfaces to surface-y. Moves with the feet: the held
   target of fortress-tunnel-target is what the hunt follows."
  [world]
  (let [pts (fortress-points world)]
    (when (seq pts)
      (let [feet (intent/feet world)
            [fx fy fz] feet
            near? (escape-near? world)
            surface-y (fortress-surface-y world)
            ;; the exit only when a fallback needs it: the bore and the retrace answer first
            exit (delay (fortress-exit-horizontal world
                                                  [fx fz]
                                                  (if-let [h (pocket-bore-heading world)]
                                                    [(double (first h)) (double (second h))]
                                                    (fortress-exit-dir world))))
            bore (when near? (or (pocket-bore-target world) (fortress-retrace-target world)))]
        (cond
          ;; Bore from the pocket along the fixed heading through solid rock, else
          ;; retrace the inbound trail or side-bore parallel to an open corridor
          bore bore
          ;; Fallback horizontal tunnel away from fortress at current y level
          near? [(first @exit) fy (second @exit)]
          ;; Surface once beyond 48 blocks from fortress
          (< (long fy) (long surface-y)) [(first @exit) surface-y (second @exit)]
          ;; Surfaced and clear of fortress
          :else nil)))))

(defn held-exit-target
  "The held tunnel-out target (:pearls/exit-target), or nil when none is held, the feet are within
   exit-reach of it (reached) or a walk or tunnel to it failed (:plan/blacklist)."
  [world]
  (let [held (:pearls/exit-target world)]
    (when (and held
               (>= (physics/distance (intent/feet world) held) pparams/exit-reach)
               (not (contains? (avoided/all-of world :blacklist) held)))
      held)))

(defn given-up-column?
  "Is the [x z] column of target t within watch-same of a column the exit was given up at
   (:exit-columns, pmove/watch-progress)? The exit's fresh target flips between cells a block apart."
  [world t]
  (boolean (some (fn [[cx cz]]
                   (<= (physics/horizontal-distance [cx 0 cz] [(first t) 0 (last t)])
                       pparams/watch-same))
                 (avoided/all-of world :exit-columns))))

(defn fortress-tunnel-target
  "The [x y z] target for the sealed fortress tunnel-out: the one held (held-exit-target) until
   reached or failed, else fresh-tunnel-target; nil once the fresh one is nil (clear of the
   fortress), however long a target has been held. A fresh target is bore-reach ahead of the feet
   and a blocked step turns the bore round, so re-derived each act it flipped between two
   targets four blocks apart for an hour of :tunnel-to, wearing the pickaxe out (sim
   d3ce99b R1-24-1: [-188 78 163] and [-188 78 160], 620 :tunnel-to intents, no pearl). Nil too
   when its column [x z] is one the exit was given up at for want of progress (:exit-columns, move/watch-progress): a
   first failed tunnel is not the end of an exit (R4/1 reached its forest through exits that failed once), no progress is."
  [world]
  (when (seq (fortress-points world))
    (when-let [fresh (fresh-tunnel-target world)]
      (let [t (or (held-exit-target world) fresh)] (when-not (given-up-column? world t) t)))))

(defn fortress-escaping?
  "Is the bot leaving the fortress via sealed tunnel-out: inside/near the fortress,
   or still surfacing underground while escaping? Never in a warped forest (psense/in-forest?): the
   hunt is there. Sim R4/1 seed 1 (issue #263) was in its forest from minute 8.7, 81 endermen in
   sight, and still tunnelled the fortress exit at minute 47."
  [world]
  (boolean (and (= dimension/nether (dimension/of world))
                (seq (fortress-points world))
                (not (psense/in-forest? world))
                (fortress-tunnel-target world))))

(defn fortress-tunnel-out
  "The :tunnel-to that leaves the fortress via a sealed tunnel,
   retracing the inbound path through enclosed cells, boring parallel to open corridors
   at the same y, never stepping down through a floor, and surfacing beyond 48 blocks."
  [world]
  (when-let [target (fortress-tunnel-target world)]
    {:intent/kind :tunnel-to
     :intent/target target
     :intent/reach 1.5
     :intent/climb 0
     :intent/no-down? true
     :intent/steps fortress/steps-per-tunnel
     :intent/seal-behind? true
     :intent/for :pearls}))

(defn tunnel-answer
  "The intent that answers a melee mob that has just struck the bot while it bores out of the
   fortress (fortress-escaping?, can-tunnel-out?), or nil: the striker (combat/striker) is not a
   ranged kind and stands within the sword's reach (combat/in-reach?). At or over the danger-line
   (combat/danger-line) a :fight held where the bot stands (:intent/hold?, :intent/yield?: it ends
   under retreat-health); a kind of combat/avoided only at combat/avoided-health or more and with no
   other of those within defend-radius; under those lines the cover (combat/cover: a block laid
   between, a meal), never the fight and never the wait that ends the plan. The hunt answers
   wither skeletons and hoglins itself (combat/answering), so the :defend row leaves them."
  [world]
  (when (and (fortress-escaping? world) (can-tunnel-out? world))
    (when-let [[eid e] (combat/striker world)]
      (when (and (not (contains? pparams/ranged-kinds (entity/kind e))) (combat/in-reach? world e))
        (let [hp (:player/health world 20.0)
              avoided? (contains? combat/avoided (entity/kind e))
              pack? (some (fn [[oid o]]
                            (and (not= oid eid)
                                 (contains? combat/avoided (entity/kind o))
                                 (<= (physics/distance (:player/pos world) (:entity/pos o))
                                     combat/defend-radius)))
                          (entity/hostiles world))
              hurt? (or (< hp (combat/danger-line world))
                        (and avoided? (or pack? (< hp combat/avoided-health))))
              cover (when hurt? (combat/cover world e))]
          (cond (not hurt?)
                {:intent/kind :fight :intent/target eid :intent/hold? true :intent/yield? true}
                (contains? cover :intent/kind) (assoc cover :intent/wall? true)
                :else nil))))))

(defn pocket-bore
  "The sealed-pocket phase of the fortress exit, or nil when the bore itself should go on:
   in the blaze pocket (pocket/in?, the stage-17 refuge the rods were fought from), seal it
   when it can be sealed (:seal), and while sealed and under full health eat (blaze/meal)
   or hold (:pocket-hold) until full; hurt outside a pocket after a hit while boring, get
   into the blaze row's refuge (blaze/pocket-go, else blaze/here-intent sealed in place)
   before going on. A seal the server rejected (:rejected) leaves the pocket open and unsealed:
   then a hurt bot eats or holds in it instead of boring out exposed. Sealed at full health, or in
   no pocket at full health, nil: fortress-tunnel-out bores from where the bot stands along the
   fixed heading (pocket-bore-heading)."
  [world]
  (when (and (fortress-escaping? world) (can-tunnel-out? world))
    (let [site (:pocket/site world)
          in? (pocket/in? world)
          sealed? (and in? (pocket/sealed? world site))
          hp (:player/health world 20.0)]
      (cond (and in?
                 (not sealed?)
                 (>= hp blaze/low-health)
                 (blaze/reach-blaze world)
                 (blaze/fightable-blaze world))
            ;; a blaze in reach of the open pocket is struck, never held under
            (intent/why :pocket-bore/and-in?-not-sealed?-hp-blaze-low-health-blaze-re
                        {:intent/kind :fight
                         :intent/target (first (blaze/fightable-blaze world))
                         :intent/to-the-end? true
                         :intent/yield? true})
            (and in? (not sealed?) (pocket/can-seal? world site))
            (intent/why :pocket-bore/and-in?-not-sealed?-pocket-can-seal?-world-site
                        {:intent/kind :seal})
            (and in? (< hp 20.0))
            (intent/why :pocket-bore/and-in?-hp-20-0
                        (or (blaze/meal world) {:intent/kind :pocket-hold :intent/heal-to 20.0}))
            (and (not in?) (< hp 20.0))
            (intent/why :pocket-bore/and-not-in?-hp-20-0
                        (or (blaze/pocket-go world (blaze/nearest-spawner world) false)
                            (blaze/here-intent world)))
            :else (intent/why :pocket-bore/else nil)))))

(declare pearls-stage?)

(defn sealed-full?
  "Is the bot sealed in its blaze pocket at full health, not burning: the moment the blaze
   row's refuge hands the fortress exit over to the pocket bore?"
  [world]
  (boolean (and (pocket/in? world)
                (pocket/sealed? world (:pocket/site world))
                (>= (:player/health world 20.0) 20.0)
                (not (:player/on-fire? world)))))

(defn refuge-expired?
  "Has the blaze refuge held this rod bot at full health for refuge-leave-ms or more since it
   first kept it (:pearls/refuge-since, track-refuge)? Then the sealed exit goes anyway, even
   with a blaze watching: the spawner refills watchers every spawn cycle, so the watcher gate
   alone held sim rod bots at the pocket fighting and collecting for the whole horizon (22 of
   52 sim timeouts on 0ace52e, TRACE :fight 21480, :collect-drops 21696). Full health only:
   a hurt bot is never sent out, and never under danger health."
  [world]
  (boolean (and (:pearls/refuge-since world)
                (>= (:player/health world 20.0) 20.0)
                (>= (- (:time/now world 0) (:pearls/refuge-since world)) pparams/refuge-leave-ms))))

(defn track-refuge
  "The world with :pearls/refuge-since noted the first tick the refuge is in charge after the
   rods, and :pearls/refuge-done set once it has expired (refuge-expired?), so the exit stays
   handed over. Called from the :pearls steer every tick."
  [world]
  (cond (or (:pearls/refuge-done world) (not (pearls-stage? world))) world
        (refuge-expired? world) (assoc world :pearls/refuge-done true)
        (and (nil? (:pearls/refuge-since world))
             (= dimension/nether (dimension/of world))
             (blaze/nearest-spawner world)
             (or (pocket/in? world)
                 (fortress-escaping? world)
                 (<= (physics/distance (:player/pos world)
                                       (physics/centre (blaze/nearest-spawner world)))
                     32.0)))
        (assoc world :pearls/refuge-since (:time/now world 0))
        :else world))

(defn blaze-refuge
  "The blaze row's survival intent after the rods, or nil once it has handed over: while the
   pearl stage is in play in the Nether near the spawner and the bot has not yet been sealed
   in its pocket at full health (:pearls/refuge-done, noted by steer at sealed-full?), the
   blaze row stays in charge — a committed fight at hand (blaze/commit-fight, never under
   danger health), the refuge (blaze/refuge-now), the way to the pocket (blaze/pocket-go) or
   a pocket sealed where it stands (blaze/here-intent); in the pocket, its hold (blaze/hold-intent:
   seal, meal, hold), sealing first when it can. The pearl hunt has none of this, and took over
   the tick the seventh rod was held, out of the pocket grabbing a rod, hurt and watched:
   14 sim rod bots on main and the pocket-bore branch died at the spawner within a minute,
   1.4 to 2.8 blocks from the rod. Reused, not copied: no blaze logic lives here."
  [world]
  (when (and (pearls-stage? world)
             (= dimension/nether (dimension/of world))
             (not (:pearls/refuge-done world))
             (not (refuge-expired? world))
             (not (sealed-full? world))
             (blaze/nearest-spawner world)
             (or (pocket/in? world)
                 (fortress-escaping? world)
                 (<= (physics/distance (:player/pos world)
                                       (physics/centre (blaze/nearest-spawner world)))
                     32.0)))
    (let [sp (blaze/nearest-spawner world)]
      (if (pocket/in? world)
        (or (blaze/commit-fight world)
            (blaze/hold-intent world)
            ;; hold-intent is nil with a blaze in reach: the row strikes it (its fight
            ;; branch), never holds in the open pocket while it hits
            (when-let [[eid] (and (blaze/reach-blaze world) (blaze/fightable-blaze world))]
              {:intent/kind :fight :intent/target eid :intent/to-the-end? true :intent/yield? true})
            ;; hold-intent is nil with a blaze in reach to be fought or nothing to heal:
            ;; seal only with none in reach, never against one at hand
            (when (and (nil? (blaze/reach-blaze world))
                       (pocket/can-seal? world (:pocket/site world)))
              {:intent/kind :seal})
            (blaze/meal world)
            {:intent/kind :pocket-hold :intent/heal-to 20.0})
        ;; out of the pocket at full health and unwatched, the bore goes on from where
        ;; the bot stands (pocket-bore); hurt, burning or watched, the refuge comes first.
        ;; Watched alone counts, at full health too: the rod-7 bots of the 2026-11-04
        ;; races arrived at the seventh rod at 15 to 20 health with blazes in sight and
        ;; the hunt bored out in their sight, was shot, and only then was sent back,
        ;; the pocket-go and the bore cutting each other until the blazes finished it
        (when (or (< (:player/health world 20.0) 20.0)
                  (blaze/refuge-needed? world)
                  (seq (blaze/watchers world)))
          (or (blaze/commit-fight world)
              (blaze/refuge-now world sp)
              (blaze/pocket-go world sp false)
              (blaze/here-intent world)
              ;; no pocket to go to and none to seal where it stands: the cover
              ;; walk out of every watcher's sight, never the hunt's bore in the
              ;; open
              (blaze/hide world)))))))

(defn fortress-exit-hold
  "The world with :pearls/exit-target set to the tunnel-out target while the bot escapes the
   fortress (fortress-tunnel-target: the held one while it stands, else a fresh one), and dropped
   once it is clear. The world as it was when it already holds that target."
  [world]
  (let [t (when (fortress-escaping? world) (fortress-tunnel-target world))]
    (cond (= t (:pearls/exit-target world)) world
          t (assoc world :pearls/exit-target t)
          :else (dissoc world :pearls/exit-target))))

(defn fortress-exit-log
  "The world with one log line when the fortress exit choice is made or changed."
  [world]
  (if (fortress-escaping? world)
    (let [can? (can-tunnel-out? world)
          logged (:pearls/exit-logged world)]
      (if (= logged can?)
        world
        (-> world
            (assoc :pearls/exit-logged can?)
            (game/say (if can?
                        "pearls: leaving fortress via sealed tunnel-out"
                        "pearls: fallback corridor exit (no tunnel possible)")))))
    (dissoc world :pearls/exit-logged)))

(defn room-exit
  "The :tunnel-to along the fortress trail to its newest cell outside every blaze room, or nil:
   while the feet are inside one (room-boxes) and no enderman is tracked within seek-radius. A
   climb-out or dig-out would open straight up into the room."
  [world]
  (let [boxes (pcover/room-boxes world)
        feet (intent/feet world)]
    (when (and (seq boxes)
               (pcover/in-room? boxes feet)
               (empty? (psense/endermen world pparams/seek-radius)))
      (when-let [c (or (->> (pcover/trail-cells world)
                            (remove #(pcover/in-room? boxes %))
                            first)
                       (let [corners (for [[x0 _ z0 x1 _ z1] boxes
                                           cx [(dec x0) (inc x1)]
                                           cz [(dec z0) (inc z1)]]
                                       [cx (nth feet 1) cz])]
                         (first (sort-by #(physics/distance feet %) corners))))]
        {:intent/kind :tunnel-to
         :intent/target c
         :intent/reach 1.5
         :intent/steps fortress/steps-per-tunnel
         :intent/for :pearls}))))

(defn pearls-stage?
  "Is the pearl stage the one in play: the plan's goals are the pearls row alone (a gym row), or the
   chain has reached the blaze rods? The kinds the hunt answers beyond the endermen apply only then:
   answered-kinds asks every row in play and undone, and an overworld bot at an earlier stage must
   still have :defend fight a silverfish."
  [world]
  (boolean (or (= #{:ender-pearls} (:plan/goals world))
               (contains? (:plan/reached world #{}) :blaze-rods))))
