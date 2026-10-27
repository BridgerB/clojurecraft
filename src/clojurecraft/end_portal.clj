(ns clojurecraft.end-portal
  "The end portal lit and stepped into (issue #266): the :end-portal goal, done when an end portal
   block is remembered, and :enter-end, done when the dimension is the End.

   The frames are landmarks (memory/landmark-kinds), remembered as their chunk loads; the lit
   portal's blocks are too, so the goal is read from memory like the fortress's bricks. The work
   is a plan of ops read against the world each tick, as the nether portal's cast (build.clj):
   for each remembered frame whose state has no eye, a :use-on with an eye of ender held, from a
   cell a route reaches that is within reach of the frame (build/reachable-stand); then, for the
   row that enters, a wait for the interior to fill once every frame is eyed, and a :stand-in
   over the portal's middle. The first op not satisfied is worked (build/work-op), so a death or
   a reload resumes where the world says. The frames and the portal are floors to a route
   (path/stood-on): the room's frames are walked on, as a player walks them, to reach the far
   side of the ring, and the portal takes the body the moment the feet are in its cell.

   The vanilla room (StrongholdPieces.PortalRoom): the twelve frames float two blocks over the
   floor round a 3x3 pit of lava, reached up a staircase of three stairs blocks with a
   silverfish spawner in the middle of the top step; the way up is a side of the stairs. The
   room is the gym's :portal-room landing (gym/portal-room)."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stronghold :as stronghold]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def portal-radius 64)                ; blocks from the feet a remembered end portal block is the RESULT's :portal

(def frame-states
  "Every state of an end portal frame, with an eye or without."
  (blocks/states-of :end_portal_frame))

(def portal-states "Every state of the end portal block." (blocks/states-of :end_portal))

;; ---------------------------------------------------------------- what is known

(defn frames
  "The end portal frames on record in the bot's dimension that nothing seen since has replaced
   (memory/positions-now)."
  [world]
  (memory/positions-now world frame-states))

(defn portals
  "The end portal blocks on record in the bot's dimension: the lit portal's interior."
  [world]
  (memory/positions-now world portal-states))

(defn nearest-portal
  "The end portal block on record nearest the feet within portal-radius, or nil: the RESULT's
   :portal, which the gym asks the server about."
  [world]
  (memory/nearest-of (physics/centre (intent/feet world)) portal-radius (portals world)))

(defn state-at
  "The state at pos as the world has it now: the loaded block, else the last one remembered."
  [world pos]
  (or (terrain/block-at world pos) (memory/remembered world pos)))

(defn empty-frames
  "The remembered frames whose state has no eye yet, in one fixed order (by position), so the
   first is the same frame tick after tick while it is worked."
  [world]
  (sort (remove #(end/eyed? (state-at world %)) (frames world))))

(defn middle
  "The middle cell of a whole ring of twelve among frames (positions): a cell some frame borders
   (end/ring-offsets) with a frame at every one of the twelve cells round it; nil when no ring is
   whole."
  [frames]
  (let [fs (set frames)]
    (first (for [[x y z] (sort fs)
                 [ox oz] end/ring-offsets
                 :let [mx (- x ox)
                       mz (- z oz)]
                 :when (every? (fn [[rx rz]] (contains? fs [(+ mx rx) y (+ mz rz)]))
                               end/ring-offsets)]
             [mx y mz]))))

(defn eyes-held
  "How many eyes of ender the inventory holds."
  [world]
  (inventory/item-count world (recipe/item-id :ender_eye)))

;; ---------------------------------------------------------------- the ops

(defmethod build/holds? :eyed
  ;; the loaded block at p is an end portal frame holding an eye
  [world [_ p]]
  (boolean (some-> (terrain/block-at world p)
                   end/eyed?)))

(defn eye-op
  "The op that puts an eye into frame: a :use-on of the frame's top with an eye held, from a
   cell the feet can stand in within reach of it that a route reaches (build/reachable-stand: the
   feet when they already reach, else the nearest such cell to the frame, its own top first),
   done when the frame holds an eye; nil when no such cell exists."
  [world frame]
  (when-let [stand (build/reachable-stand world frame frame)]
    {:build/op :use-on
     :build/item :ender_eye
     :build/pos frame
     :build/face :up
     :build/stand stand
     :build/step :eye
     :build/done [:eyed frame]}))

(defn lit-op
  "The op that waits, where the feet are, for the portal to fill the ring's middle m once every
   frame has its eye: the server tells the nine portal blocks a moment after the last eye."
  [world m]
  {:build/op :stand-in
   :build/pos m
   :build/stand (intent/feet world)
   :build/step :lit
   :build/done [:block m :end_portal]})

(defn enter-op
  "The op that steps into the lit portal whose middle is m: the feet brought over it (the portal
   is a floor to the route, path/stood-on, and the physics lets the body down into it), done
   when the dimension is the End."
  [m]
  {:build/op :stand-in
   :build/pos m
   :build/stand (update m 1 inc)
   :build/step :enter
   :build/done [:dimension dimension/end]})

(defn next-op
  "The op to work now toward the lit portal, and with enter? into the End: an eye into the first
   empty frame with a reachable stand (eye-op), else with enter? the wait for the ring's middle
   to fill (lit-op) and the step in (enter-op); nil when there is none left (every frame eyed, or
   the End reached), or a keyword, why none can be worked: :no-stand (no cell reaches any empty
   frame), :no-ring (the frames on record close no ring of twelve)."
  [world enter?]
  (let [empty (empty-frames world)
        m (when (and enter? (empty? empty)) (middle (frames world)))]
    (cond (= dimension/end (dimension/of world)) nil
          (seq empty) (or (some #(eye-op world %) empty) :no-stand)
          (not enter?) nil
          (nil? m) :no-ring
          (not (build/satisfied? world (lit-op world m))) (lit-op world m)
          :else (enter-op m))))

(defn toward-frame
  "The way through the stronghold's walls to an empty frame when none has a reachable stand
   yet: the portal room's way (stronghold/to-room) toward the cell on top of frame, with a
   tunnel reach of 1.5 and climb of 1 so a tunnel started within fortress/reach (6) outside
   the room's wall or roof digs through into the room.
   Never tunnels when already in the portal room: the room is open and has a lava pit under
   the frame."
  [world frame]
  (when-not (plan/done-by world {:goal/done? :in-portal-room?})
    (let [way (stronghold/to-room world (update frame 1 inc))]
      (if (= :tunnel-to (:intent/kind way)) (assoc way :intent/reach 1.5 :intent/climb 1) way))))

;; ---------------------------------------------------------------- the executor

(defmethod intent/run :end-portal
  ;; work the op next-op names through build/work-op (its child a walk to the stand, the use-on,
  ;; the await); done when none is left or when remaining empty frames need a tunnel through a
  ;; wall (toward-frame), failed for next-op's reason otherwise
  [world {:intent/keys [enter?] :as i} event]
  (let [next (next-op world (boolean enter?))]
    (cond (or (nil? next) (and (= :no-stand next) (seq (empty-frames world))))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (keyword? next)
          (-> world
              (assoc :player/controls {})
              (intent/fail next))
          :else (build/work-op world i next event))))

;; ---------------------------------------------------------------- the goals

(defn next-toward
  "The next intent toward the lit portal, and with enter? into the End: the :end-portal intent
   while a remembered frame without an eye has a reachable stand or the End is still to enter;
   when empty frames remain and none has a reachable stand yet (the portal room's wall or roof
   still between the feet and the frames after :portal-room arrived within 6), the way through
   to the nearest empty frame (toward-frame); a wait :no-frames with no frame on record,
   :no-eyes when fewer eyes are held than frames lack them (the eyes stage, issue #264, is the
   one to look at), :clearing when inside the portal room but no frame stand is reachable yet,
   :lighting once every frame is eyed and the portal's blocks are not yet on record; nil when
   nothing is left."
  [world enter?]
  (let [fs (frames world)
        empty (empty-frames world)]
    (cond (empty? fs) {:plan/wait :no-frames}
          (and (seq empty) (< (eyes-held world) (count empty))) {:plan/wait :no-eyes}
          (and (seq empty) (nil? (some #(eye-op world %) empty)))
          (or (toward-frame world (first empty)) {:plan/wait :clearing})
          (or (seq empty) enter?) {:intent/kind :end-portal :intent/enter? enter?}
          (empty? (portals world)) {:plan/wait :lighting}
          :else nil)))

(defmethod plan/interrupts? :end-portal
  [world _ i]
  (and (not= :end-portal (:intent/kind i)) (some? (some #(eye-op world %) (empty-frames world)))))

(defmethod plan/interrupts? :enter-end
  [world _ i]
  (and (not= :end-portal (:intent/kind i)) (some? (some #(eye-op world %) (empty-frames world)))))

(defmethod plan/done-by :portal-lit? [world _] (boolean (seq (portals world))))

(defmethod plan/done-by :in-end? [world _] (= dimension/end (dimension/of world)))

(defmethod plan/next-intent :end-portal [world _ _] (next-toward world false))

(defmethod plan/next-intent :enter-end [world _ _] (next-toward world true))
