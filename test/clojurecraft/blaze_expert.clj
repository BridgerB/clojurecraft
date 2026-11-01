(ns clojurecraft.blaze-expert
  "A scripted expert for the stage-17 ceiling (issue #262, LOOP.md section 4a principle 3).

   It plays the same sim the bot plays, through the same intents, and it may read the true sim:
   every blaze's position, health, volley step and the fireballs in flight. It is a yardstick.
   Nothing here is the bot's decision code.

   The plan: wear the race kit, walk into a spawner's wake, dig a 1x2 pocket outside the spawn
   volume and inside the wake, and seal it. A blaze's melee is a touch with a clear line, so a sealed
   wall holds; one that can see hovers and shoots. The sword band (farther than a
   blaze's reach, near enough to hit) is about a block wide, and a cobble seal opens only when
   the dig sends FINISH, settle plus the finish margin, about a second after the decision. So
   the window is opened between volleys when at most two blazes would see, on the nearest
   whose landing is in that band, and the dig is dropped before FINISH when the landing leaves
   the band. The seal goes back up on a hit, on fire, or when a volley starts charging in view
   and nothing stands in the band. With nobody there to strike, it peeks: the window opens, or
   the bot steps into the opening, so one blaze is drawn to it. A volley already past its charge
   is never given the line back. Rods are taken from the window. The run leaves only at seven
   rods and full health. No act succeeds by dying."
  (:require [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.armour :as armour]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.combat :as combat]
            [clojurecraft.dig :as dig]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.drops :as drops]
            [clojurecraft.eat :as eat]
            [clojurecraft.food :as food]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def ^:private rod-goal 7) ; rods in hand that ends a run, alive and full
(def ^:private full-health 20.0) ; a sortie starts, and the run leaves, only at this
(def ^:private stop-health 12.0) ; an open fight is left at this: one contact and one fireball still live
(def ^:private charge-margin 600) ; ms: a charge this close to firing is not given a line
(def ^:private other-margin 1500) ; ms: another blaze's shot this close keeps the window shut
(def ^:private blaze-speed 0.2) ; blocks a tick a blaze closes while it cannot see
(def ^:private band-pad 0.15) ; blocks outside a blaze's reach the sword band starts
(def ^:private sight-range 32.0) ; blocks: blazes farther than this are not sight-tested
(def ^:private wake-min 6.0) ; horizontal blocks from the spawner the approach counts as arrived
(def ^:private rod-reach 3.0) ; blocks: a rod is taken from the window, not chased across the room
(def ^:private near-dy 8) ; blocks of height from the spawner the approach still counts as near
(def ^:private stand-near 8.0) ; blocks, on the plane, a stand must be from the spawner
(def ^:private stand-far 12.0) ; and no farther: close enough to dig a pocket in the wake
(def ^:private stand-below 8) ; levels under the spawner a floor may be
(def ^:private stand-above 4) ; levels over it
(def ^:private stand-attempts 6) ; stands tried before the expert stops alive
(def ^:private searched-span 4) ; ring cells marked after :no-site. Probe 41289491940's six give-ups sat in one cluster; search-steps marked R4's whole ring (41293318253).
(def ^:private stand-searches 6) ; path plans for the nearest stands: a walk is preferred to a tunnel
(def ^:private window-cap 2) ; blazes that may have a line when the window opens

(def ^:private armour-pieces
  "Worn before any fight, in the order they are put on."
  [:iron_helmet :iron_chestplate :iron_leggings :golden_boots])

(def ^:private band-lo
  "Feet-to-feet distance the sword band starts at."
  (+ (:reach (mob/kinds :blaze)) band-pad))

(def ^:private band-hi "Feet-to-feet distance the sword still reaches at." combat/attack-reach)

(def ^:private eat-below
  "Food the expert eats back up to: a full bar, which is also at least the regen line."
  (max food/max-food eat/regen-food))

(def ^:private out-lock "println from the runs shares this." (Object.))

(defn- say
  "Print one line and flush it, under out-lock."
  [& parts]
  (locking out-lock (apply println parts) (flush)))

(defn- cell-centre "The centre of cell [x y z]." [[x y z]] [(+ x 0.5) (+ y 0.5) (+ z 0.5)])

;; ---------------------------------------------------------------- the pure policy

(defn- shot-wait
  "Ms from now until blaze b's volley timer, negative when the timer is already due."
  [b now]
  (when-let [at (:shot-at b)] (- at now)))

(defn- hot?
  "Will blaze b fire as soon as it is given a line: a shot already leaving, or a charge within
   charge-margin (a charge whose timer passed while the line was shut fires on the tick the
   line returns)?"
  [b now]
  (let [step (:shot-step b 0)]
    (or (>= step 2)
        (and (= 1 step) (let [w (shot-wait b now)] (or (nil? w) (<= w charge-margin)))))))

(defn- idle-ok?
  "May the window be opened on blaze b: it has not started a volley, or it is charging with
   more than charge-margin left, so the sword lands before the first fireball."
  [b now]
  (let [step (:shot-step b 0)] (or (zero? step) (and (= 1 step) (not (hot? b now))))))

(defn- seers "The blazes a window opened now would give a line to." [s] (filterv :see? (:blazes s)))

(defn- between-volleys?
  "Is blaze b between volleys? Step 0 has not started a charge. Step 1 is the charge."
  [b]
  (zero? (:shot-step b 0)))

(defn- window-open?
  "May the window be used: one or two blazes would see, and each is between volleys."
  [s]
  (let [ss (seers s)] (and (<= 1 (count ss) window-cap) (every? between-volleys? ss))))

(defn- soon-shot?
  "Is blaze b's next fire within margin ms, or already due?"
  [b now margin]
  (let [w (shot-wait b now)] (or (>= (:shot-step b 0) 2) (and w (<= w margin)))))

(defn- strike-of
  "The nearest blaze in bs, already nearest-first, for which ok? holds."
  [bs ok?]
  (first (filter ok? bs)))

(defn- any-hot? "Is any blaze hot? now." [s now] (boolean (some #(hot? % now) (:blazes s))))

(defn- ticks-left
  "Ticks until the seal block the unseal is opening would break."
  [s]
  (let [spent (if (:unseal-at s) (- (:now s 0) (:unseal-at s)) 0)]
    (max 0.0 (/ (double (- (:break-ms s 0) spent)) physics/tick-ms))))

(defn- closing-speed
  "Blocks a tick blaze dist is closing: the speed measured since the unseal started, or
   blaze-speed before it has started."
  [s dist]
  (if-let [t (:unseal-at s)]
    (let [elapsed (/ (double (- (:now s 0) t)) physics/tick-ms)]
      (if (pos? elapsed) (max 0.0 (/ (- (:unseal-dist s 0) dist) elapsed)) blaze-speed))
    blaze-speed))

(defn- arrival
  "Feet-to-feet distance blaze dist will stand at when the seal breaks, closing at
   closing-speed for the ticks the break still has."
  [s dist]
  (- dist (* (closing-speed s dist) (ticks-left s))))

(defn- in-band? "Does feet-distance d land in the sword band?" [d] (<= band-lo d band-hi))

(defn- lands?
  "Is blaze b in the sword band now, or will its closing land it there when the seal breaks?"
  [s b]
  (or (in-band? (:dist b)) (in-band? (arrival s (:dist b)))))

(defn- sortie-target
  "The blaze a sealed window may be opened on: the nearest of at most two that would see,
   each between volleys, whose landing at the break is inside the sword band."
  [s]
  (when (and (:sealed? s) (window-open? s))
    (strike-of (seers s) #(and (not (:melee? %)) (lands? s %)))))

(defn- fight-target
  "The blaze an open window may be hit: the nearest of at most two that would see, idle,
   standing in the band now, the sword's reach covering its box."
  [s]
  (when (and (not (:sealed? s)) (<= (count (seers s)) window-cap))
    (strike-of (seers s)
               #(and (idle-ok? % (:now s 0))
                     (not (:melee? %))
                     (in-band? (:dist %))
                     (<= (:box % 100.0) band-hi)))))

(defn- others-soon?
  "Is some blaze other than the one being struck already in a volley and about to fire
   within other-margin? A blaze still between volleys is not one: the window is opened
   on up to two of those."
  [s now]
  (let [id (:eid (or (fight-target s) (sortie-target s)))]
    (boolean
     (and id
          (some (fn [b]
                  (and (not= (:eid b) id) (pos? (:shot-step b 0)) (soon-shot? b now other-margin)))
                (:blazes s))))))

(defn- charging-out-of-band?
  "Has a blaze that would see started a volley while none of them stands in the sword band?
   The charge begins the tick the line returns (BlazeAttackGoal's attackStep), and a sword
   that cannot reach it should not wait the charge out."
  [s]
  (and (some #(pos? (:shot-step % 0)) (seers s)) (not-any? #(in-band? (:dist %)) (seers s))))

(defn- live-fight?
  "Is there an open fight worth staying in: a fight-target and health still over stop-health?"
  [s]
  (and (fight-target s) (>= (:health s 0) stop-health)))

(defn- danger?
  "Must the window be shut or the bot stand still: a hit, burning, a fireball, a volley
   charging into a shot, a charge that started with nothing in the band, more blazes than
   the window may show, another blaze about to shoot, health under stop-health, or health
   under full with no fight open?"
  [s]
  (let [now (:now s 0)]
    (or (:on-fire? s)
        (:struck? s)
        (:fireballs? s)
        (any-hot? s now)
        (charging-out-of-band? s)
        (and (not (:sealed? s)) (> (count (seers s)) window-cap))
        (others-soon? s now)
        (< (:health s 0) stop-health)
        (and (not (live-fight? s)) (< (:health s 0) full-health)))))

(defn- hungry?
  "Is the food bar under eat-below, so a meal still raises it?"
  [s]
  (and (number? (:food s)) (< (:food s) eat-below)))

(defn- leave?
  "Seven rods, full health, and nothing in the air or in reach."
  [s]
  (and (>= (:rods s 0) rod-goal)
       (>= (:health s 0) full-health)
       (not (:on-fire? s))
       (not (:fireballs? s))
       (not (:struck? s))
       (not (:melee? s))))

(defn- seal-act "The seal intent, named why." [why] {:intent/kind :seal :expert/why why})

(defn- eat-act
  "The meal, cooked beef, standing still."
  []
  {:intent/kind :eat :intent/item :cooked_beef :expert/why :eat})

(defn- retreat-act
  "Walk to the retreat cell."
  [s]
  {:intent/kind :walk :intent/target (:retreat s) :intent/for :retreat :expert/why :retreat})

(defn- approach-act
  "Walk to the next stand, tunnel when no walk reaches it, or stop alive when every stand
   has been tried."
  [s]
  (cond (>= (:approach s 0) (:approaches s stand-attempts))
        {:expert/stop true :expert/why :no-pocket}
        (:tunnel s) (assoc (:tunnel s) :expert/why :approach)
        (:walk-to s)
        {:intent/kind :walk :intent/target (:walk-to s) :intent/for :fight :expert/why :approach}
        :else {:expert/why :wait}))

(defn- cover
  "What danger does: seal when the pocket is open, eat when sealed and hungry and quiet,
   otherwise wait."
  [s]
  (cond (and (not (:sealed? s)) (:can-seal? s)) (seal-act :seal)
        (and (:sealed? s)
             (< (:health s 0) full-health)
             (not (:on-fire? s))
             (not (:struck? s))
             (not (:fireballs? s))
             (hungry? s))
        (eat-act)
        :else {:expert/why :wait}))

(defn- gear-act
  "The next piece of armour, or the pick then the sword onto the hotbar. Which is in the hand
   does not matter: selecting the sword would otherwise equip the pick again, and the two
   swap forever."
  [s]
  (cond (= :wear (:phase s))
        (when-let [item (first (remove (:wearing s #{}) armour-pieces))]
          {:intent/kind :wear :intent/item item :expert/why :wear})
        (not (:pick-hotbar? s))
        {:intent/kind :equip :intent/item :stone_pickaxe :expert/why :arm-pick}
        (not (:sword-hotbar? s))
        {:intent/kind :equip :intent/item :iron_sword :expert/why :arm-sword}
        :else {:expert/why :wait}))

(defn- before-close
  "While still outside the pocket: step away from fire, and do not close the spawner under
   full health. Nil when the approach may go on."
  [s]
  (cond (or (:on-fire? s) (:fireballs? s)) (if (:retreat s) (retreat-act s) {:expert/why :wait})
        (< (:health s 0) full-health)
        (cond (:struck? s) {:expert/why :wait}
              (hungry? s) (eat-act)
              :else {:expert/why :wait})
        :else nil))

(defn- fight-act
  "Hit the open window's blaze, the sword in hand, holding ground."
  [s b]
  (if (= :iron_sword (:holding s))
    {:intent/kind :fight :intent/target (:eid b) :intent/hold? true :expert/why :fight}
    {:intent/kind :equip :intent/item :iron_sword :expert/why :arm-sword}))

(defn- sortie-act
  "Open the seal on b, the pick already in hand so the dig starts at once."
  [s]
  (if (= :stone_pickaxe (:holding s))
    {:intent/kind :unseal :expert/why :unseal}
    {:intent/kind :equip :intent/item :stone_pickaxe :expert/why :arm-pick}))

(defn- abort?
  "Is a sortie's unseal in progress whose blaze no longer lands in the band? The dig is
   dropped for a seal before FINISH, so the block stays solid. A peek is not a sortie."
  [s]
  (and (:unseal-at s) (:sealed? s) (pos? (:unseal-dist s 0)) (nil? (sortie-target s))))

(defn- peek?
  "Lure one blaze to the window. Nobody is in the band to strike, at most two would see,
   and some blaze is still between volleys. Sealed, the window opens. Open, with nothing
   that would see, the bot steps into the opening. A blaze that sees and then loses the
   line closes for five ticks (BlazeAttackGoal's lastSeen), which is the draw."
  [s]
  (and (= :hold (:phase s))
       (nil? (sortie-target s))
       (nil? (fight-target s))
       (<= (count (seers s)) window-cap)
       (some #(and (between-volleys? %) (not (:melee? %))) (:blazes s))
       (or (and (:sealed? s) (or (seq (seers s)) (:peek-cell s)))
           (and (not (:sealed? s)) (:peek-cell s) (empty? (seers s))))))

(defn- peek-act
  "Open the seal, or step to the opening once it is open. The pick has to be in hand
   for the dig to start."
  [s]
  (cond (:sealed? s)
        (if (= :stone_pickaxe (:holding s))
          {:intent/kind :unseal :expert/why :peek}
          {:intent/kind :equip :intent/item :stone_pickaxe :expert/why :arm-pick})
        (:peek-cell s)
        {:intent/kind :walk :intent/target (:peek-cell s) :intent/for :peek :expert/why :peek}
        :else {:expert/why :wait}))

(defn- rod-act
  "Take a rod from the window when no blaze would see through it."
  [s]
  (when (and (:rod? s) (empty? (seers s)))
    (if (and (:sealed? s) (:can-seal? s))
      {:intent/kind :unseal :expert/why :unseal-rod}
      {:intent/kind :collect-drops
       :intent/item :blaze_rod
       :intent/target (:collect-at s)
       :expert/why :collect})))

(defn choose
  "The next act for situation s, a pure function of that map: an intent plus :expert/why, or
   done, stop, or wait. Nothing it returns is a death."
  [s]
  (or (cond (leave? s) {:expert/done true :expert/why :done}
            (:melee? s)
            (cond (and (:sealed? s) (:back s))
                  {:intent/kind :dig :intent/target (:back s) :expert/why :flee}
                  (:retreat s) (retreat-act s)
                  :else {:expert/why :wait})
            (#{:wear :arm} (:phase s)) (gear-act s)
            (#{:approach :pocket} (:phase s))
            (or (before-close s)
                (if (and (= :pocket (:phase s)) (:site s))
                  {:intent/kind :pocket :intent/site (:site s) :expert/why :pocket}
                  (approach-act s)))
            (danger? s) (cover s)
            (fight-target s) (fight-act s (fight-target s))
            (abort? s) (seal-act :abort)
            (sortie-target s) (sortie-act s)
            (peek? s) (peek-act s)
            (rod-act s) (rod-act s)
            (and (= :hold (:phase s)) (not (:sealed? s)) (:can-seal? s)) (seal-act :close))
      {:expert/why :wait}))

;; ---------------------------------------------------------------- what the sim shows

(defn- holding-name
  "The item name in the hand, or nil."
  [world]
  (recipe/item-name (get-in world [:player/inventory (:player/held-slot world 0) :item])))

(defn- hotbar-has?
  "Is item's id in a hotbar slot? The hand can select it, and a dig must not send it back
   to the main inventory."
  [world item]
  (let [id (recipe/item-id item)]
    (boolean (some (fn [[slot stack]] (and (inventory/hotbar? slot) (= id (:item stack))))
                   (:player/inventory world)))))

(defn- worn-set
  "The armour pieces worn now."
  [world]
  (set (filter #(armour/worn? world %) armour-pieces)))

(defn- struck?
  "Was the bot hit within eat/hit-quiet-ticks?"
  [world now]
  (boolean (when-let [at (:player/struck-at world)]
             (<= (- now at) (* eat/hit-quiet-ticks physics/tick-ms)))))

(defn- rod-count
  "Blaze rods in the inventory."
  [world]
  (inventory/item-count world (recipe/item-id :blaze_rod)))

(defn- missing-armour
  "The first armour piece not worn, or nil."
  [world]
  (first (remove #(armour/worn? world %) armour-pieces)))

(defn- in-expert-pocket?
  "Is the bot standing in the pocket the expert chose?"
  [world]
  (let [want (:pocket/cell (:expert/site world))
        held (:pocket/cell (:pocket/site world))]
    (boolean (and want held (= want held) (pocket/in? world)))))

(defn- near-spawner?
  "Is the bot inside the spawner's wake and outside the spawn box, close in height?"
  [world spawner]
  (let [pos (:player/pos world)]
    (when (and pos spawner)
      (let [h (physics/horizontal-distance pos (cell-centre spawner))
            dy (abs (- (physics/y-of pos) (double (second spawner))))]
        (and (<= wake-min h (:wake mob/spawner)) (<= dy near-dy))))))

(defn- at-stand?
  "Is the bot at the chosen stand: within a walk's goal of it and about its height, or within
   the reach the tunnel to it ends at (fortress/arrived?)?"
  [world]
  (when-let [cell (:expert/stand world)]
    (when-let [pos (:player/pos world)]
      (or (fortress/arrived? world cell (:expert/stand-reach world 0.5))
          (and (<= (physics/horizontal-distance pos (physics/centre cell)) intent/goal-range)
               (<= (abs (- (physics/y-of pos) (double (second cell)))) 1.0))))))

(defn- phase-of
  "What the bot is doing: armour, the hotbar, the walk in, the dig, or the hold."
  [world spawner]
  (cond (missing-armour world) :wear
        (not (:expert/armed? world)) :arm
        (or (in-expert-pocket? world) (:expert/peeking? world)) :hold
        ;; a site is chosen at the stand. The stand is in the ring, or on the spawner when
        ;; the ring had no floor, which is inside wake-min and would otherwise stay :approach.
        (and (:expert/site world) (or (near-spawner? world spawner) (at-stand? world))) :pocket
        :else :approach))

(defn- opening-dir
  "The [dx dz] the pocket's window faces: its one open side, or back toward the stand it was
   dug from."
  [world site]
  (or (first (pocket/openings world (:pocket/cell site)))
      (when-let [stand (:pocket/stand site)]
        (let [[sx _ sz] stand [cx _ cz] (:pocket/cell site)] [(- sx cx) (- sz cz)]))))

(defn- find-site
  "The nearest site the bot digs on the way in: pocket/site-near with blaze/pocket-ok? and no
   line asked (prepare-intent). A clear line to the block over the spawner is not that pocket.
   Probe 41293318253 stood on the same cells as 41289491940 and each was still :no-site once
   the line was required."
  [world spawner]
  (when (:player/pos world)
    (pocket/site-near world (intent/feet world) (blaze/pocket-ok? world spawner false))))

(defn- stand-eye
  "The eye of a player standing in cell."
  [[x y z]]
  (physics/eye [(+ (double x) 0.5) (double y) (+ (double z) 0.5)]))

(defn- sees-spawner?
  "Is the line from a player in cell to the point a block over the spawner clear? The
   spawner's own cell is solid, so the line aims where its blazes appear."
  [world cell spawner]
  (combat/clear? world (stand-eye cell) (update (physics/centre spawner) 1 + 1.0)))

(defn- ring-cells
  "Standable cells in the ring around the spawner, nearest the feet first. Cells already
   tried are left out. With sight?, only a cell whose line to the block over the spawner
   is clear. A walled spawner has a floor in the ring and no such line until the tunnel
   digs one, and the stand is that floor."
  [world spawner sight?]
  (let [[sx sy sz] spawner
        at (path/cells world)
        feet (intent/feet world)
        tried (:expert/tried world #{})]
    (->> (for [y (range (- sy stand-below) (+ sy stand-above 1))
               x (range (- sx 12) (+ sx 13))
               z (range (- sz 12) (+ sz 13))
               :let [c [x y z]
                     d (physics/horizontal-distance (physics/centre c) (physics/centre spawner))]
               :when (and (<= stand-near d stand-far)
                          (not (contains? tried c))
                          (path/standable? at c)
                          (or (not sight?) (sees-spawner? world c spawner)))]
           c)
         (sort-by #(physics/distance (physics/centre feet) (physics/centre %)))
         vec)))

(defn- tunnel-intent
  "The fortress's tunnel to cell, ending within reach of it. The same shape the bot's
   :tunnel-to uses: a few steps, no climb first, a floor across a gap."
  [cell reach]
  {:intent/kind :tunnel-to
   :intent/target cell
   :intent/steps fortress/steps-per-tunnel
   :intent/reach reach
   :intent/climb 0
   :intent/bridge? true})

(defn- pick-stand
  "A stand in the ring: the nearest of the nearest few with a clear line that the path
   planner can reach, else the nearest of those, to be tunnelled to. With no clear line,
   the nearest standable cell in the ring. The spawner itself only when the ring has no
   stand and it has not already failed. No :cell when nothing is left to try."
  [world spawner]
  (let [band (ring-cells world spawner true)
        floor (ring-cells world spawner false)
        tried (:expert/tried world #{})
        walk (some (fn [c] (when (intent/routable? world {:intent/kind :walk :intent/target c}) c))
                   (take stand-searches band))
        picked (cond walk {:cell walk :how :walk :reach 0.5}
                     (first band) {:cell (first band) :how :tunnel :reach 0.5}
                     (first floor) {:cell (first floor) :how :tunnel :reach 0.5}
                     (not (contains? tried spawner)) {:cell spawner :how :tunnel :reach 2.0}
                     :else nil)]
    (assoc (or picked {}) :band (count band) :floor (count floor))))

(defn- back-cell
  "The cell through the pocket's back wall, opposite the window."
  [world]
  (when-let [site (:pocket/site world)]
    (when-let [[dx dz] (opening-dir world site)]
      (pocket/plus (:pocket/cell site) [(- dx) 0 (- dz)]))))

(defn- retreat-cell
  "A cell eight blocks from the nearest mob, away from it, at the feet's height."
  [world sim]
  (when-let [pos (:player/pos world)]
    (let [nearest (first (sort-by #(physics/distance pos (:pos %)) (vals (:sim/mobs sim))))
          [px py pz] pos
          [mx _ mz] (or (:pos nearest) [(+ px 1.0) py pz])
          dx (- px mx)
          dz (- pz mz)
          h (math/hypot dx dz)
          k (if (pos? h) (/ 8.0 h) 8.0)
          y (second (intent/feet world))]
      [(long (math/floor (+ px (* k dx)))) y (long (math/floor (+ pz (* k dz))))])))

(defn- open-ms
  "Ms from the decision to dig cell until the client sends FINISH: the settle, then the
   finish delay of the block's dig time with the stone pick under the penalties in force.
   An unknown cell is cobble, which is what a seal places while the kit holds it."
  [world cell]
  (let [id (or (when cell (terrain/block-at world cell)) (blocks/default-state :cobblestone))
        ms (or (dig/ms id (recipe/item-id :stone_pickaxe) (intent/penalties world)) intent/dig-ms)]
    (+ intent/settle-ms (intent/finish-delay ms (intent/slowdown world)))))

(defn- sight-solid
  "sim/solid-fn, with the seal's placed cells counted as air: the line a blaze would have
   once the window is dug. With nothing placed, the line the sim has now."
  [sim placed]
  (let [solid? (sim/solid-fn sim)
        open (set placed)]
    (fn [c] (if (contains? open c) false (solid? c)))))

(defn- blaze-view
  "What the policy needs of one blaze mob, or nil when it is too far to test. Melee is the
   strike that would land now (boxes touching, the real line clear), not a reach of two
   through the seal. see? is the line with the seal's placed cells counted open."
  [sim solid? player mob]
  (when-let [pos (:pos mob)]
    (let [dist (physics/distance pos player)]
      (when (<= dist sight-range)
        {:eid (:eid mob)
         :pos pos
         :dist dist
         :box (mob/box-distance (physics/eye player) :blaze pos)
         :melee? (mob/can-strike? (sim/solid-fn sim) :blaze pos player)
         :shot-step (:shot-step mob 0)
         :shot-at (:shot-at mob)
         :see? (mob/sight? solid? (mob/head :blaze pos) (physics/eye player))}))))

(defn- blaze-rows
  "Every blaze within sight-range, nearest first."
  [world sim]
  (let [player (:sim/player-pos sim)
        placed (get-in world [:pocket/site :pocket/placed])
        solid? (sight-solid sim placed)]
    (when player
      (->> (:sim/mobs sim)
           (keep (fn [[eid m]]
                   (when (= :blaze (:type m)) (blaze-view sim solid? player (assoc m :eid eid)))))
           (sort-by :dist)
           vec))))

(defn- any-melee?
  "Would any mob's hit land on the sim's player? A flier only when its boxes touch with a
   clear line; anything else within its reach."
  [sim]
  (let [player (:sim/player-pos sim)
        solid? (sim/solid-fn sim)]
    (boolean (and player
                  (some (fn [[_ m]]
                          (if (:flies (mob/kinds (:type m)))
                            (mob/can-strike? solid? (:type m) (:pos m) player)
                            (mob/in-reach? (:type m) (:pos m) player)))
                        (:sim/mobs sim))))))

(defn- rod-drop
  "The blaze-rod drop within rod-reach of the feet that is not over a void, or nil. The
   truth is the sim's item; the bot's entity is what :collect-drops can walk to, and a drop
   the sim has not named yet still may be the rod."
  [world sim]
  (when-let [pos (:player/pos world)]
    (let [id (recipe/item-id :blaze_rod)
          from-sim (keep (fn [[_ {:keys [pos item]}]]
                           (when (= id (:item item))
                             (when (<= (physics/distance pos (:player/pos world)) rod-reach) pos)))
                         (:sim/items sim))
          [_ entity] (drops/nearest world :blaze_rod (physics/centre (intent/feet world)))]
      (or (first from-sim)
          (when (and entity
                     (<= (physics/distance pos (:entity/pos entity)) rod-reach)
                     (not (drops/over-void? world entity)))
            (:entity/pos entity))))))

(defn- situation
  "The map choose reads, from the bot's world and the sim after this tick."
  [world sim]
  (let [now (:time/now world 0)
        spawner (:expert/spawner world)
        idx (:expert/approach world 0)
        blazes (blaze-rows world sim)
        drop (rod-drop world sim)
        site (:pocket/site world)]
    {:rods (rod-count world)
     :health (:player/health world full-health)
     :on-fire? (boolean (:player/on-fire? world))
     :struck? (struck? world now)
     :food (:player/food world)
     :holding (holding-name world)
     :pick-hotbar? (hotbar-has? world :stone_pickaxe)
     :sword-hotbar? (hotbar-has? world :iron_sword)
     :phase (phase-of world spawner)
     :site (:expert/site world)
     :sealed? (boolean (and site (pocket/sealed? world site)))
     :can-seal? (boolean (and site (pocket/can-seal? world site)))
     :wearing (worn-set world)
     :walk-to (when (= :walk (:expert/stand-how world)) (:expert/stand world))
     :tunnel (when (= :tunnel (:expert/stand-how world))
               (tunnel-intent (:expert/stand world) (:expert/stand-reach world 0.5)))
     :retreat (retreat-cell world sim)
     :back (back-cell world)
     :blazes (or blazes [])
     :fireballs? (boolean (seq (:sim/fireballs sim)))
     :melee? (any-melee? sim)
     :break-ms (open-ms world (last (:pocket/placed site)))
     :now now
     :unseal-at (:expert/unseal-at world)
     :unseal-dist (:expert/unseal-dist world)
     :rod? (some? drop)
     :collect-at (when drop (mapv #(long (math/floor %)) drop))
     :approach idx
     :approaches stand-attempts
     :spawner spawner
     :peek-cell (when-let [site (:pocket/site world)]
                  (when-let [[dx dz] (opening-dir world site)]
                    (when (or (not (zero? dx)) (not (zero? dz)))
                      (pocket/plus (:pocket/cell site) [dx 0 dz]))))}))

;; ---------------------------------------------------------------- driving the intents

(defn- drop-intent
  "The world with its running intent remembered and cleared."
  [world]
  (if-let [i (:plan/intent world)]
    (-> world
        (memory/remember-intention i
                                   (cond (intent/done? i) :done
                                         (intent/failed? i) :failed
                                         :else :abandoned))
        (assoc :player/controls {})
        (dissoc :plan/intent))
    world))

(defn- same-act?
  "Is the running intent the act want names: same kind, target, item, site and hold?"
  [i want]
  (and i
       (= (:intent/kind i) (:intent/kind want))
       (= (:intent/target i) (:intent/target want))
       (= (:intent/item i) (:intent/item want))
       (= (:intent/site i) (:intent/site want))
       (= (boolean (:intent/hold? i)) (boolean (:intent/hold? want)))))

(defn- want-keys
  "want without the expert's own marks, which start-intent would copy onto the intent."
  [want]
  (dissoc want :expert/why :expert/done :expert/stop))

(defn- note-armed
  "Both tools are on the hotbar: arming is over, and a later dig selecting the pick does
   not arm again. Waiting until the sword equip is done never happens, because the hand
   holding the sword asks for the pick."
  [world]
  (if (and (not (:expert/armed? world))
           (hotbar-has? world :stone_pickaxe)
           (hotbar-has? world :iron_sword))
    (assoc world :expert/armed? true)
    world))

(defn- mark-searched
  "Ring cells within searched-span of the feet. They are tried, so the next stand is outside
   the cluster find-site just walked. A whole ring marked at once leaves only the spawner."
  [world]
  (let [feet (intent/feet world)
        sp (:expert/spawner world)]
    (if-not (and (:player/pos world) sp)
      world
      (reduce (fn [w [x y z]]
                (if (<=
                     (+ (abs (- x (first feet))) (abs (- y (second feet))) (abs (- z (nth feet 2))))
                     searched-span)
                  (update w :expert/tried (fnil conj #{}) [x y z])
                  w))
              world
              (ring-cells world sp false)))))

(defn- give-up-stand
  "This stand has no pocket, or the way to it failed: remember the cell and try the next.
   reason is the intent's :intent/reason, or :no-site, :pocket, :no-stand. A :no-site also
   marks the ring cells the search already walked."
  [world cell reason]
  (say "EXPERT-GIVEUP"
       (pr-str {:set (:expert/set world)
                :seed (:expert/seed world)
                :cell cell
                :reason reason
                :approach (:expert/approach world 0)
                :pos (:player/pos world)}))
  (cond-> (-> world
              (dissoc :expert/stand :expert/stand-how :expert/stand-try :expert/stand-reach)
              (update :expert/approach (fnil inc 0)))
    cell (update :expert/tried (fnil conj #{}) cell)
    (= reason :no-site) mark-searched))

(defn- note-approach
  "A walk or a tunnel to the stand that failed advances to the next stand. A retreat's
   walk is not one of those."
  [world prev]
  (if (and prev
           (intent/failed? prev)
           (or (= :tunnel-to (:intent/kind prev))
               (and (= :walk (:intent/kind prev)) (= :fight (:intent/for prev)))))
    (give-up-stand world (:intent/target prev) (:intent/reason prev))
    world))

(defn- note-pocket
  "A pocket intent that ended without the bot in it was the wrong cell: try the next stand."
  [world prev]
  (if (and (= :pocket (:intent/kind prev))
           (or (intent/done? prev) (intent/failed? prev))
           (not (in-expert-pocket? world)))
    (give-up-stand (dissoc world :expert/site) (:expert/stand world) :pocket)
    world))

(defn- note-unseal
  "The tick an unseal sortie starts: the clock and the distance the closing is measured from.
   A rod's unseal is not timed."
  [world want sit]
  (if (and (= :unseal (:intent/kind want))
           (= :unseal (:expert/why want))
           (not= :unseal (get-in world [:plan/intent :intent/kind])))
    (assoc world :expert/unseal-at (:now sit) :expert/unseal-dist (:dist (sortie-target sit) 0))
    world))

(defn- seek
  "Look for a pocket once, at the current stand, when the bot has arrived there. No site
   advances to the next stand. A site already chosen is kept."
  [world]
  (let [spawner (:expert/spawner world)
        idx (:expert/approach world 0)]
    (cond (or (nil? (:player/pos world)) (nil? spawner)) world
          (in-expert-pocket? world) (assoc world :expert/approach 0)
          (or (:expert/site world) (>= idx stand-attempts) (= idx (:expert/searched world))) world
          (at-stand? world)
          (if-let [site (find-site world spawner)]
            (assoc world :expert/site site :expert/searched idx)
            (-> world
                (assoc :expert/searched idx)
                (give-up-stand (:expert/stand world) :no-site)))
          :else world)))

(defn- ensure-stand
  "Choose the stand once for this attempt. A routable cell with a clear line is walked;
   anything else in that line, or a standable cell when the line is empty, is tunnelled to.
   The spawner is the last try. Nothing left stops the approach."
  [world]
  (let [idx (:expert/approach world 0)
        sp (:expert/spawner world)]
    (cond (or (not= :approach (phase-of world sp))
              (nil? sp)
              (nil? (:player/pos world))
              (>= idx stand-attempts)
              (= idx (:expert/stand-try world)))
          world
          :else
          (let [{:keys [cell how reach band floor]} (pick-stand world sp)]
            (say "EXPERT-STAND"
                 (pr-str {:set (:expert/set world)
                          :seed (:expert/seed world)
                          :cell cell
                          :how how
                          :reach reach
                          :band band
                          :floor floor
                          :approach idx
                          :pos (:player/pos world)}))
            (if cell
              (assoc world
                     :expert/stand cell
                     :expert/stand-how how
                     :expert/stand-reach reach
                     :expert/stand-try idx)
              (assoc world :expert/approach stand-attempts :expert/stand-try idx))))))

(defn- dead-blazes
  "Eids of blazes whose death has started."
  [sim]
  (into #{}
        (keep (fn [[eid m]] (when (and (= :blaze (:type m)) (:dead-at m)) eid)))
        (:sim/mobs sim)))

(defn- rod-eids
  "Eids of blaze-rod item entities lying in the sim."
  [sim]
  (let [id (recipe/item-id :blaze_rod)]
    (into #{} (keep (fn [[eid {:keys [item]}]] (when (= id (:item item)) eid))) (:sim/items sim))))

(defn- finish-window
  "The open window has closed, or the run has ended with it open. Print EXPERT-SORTIE and add
   the window's hits to the run. Kills are counted as each blaze dies."
  [world sim]
  (if-let [w (:expert/window world)]
    (let [health (:player/health world 0.0)
          hits (+ (:hits w 0.0) (max 0.0 (- (double (:health w 0.0)) (double health))))
          dead (into (:dead-seen w #{}) (dead-blazes sim))
          seen (into (:seen w #{}) (rod-eids sim))
          kills (count (remove (:dead w #{}) dead))
          drops (count (remove (:drops w #{}) seen))
          pickups (max 0 (- (rod-count world) (:rods w 0)))]
      (say "EXPERT-SORTIE"
           (pr-str {:set (:expert/set world)
                    :seed (:expert/seed world)
                    :t (:t w)
                    :ms (- (:time/now world 0) (:t w 0))
                    :why (:why w)
                    :seers (:seers w 0)
                    :hits hits
                    :kills kills
                    :drops drops
                    :pickups pickups}))
      (-> world
          (dissoc :expert/window)
          (update :expert/sorties (fnil inc 0))
          (update :expert/hits (fnil + 0.0) hits)))
    world))

(defn- track-window
  "Remember how many blazes would see through the seal, every rod entity, and each death.
   An open hold is a sortie: the seal is down. It ends when the seal is up, and that prints
   EXPERT-SORTIE."
  [world sim sit]
  (let [n (count (seers sit))
        open? (and (= :hold (:phase sit)) (:site sit) (not (:sealed? sit)))
        dead (dead-blazes sim)
        fresh (count (remove (:expert/dead world #{}) dead))
        world (-> world
                  (assoc :expert/seers n :expert/dead dead)
                  (update :expert/kills (fnil + 0) fresh)
                  (update :expert/rod-seen (fnil into #{}) (rod-eids sim))
                  (cond-> (and (= :hold (:phase sit)) (:sealed? sit))
                          (update :expert/seer-max (fnil max 0) n)))]
    (cond (and open? (nil? (:expert/window world)))
          (assoc world
                 :expert/window
                 {:t (:now sit 0)
                  :why (:expert/why world)
                  :seers n
                  :health (:health sit 0.0)
                  :hits 0.0
                  :rods (:rods sit 0)
                  :dead dead
                  :dead-seen dead
                  :drops (rod-eids sim)
                  :seen (rod-eids sim)})
          (and open? (:expert/window world))
          (let [w (:expert/window world)
                health (:health sit 0.0)]
            (assoc world
                   :expert/window
                   (-> w
                       (assoc :health health)
                       (update :hits + (max 0.0 (- (double (:health w)) (double health))))
                       (update :dead-seen into dead)
                       (update :seen into (rod-eids sim)))))
          (and (not open?) (:expert/window world)) (finish-window world sim)
          :else world)))

(defn- drive
  "One tick of the expert: choose, and run that intent, keeping it while it is the same act."
  [world sim event]
  (let [running (:plan/intent world)
        world (if (and running
                       (intent/failed? running)
                       (or (= :tunnel-to (:intent/kind running))
                           (and (= :walk (:intent/kind running)) (= :fight (:intent/for running)))))
                (note-approach world running)
                world)
        world (if (= :unseal (:intent/kind (:plan/intent world)))
                world
                (dissoc world :expert/unseal-at :expert/unseal-dist))
        world (-> world
                  note-armed
                  ensure-stand
                  seek)
        sit (situation world sim)
        world (track-window world sim sit)
        want (choose sit)
        world (if (= :peek (:expert/why want))
                (assoc world :expert/peeking? true)
                (dissoc world :expert/peeking?))
        i (:plan/intent world)]
    (cond (:expert/done want)
          (-> (drop-intent world)
              (assoc :player/controls {} :expert/done? true :expert/why :done))
          (:expert/stop want)
          (-> (drop-intent world)
              (assoc :player/controls {}
                     :expert/done? true
                     :expert/stop :no-pocket
                     :expert/why :no-pocket))
          (and i (same-act? i want) (not (intent/done? i)) (not (intent/failed? i)))
          (assoc (intent/run world i event) :expert/why (:expert/why want))
          :else
          (let [world (-> (drop-intent world)
                          (assoc :expert/why (:expert/why want))
                          (note-pocket i)
                          (note-unseal want sit))]
            (if (:intent/kind want)
              (let [world (plan/start-intent world (want-keys want))]
                (intent/run world (:plan/intent world) event))
              (assoc world :player/controls {}))))))

(defn- note-progress
  "Rods this tick, the first time they reached rod-goal, and a flag when the count rose."
  [world]
  (let [n (rod-count world)
        prev (:expert/rods world 0)]
    (cond-> (assoc world :expert/rods n)
      (> n prev) (assoc :expert/rod-up? true)
      (and (>= n rod-goal) (nil? (:expert/seven-ms world)))
      (assoc :expert/seven-ms (:time/now world)))))

(defn- minute-line
  "One EXPERT-MIN map."
  [world sim]
  {:set (:expert/set world)
   :file (:expert/file world)
   :seed (:expert/seed world)
   :t (:time/now world 0)
   :rods (:expert/rods world 0)
   :health (:player/health world)
   :food (:player/food world)
   :intent (get-in world [:plan/intent :intent/kind])
   :why (:expert/why world)
   :blazes (count (filter #(= :blaze (:type %)) (vals (:sim/mobs sim))))
   :seers (:expert/seers world 0)
   :seer-max (:expert/seer-max world 0)
   :fireballs (count (:sim/fireballs sim))
   :sealed? (boolean (when-let [site (:pocket/site world)] (pocket/sealed? world site)))
   :phase (phase-of world (:expert/spawner world))
   :holding (holding-name world)
   :armed? (boolean (:expert/armed? world))
   :pos (:player/pos world)})

(defn- log-progress
  "Print EXPERT-ROD when the count rose and EXPERT-MIN each simulated minute. Returns world
   with those marks consumed."
  [world sim]
  (when (:expert/rod-up? world)
    (say "EXPERT-ROD"
         (pr-str {:set (:expert/set world)
                  :file (:expert/file world)
                  :seed (:expert/seed world)
                  :t (:time/now world 0)
                  :rods (:expert/rods world 0)})))
  (let [minute (quot (:time/now world 0) 60000)]
    (when (> minute (:expert/minute world -1)) (say "EXPERT-MIN" (pr-str (minute-line world sim))))
    (cond-> (dissoc world :expert/rod-up?)
      (> minute (:expert/minute world -1)) (assoc :expert/minute minute))))

;; ---------------------------------------------------------------- one run

(defn- one-tick
  "One tick the way sim/tick-from runs it, with the expert choosing after the sim has ticked
   and the bot has taken the tick's packets. Lag is zero, so the choice sees this tick."
  [{:keys [world sim pending t rand]} max-ms]
  (let [sim (reduce (fn [s p] (sim/step s {:sim/kind :packet :sim/packet p})) sim pending)
        sim (sim/step sim {:sim/kind :tick :sim/now t})
        [sim fresh] (sim/drain sim)
        world (reduce (fn [w p] (game/step w {:event/kind :packet :event/packet p})) world fresh)
        [rand' roll] (if rand (sim/tick-rand rand) [nil 0.5])
        event {:event/kind :tick :event/now t :event/rand roll}
        world (game/step world event)
        dead? (or (:player/dead? world) (seq (:sim/death-causes sim)))
        world (if (and (:player/pos world) (not dead?)) (drive world sim event) world)
        world (log-progress (note-progress world) sim)
        out (sim/sends world)
        world (assoc world :bot/effects [])
        ended? (or dead? (:expert/done? world) (> t max-ms))]
    (if ended?
      {:stop {:world world :sim sim}}
      {:next {:world world :sim sim :pending out :t (+ t physics/tick-ms) :rand rand'}})))

(defn- end-of
  "Why the run stopped. A run that is still alive and has not left or given up ran out of time."
  [world sim]
  (cond (or (:player/dead? world) (seq (:sim/death-causes sim))) :dead
        (= :no-pocket (:expert/stop world)) :no-pocket
        (:expert/done? world) :done
        :else :time))

(defn- death-of
  "The class of the first death cause, or nil when the run is alive."
  [sim]
  (when-let [cause (first (:sim/death-causes sim))] (get simrace/death-classes cause cause)))

(defn- result-line
  "The EXPERT map for one run."
  [world sim]
  (let [rods (:expert/rods world 0)
        end (end-of world sim)
        death (death-of sim)]
    {:set (:expert/set world)
     :file (:expert/file world)
     :seed (:expert/seed world)
     :spawner (:expert/spawner world)
     :spawn (:expert/spawn world)
     :rods rods
     :death death
     :ms (:time/now world 0)
     :seven-ms (:expert/seven-ms world)
     :hit-seven? (some? (:expert/seven-ms world))
     :seven? (and (>= rods rod-goal) (nil? death))
     :left? (= :done end)
     :end end
     :health (:player/health world)
     :why (:expert/why world)
     :seers-max (:expert/seer-max world 0)
     :sorties (:expert/sorties world 0)
     :hits (:expert/hits world 0)
     :kills (:expert/kills world 0)
     :drops (count (:expert/rod-seen world))
     :pickups rods}))

(defn- play
  "Run the expert on a fresh handshake until it leaves, dies, or max-ms passes."
  [world0 sim0 max-ms]
  (let [started (game/step world0 {:event/kind :start})]
    (loop [m {:world (assoc started :bot/effects [])
              :sim sim0
              :pending (sim/sends started)
              :t 0
              :rand (:sim/tick-seed sim0)}]
      (let [r (try (one-tick m max-ms)
                   (catch Exception e {:crashed e :world (:world m) :sim (:sim m)}))]
        (cond (:crashed r) r
              (:stop r) (:stop r)
              :else (recur (:next r)))))))

(defn- expert-init
  "The sim a fortress run starts in: the race kit, the Nether, flying blazes, a full food bar."
  [w seed]
  (sim/init {:columns (get-in w [:capture/nether :columns])
             :decoded (get-in w [:capture/nether :decoded])
             :spawn (simrace/spawn-of w)
             :seed seed
             :tick-seed seed
             :difficulty simrace/difficulty
             :day-time (simrace/day-time-of seed)
             :dimension dimension/nether
             :view-distance simrace/nether-view
             :inventory (get-in w [:capture/fortress :inventory])
             :respawn-in dimension/nether}))

(defn- file-name "The last name of path." [path] (.getName ^java.io.File (io/file path)))

(defn- run-one
  "One fortress run. Prints its EXPERT line and returns that map."
  [w seed max-ms]
  (let [fortress (:capture/fortress w)
        tag {:expert/set (:capture/set w)
             :expert/file (file-name (:capture/file w))
             :expert/seed seed
             :expert/spawner (:spawner fortress)
             :expert/spawn (:spawn fortress)}
        world0 (merge (game/init {:host "sim" :port 0 :name "Clj_expert"}) tag)
        played (try (play world0 (expert-init w seed) max-ms)
                    (catch Exception e {:crashed e :world world0 :sim {}}))]
    (if-let [^Exception e (:crashed played)]
      (let [line {:set (:capture/set w)
                  :file (:expert/file tag)
                  :seed seed
                  :spawner (:spawner fortress)
                  :spawn (:spawn fortress)
                  :rods 0
                  :death nil
                  :ms 0
                  :seven-ms nil
                  :hit-seven? false
                  :seven? false
                  :left? false
                  :end :crashed
                  :health nil
                  :why :crashed
                  :error (str (.getName (class e))
                              ": " (.getMessage e)
                              " " (pr-str (mapv str (take 6 (.getStackTrace e)))))}]
        (say "EXPERT" (pr-str line))
        line)
      (let [world (finish-window (:world played) (:sim played))
            line (result-line (merge world tag) (:sim played))]
        (say "EXPERT" (pr-str line))
        line))))

;; ---------------------------------------------------------------- the probe

(defn- directory?
  "Does path name a directory?"
  [path]
  (let [^java.io.File f (io/file path)] (.isDirectory f)))

(defn- worlds-of
  "The first n world files of set-name that have a fortress start, prepared, sharing one
   Nether. A set with no captured Nether or no fortress contributes nothing."
  [set-name n]
  (let [dir (io/file "cache/sim-worlds" set-name)]
    (if-not (directory? dir)
      []
      (let [files (simrace/world-files dir)
            nether-path (when-let [f (first files)] (simrace/nether-file f))
            nether (when (and nether-path
                              (let [^java.io.File nf (io/file nether-path)] (.exists nf)))
                     (simrace/read-nether nether-path))]
        (if-not nether
          []
          (into []
                (comp (map (fn [f]
                             (let [w (assoc (simrace/prepared (capture/read-world (str f)))
                                            :capture/file (str f)
                                            :capture/set set-name)]
                               (simrace/with-fortress (simrace/with-nether w nether nether-path)
                                                      simrace/fortress-kit-default))))
                      (filter :capture/fortress)
                      (take n))
                files))))))

(defn- jobs
  "[[world seed] ...] for sets, n world files each, seeds 1 through n-seeds."
  [sets n-files n-seeds]
  (vec (mapcat (fn [set-name]
                 (mapcat (fn [w] (map (fn [seed] [w seed]) (range 1 (inc n-seeds))))
                  (worlds-of set-name n-files)))
        sets)))

(defn- success?
  "Did the run hold seven rods and never die?"
  [line]
  (boolean (and (:seven? line) (not= :dead (:end line)) (not= :crashed (:end line)))))

(defn- summarize
  "The EXPERT-SUM map: k/n, rods, deaths by class, and the runs that left."
  [lines]
  (let [n (count lines)
        k (count (filter success? lines))]
    {:k k
     :n n
     :rate (if (pos? n) (/ (double k) n) 0.0)
     :rods (mapv :rods lines)
     :deaths (frequencies (keep :death lines))
     :ends (frequencies (map :end lines))
     :seven-ms (mapv :seven-ms lines)
     :hit-seven (count (filter :hit-seven? lines))
     :left (count (filter :left? lines))}))

(defn- processors "Cores the probe may use." [] (.availableProcessors (Runtime/getRuntime)))

(defn -main
  "Probe the ceiling. Args are sets (R1,R2,R3,R4), how many fortress world files of each,
   how many seeds from 1, and simulated minutes a run may take. Prints EXPERT-WORLD as the
   worlds are read, EXPERT-START, one EXPERT line per run, EXPERT-MIN each simulated minute,
   EXPERT-ROD when a rod is picked up, and EXPERT-SUM."
  [& [sets n-files n-seeds minutes]]
  (let [set-names (str/split (or sets "R1,R2,R3,R4") #",")
        files-n (parse-long (or n-files "1"))
        seeds-n (parse-long (or n-seeds "2"))
        mins (parse-long (or minutes "15"))
        max-ms (* 60000 mins)
        todo (jobs set-names files-n seeds-n)
        n (count todo)
        threads (max 1 (min 4 n (processors)))]
    (doseq [[w seed] todo]
      (say "EXPERT-WORLD"
           (pr-str {:set (:capture/set w)
                    :file (file-name (:capture/file w))
                    :seed seed
                    :spawner (get-in w [:capture/fortress :spawner])
                    :spawn (get-in w [:capture/fortress :spawn])})))
    (say "EXPERT-START"
         (pr-str {:runs n
                  :sets set-names
                  :files files-n
                  :seeds seeds-n
                  :minutes mins
                  :threads threads
                  :why (when (zero? n) :no-fortress-worlds)}))
    (let [runs (if (zero? n)
                 []
                 (let [work (atom todo)
                       take! (fn [] (let [[old _] (swap-vals! work rest)] (first old)))
                       worker (fn []
                                (loop [done []]
                                  (if-let [[w seed] (take!)]
                                    (recur (conj done (run-one w seed max-ms)))
                                    done)))
                       fs (mapv (fn [_] (future (worker))) (range threads))]
                   (vec (mapcat deref fs))))]
      (say "EXPERT-SUM" (pr-str (summarize runs)))
      (shutdown-agents))))
