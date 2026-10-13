(ns clojurecraft.memo
  "A pure function's answers remembered by its inputs, where the inputs are a world's parts.

   A world's parts are values and never change: the facts, the chunks, the block overlay. Between
   two ticks they are most often the very same objects, and a pure function asked again about the
   same objects owes the same answer. So the planner's costliest questions (where are the logs
   on record, which ore can be stood beside, is there a route) are asked of their parts and
   remembered by them: a part by identity (one pointer comparison, never a walk of a world-sized
   map), a small argument (a cell, a goal, a set of states) by value. Which is which is data, one
   keyword per argument. The answer is the one the function gives, so a remembered run and a
   recomputed one are the same run: a replay folds to the same world either way.

   The memory is a fixed array of slots indexed by the arguments' hash, shared by every bot in a
   JVM (each bot's parts are its own objects, so their answers sit in different slots); a slot
   holds one call and is replaced by the next call hashed to it. Races between threads replace
   one whole slot with another and never mix them, so the worst a race does is ask again.")

(set! *warn-on-reflection* true)

(defn same?
  "Do a and b stand for the same input, compared as kind says: :identical, the same object;
   :equal, equal values."
  [kind a b]
  (case kind
    :identical (identical? a b)
    :equal (= a b)))

(defn hash-of
  "An argument's hash as kind compares it: its identity for :identical (a world-sized map is
   never walked), its value's hash for :equal."
  ^long [kind a]
  (case kind
    :identical (System/identityHashCode a)
    :equal (hash a)))

(defn by
  "f remembered over its calls: kinds names how each argument is compared (:identical or
   :equal), slots how many calls are held. A call whose arguments are each the same? as a held
   call's returns that call's answer; any other is answered by f and held in its slot."
  [kinds slots f]
  (let [cache (java.util.concurrent.atomic.AtomicReferenceArray. (int slots))
        kinds (vec kinds)]
    (fn [& args]
      (let [args (vec args)
            h (reduce (fn [h i]
                        (unchecked-add (unchecked-multiply 31 h) (hash-of (kinds i) (args i))))
                      17
                      (range (count args)))
            slot (int (mod h slots))
            [held answer :as hit] (.get cache slot)]
        (if (and hit
                 (= (count held) (count args))
                 (every? (fn [i] (same? (kinds i) (held i) (args i))) (range (count args))))
          answer
          (let [answer (apply f args)]
            (.set cache slot [args answer])
            answer))))))
