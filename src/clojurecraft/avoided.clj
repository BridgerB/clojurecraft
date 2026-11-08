(ns clojurecraft.avoided
  "What the bot has learned not to try again, behind one interface. A failure is noted under a kind
   (`kinds`): a set of subjects (a target, a cell, an entity id) or a map from a subject to a
   value (a count, a time). Only this namespace knows where the world keeps them, so a row asks
   `noted?` or `mark-of` and never reads a world attribute.")

(set! *warn-on-reflection* true)

(def kinds
  "{kind shape}: :set kinds hold subjects, :map kinds hold subject -> value. Each is kept under
   the world attribute :plan/<kind>.
   :blacklist targets a failed intent aimed at. :boxed-at cells a stair or tunnel died :boxed
   standing in. :boxed-route cells such a search visited. :unreached log targets no route found.
   :retreat-unreachable cells a retreat walk found no route to. :cornered entities a back-off
   could not get away from. :trunk-failures [x z] column -> failures counted against a trunk.
   :pocket-regions [rx rz] 16x16 region -> the set of headings tried from it: a pocket the bot sat in
   for five minutes without netting ten blocks (pearls.move/watch-stuck).
   :given-up entity -> ms a fight gave it up at. :backed-off entity -> {:pos :times} of back-offs."
  {:blacklist :set
   :boxed-at :set
   :boxed-route :set
   :unreached :set
   :retreat-unreachable :set
   :cornered :set
   :pocket-regions :map
   :trunk-failures :map
   :given-up :map
   :backed-off :map})

(defn attribute
  "The world attribute that holds failures of kind: :plan/<kind>."
  [kind]
  (keyword "plan" (name kind)))

(defn empty-of
  "The empty value of kind: #{} for a :set kind, {} for a :map kind."
  [kind]
  (if (= :map (kinds kind)) {} #{}))

(defn all-of
  "Everything noted under kind: a set of subjects, or a map of subject -> value. Empty, never nil."
  [world kind]
  (get world (attribute kind) (empty-of kind)))

(defn noted?
  "Is subject noted under the :set kind?"
  [world kind subject]
  (contains? (all-of world kind) subject))

(defn note
  "The world with subject added to the :set kind."
  [world kind subject]
  (update world (attribute kind) (fnil conj #{}) subject))

(defn note-all
  "The world with every subject of subjects added to the :set kind."
  [world kind subjects]
  (update world (attribute kind) (fnil into #{}) subjects))

(defn mark
  "The world with value stored for subject under the :map kind."
  [world kind subject value]
  (assoc-in world [(attribute kind) subject] value))

(defn mark-of
  "The value stored for subject under the :map kind, or default (nil) when none."
  ([world kind subject] (mark-of world kind subject nil))
  ([world kind subject default] (get (all-of world kind) subject default)))

(defn bump
  "The world with the count under the :map kind for subject one higher."
  [world kind subject]
  (update-in world [(attribute kind) subject] (fnil inc 0)))

(defn forget
  "The world without the attribute of kind: nothing noted, and no key (all-of still answers empty)."
  [world kind]
  (dissoc world (attribute kind)))

(defn clear
  "The world with kind present and empty: #{} for a :set kind, {} for a :map kind."
  [world kind]
  (assoc world (attribute kind) (empty-of kind)))
