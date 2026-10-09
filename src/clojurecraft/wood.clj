(ns clojurecraft.wood
  "The goal: hold one log. A policy over the game state with the reducer signature
   (step state event) → {:state :effects}. It owns :task and :controls and reads everything
   else. Phases are data:

     nil → [:go] → :find → :walk → :settle → :dig → :collect → :done | :failed"
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.physics :as physics]))

(def reach 4.0)             ; eye → block centre, server allows ~4.5
(def search-radius 48)
(def dig-ms 3000)           ; log by hand: hardness 2 → 60 ticks
(def finish-after (+ (* dig-ms 1.35) 200)) ; an early FINISH aborts the break; a late one is accepted
(def swing-every 350)
(def find-timeout 20000)
(def walk-timeout-ticks 1200)
(def collect-timeout 10000)
(def max-attempts 3)

(defn- centre [[x y z]] [(+ x 0.5) (+ y 0.5) (+ z 0.5)])

(defn trunk-bottom? [state [x y z]]
  (let [below (game/block-at state [x (dec y) z])]
    (and below (not (blocks/log? below)))))

(defn find-log
  "Nearest trunk-bottom log within search-radius of the eye, skipping blacklisted positions."
  [state blacklist]
  (let [eye (game/eye state)
        [ex ey ez] eye
        cx (bit-shift-right (long (Math/floor ex)) 4)
        cz (bit-shift-right (long (Math/floor ez)) 4)
        r (inc (quot search-radius 16))
        near (filter (fn [[[x z] _]] (and (<= (abs (- x cx)) r) (<= (abs (- z cz)) r))) (:chunks state))]
    (->> (chunk/find-blocks near blocks/log?)
         (map (fn [[x y z _]] [x y z]))
         (remove blacklist)
         (filter #(blocks/log? (game/block-at state %)))
         (filter #(trunk-bottom? state %))
         (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
         (map (fn [p] [(physics/distance eye (centre p)) p]))
         (filter (fn [[d _]] (<= d search-radius)))
         (sort-by first)
         first
         second)))

(defn face-toward
  "Block face nearest the eye: 0 down 1 up 2 north 3 south 4 west 5 east."
  [eye target]
  (let [[dx dy dz] (map - eye (centre target))
        ax (abs dx) ay (abs dy) az (abs dz)]
    (cond (and (>= ay ax) (>= ay az)) (if (pos? dy) 1 0)
          (>= ax az) (if (pos? dx) 5 4)
          :else (if (pos? dz) 3 2))))

(defn- task [state m] (assoc state :task m))
(defn- phase [state] (get-in state [:task :phase]))

(defn- nearest-item [state near-pos]
  (->> (:entities state)
       (map (fn [[id e]] [(physics/distance near-pos (:pos e)) e]))
       (filter (fn [[d _]] (<= d 6.0)))
       (sort-by first)
       first
       second))

(defn- walk-controls
  "Controls that walk toward target; jump when blocked."
  [state target yaw-offset]
  (let [eye (game/eye state)
        [yaw pitch] (physics/look-at eye (centre target))
        yaw (+ yaw yaw-offset)]
    {:forward? true
     :jump? (boolean (get-in state [:player :horizontal-collision?]))
     :yaw yaw
     :look [yaw pitch]}))

;; ---------------------------------------------------------------- phases

(defn- begin-find [state]
  (task state {:phase :find :since (:now state) :blacklist (get-in state [:task :blacklist] #{})
               :attempts (get-in state [:task :attempts] 0)}))

(defn- fail [state reason]
  (-> state (assoc :controls {}) (task (assoc (:task state) :phase :failed :reason reason))))

(defn- retry-or-fail [state reason]
  (let [t (:task state)
        attempts (inc (:attempts t 0))]
    (if (< attempts max-attempts)
      (-> state
          (assoc :controls {})
          (task (assoc t :attempts attempts :blacklist (conj (:blacklist t #{}) (:target t))))
          begin-find
          (game/log (str "retry after " reason " (attempt " attempts ")")))
      (fail state reason))))

(defn- on-find [state]
  (let [t (:task state)]
    (if-let [target (and (zero? (mod (:tick state) 20)) (find-log state (:blacklist t)))]
      (-> state
          (task (assoc t :phase :walk :target target :best-dist Double/MAX_VALUE
                         :best-tick (:tick state) :started (:tick state) :detours 0))
          (game/log (str "log at " target)))
      (if (> (- (:now state) (:since t)) find-timeout)
        (fail state :no-log)
        state))))

(defn- on-walk [state]
  (let [t (:task state)
        target (:target t)
        tick (:tick state)
        d (physics/distance (game/eye state) (centre target))
        in-reach? (<= d reach)
        progressed? (< d (- (:best-dist t) 0.25))
        t (if progressed? (assoc t :best-dist d :best-tick tick) t)
        stuck? (> (- tick (:best-tick t)) 40)
        detouring? (and (:detour-until t) (< tick (:detour-until t)))]
    (cond
      in-reach?
      (-> state (assoc :controls {:look (physics/look-at (game/eye state) (centre target))})
          (task (assoc t :phase :settle :since (:now state) :still 0)))

      (or (> (- tick (:started t)) walk-timeout-ticks) (>= (:detours t) 4))
      (retry-or-fail state :stuck)

      (and stuck? (not detouring?))
      (let [t (assoc t :detour-until (+ tick 20) :detour-yaw (rand-nth [-70.0 70.0])
                       :detours (inc (:detours t)) :best-tick tick)]
        (-> state (task t)
            (assoc :controls (assoc (walk-controls state target (:detour-yaw t)) :jump? true))))

      :else
      (-> state (task t)
          (assoc :controls (walk-controls state target (if detouring? (:detour-yaw t) 0.0)))))))

(defn- on-settle [state]
  (let [t (:task state)
        {:keys [on-ground? vel]} (:player state)
        still? (and on-ground? (every? #(< (abs %) 0.05) [(first vel) (nth vel 2)]))
        t (assoc t :still (if still? (inc (:still t)) 0))
        eye (game/eye state)
        target (:target t)]
    (cond
      (not (blocks/log? (game/block-at state target)))
      (retry-or-fail state :target-gone)

      (> (physics/distance eye (centre target)) (+ reach 0.5))
      (task state (assoc t :phase :walk :best-dist Double/MAX_VALUE :best-tick (:tick state)))

      (and (>= (:still t) 3) (> (- (:now state) (:since t)) 500))
      (let [seq (inc (:sequence state))
            face (face-toward eye target)
            now (:now state)]
        (-> state
            (assoc :sequence seq)
            (game/send {:name :set-carried-item :slot 0})
            (game/send {:name :player-action :status 0 :pos target :face face :sequence seq})
            (game/send {:name :swing :hand 0})
            (task (assoc t :phase :dig :face face :started now :next-swing (+ now swing-every)
                           :finish-at (+ now finish-after)))
            (game/log (str "digging " target " face " face))))

      :else
      (task state t))))

(defn- on-dig [state]
  (let [t (:task state)
        now (:now state)
        target (:target t)]
    (cond
      (>= now (:finish-at t))
      (let [seq (inc (:sequence state))]
        (-> state
            (assoc :sequence seq)
            (game/send {:name :player-action :status 2 :pos target :face (:face t) :sequence seq})
            (assoc-in [:blocks target] blocks/air)
            (task (assoc t :phase :collect :since now))
            (game/log "finished digging, collecting")))

      (>= now (:next-swing t))
      (-> state (game/send {:name :swing :hand 0}) (task (assoc t :next-swing (+ now swing-every))))

      :else state)))

(defn- on-collect [state]
  (let [t (:task state)
        logs (game/logs-held state)
        log-centre (centre (:target t))
        item (nearest-item state log-centre)
        goal (if item (:pos item) [(first log-centre) (second log-centre) (nth log-centre 2)])
        pos (get-in state [:player :pos])
        hd (physics/horizontal-distance pos goal)]
    (cond
      (pos? logs)
      (-> state (assoc :controls {}) (task (assoc t :phase :done :logs logs)) (game/log (str "holding " logs " log(s)")))

      (> (- (:now state) (:since t)) collect-timeout)
      (retry-or-fail state :not-picked-up)

      (> hd 0.4)
      (let [[yaw pitch] (physics/look-at (game/eye state) goal)]
        (assoc state :controls {:forward? true :yaw yaw :look [yaw pitch]
                                :jump? (boolean (get-in state [:player :horizontal-collision?]))}))

      :else (assoc state :controls {}))))

(defn- on-tick [state]
  (case (phase state)
    :find (on-find state)
    :walk (on-walk state)
    :settle (on-settle state)
    :dig (on-dig state)
    :collect (on-collect state)
    state))

(defn plan [state [kind]]
  (case kind
    :go (-> state begin-find (game/log "go: looking for a log"))
    :tick (if (get-in state [:player :loaded?]) (on-tick state) state)
    state))

(defn step [state event]
  (let [s (plan state event)]
    {:state (dissoc s ::game/out) :effects (or (::game/out s) [])}))

(defn done? [state] (= :done (phase state)))
(defn failed? [state] (= :failed (phase state)))

(defn summary [state]
  (select-keys (:task state) [:phase :target :reason :attempts :logs :detours]))
