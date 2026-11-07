(ns clojurecraft.blind
  "What the ground truth knew that the bot did not, at one moment (the observability plan, truth
   always): the model's truth (simrace/truth: its mobs near the player, who targets the player and
   who dealt what, and its own cause for each blow) set beside the bot's moment at the same time
   (senses/moment: the entities it tracked, the threats it ranked, the hits it felt). Pure over
   those two values, so botlogs reads it from the files a sim run keeps and a test from two small
   maps. It lives under bb/ because botlogs, the one reader, runs in Babashka, which does not load
   the bot."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def hit-match-ms 250)                 ; a blow the bot felt within this of the model's blow is the same blow

(defn alive
  "The mobs of truth (its :mobs) that are not dead (no :dead-at: the model keeps a corpse a moment)."
  [truth]
  (remove :dead-at (:mobs truth)))

(defn untracked
  "The live mobs of truth the bot never tracked: none of the moment's entities carries their eid.
   Each as {:eid :kind :d :targets?}, nearest first (truth's order)."
  [truth moment]
  (let [known (set (keys (:entities moment)))]
    (vec (for [m (alive truth)
               :when (not (contains? known (:eid m)))]
           {:eid (:eid m) :kind (:type m) :d (:d m) :targets? (boolean (:targets? m))}))))

(defn unthreatened
  "The live mobs of truth that have the player as their target (:targets?) and that the bot
   tracked but did not rank as a threat: their eid is among the moment's entities and not among
   its :threats. Empty when the moment names no :threats (an older file: nothing to compare)."
  [truth moment]
  (let [known (set (keys (:entities moment)))
        threats (set (:threats moment))]
    (cond (not (contains? moment :threats)) []
          :else
          (vec (for [m (alive truth)
                     :when (and (:targets? m)
                                (contains? known (:eid m))
                                (not (contains? threats (:eid m))))]
                 {:eid (:eid m) :kind (:type m) :d (:d m)})))))

(defn felt
  "The hit the bot felt that is the model's blow hit (:t, ms), or nil: the one of the moment's
   :hits whose :at is nearest hit's time, within hit-match-ms."
  [moment hit]
  (->> (:hits moment)
       (filter #(<= (abs (- (:at % 0) (:t hit 0))) hit-match-ms))
       (sort-by #(abs (- (:at % 0) (:t hit 0))))
       first))

(defn unseen-hits
  "The blows of truth's :hits that an attacker dealt (:by) and the bot felt without knowing who:
   no hit of the moment near the blow's time, or one with no :by-kind (the attacker was not among
   the entities it tracked when the blow came). Each as {:t :cause :by :dealt}, oldest first."
  [truth moment]
  (vec (for [h (:hits truth)
             :when (:by h)
             :let [f (felt moment h)]
             :when (or (nil? f) (nil? (:by-kind f)))]
         (select-keys h [:t :cause :by :dealt]))))

(defn blind
  "What the truth knew that the bot did not: {:untracked [...] :unthreatened [...] :unseen-hits
   [...]} (untracked, unthreatened, unseen-hits), each key only when it holds something; nil when
   the bot missed nothing the truth knew, or when either side is absent. A pure function of the
   two values."
  [truth moment]
  (when (and truth moment (not (:unavailable truth)))
    (let [u (untracked truth moment)
          t (unthreatened truth moment)
          h (unseen-hits truth moment)
          b (cond-> {}
              (seq u) (assoc :untracked u)
              (seq t) (assoc :unthreatened t)
              (seq h) (assoc :unseen-hits h))]
      (when (seq b) b))))

(defn blind-text
  "A blind map (blind) as the lines a case prints, \"blind: ...\" each; an empty string when the
   bot was not blind (nil)."
  [b]
  (str/join "\n"
            (concat (for [m (:untracked b)]
                      (str "blind: never tracked "
                           (some-> (:kind m)
                                   name)
                           "#"
                           (:eid m)
                           " at "
                           (:d m)
                           (when (:targets? m) ", targeting the bot")))
                    (for [m (:unthreatened b)]
                      (str "blind: "
                           (some-> (:kind m)
                                   name)
                           "#"
                           (:eid m)
                           " targets the bot at "
                           (:d m)
                           " and was ranked no threat"))
                    (for [h (:unseen-hits b)]
                      (str "blind: hit at "
                           (:t h)
                           " ("
                           (some-> (:cause h)
                                   name)
                           ", "
                           (:dealt h)
                           ") from #"
                           (:by h)
                           ", an attacker the bot did not see")))))
