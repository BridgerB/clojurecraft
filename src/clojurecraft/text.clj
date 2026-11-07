(ns clojurecraft.text
  "The server's text components as plain values (nbt.clj reads them: a string, or a map with
   string keys \"text\", \"translate\", \"with\", \"extra\", \"color\" ...). Pure: no English
   table is held, so a translate key reads as itself with its arguments' text after it, which is
   what a death message needs: the key says what happened and the arguments say who and by what.
   The builders (death-component) are the model's, writing what the server would send."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def entity-prefix "entity.minecraft.")
(def death-prefix "death.")

(defn parts
  "The elements of x when it is a vector or a list (a component's \"with\" or \"extra\"), else nothing."
  [x]
  (when (sequential? x) x))

(defn plain
  "A component as one string: a string as it is; a number as its text; a vector as its parts
   joined; a map as its \"text\" or, for a \"translate\" key, the key then each of \"with\"'s
   arguments' plain text after a space, then the plain text of \"extra\"; nil, or anything
   else, as the empty string. Total: a malformed component reads as what it holds."
  [c]
  (cond (string? c) c
        (number? c) (str c)
        (vector? c) (str/join (map plain c))
        (map? c)
        (str/join (cons (if-let [k (get c "translate")]
                          (str k (str/join (map #(str " " (plain %)) (parts (get c "with")))))
                          (plain (get c "text")))
                        (map plain (parts (get c "extra")))))
        :else ""))

(defn translate-key
  "The \"translate\" key of component c, or nil for one that has none."
  [c]
  (when (map? c) (let [k (get c "translate")] (when (string? k) k))))

(defn entity-name
  "The entity name a plain argument text names (\"entity.minecraft.blaze\" is \"blaze\"), else
   the text itself."
  [s]
  (cond (and (string? s) (str/starts-with? s entity-prefix)) (subs s (count entity-prefix))
        :else s))

(defn death
  "A death message component as {:death/key the translate key :death/victim the first argument's
   text :death/by the second argument's, an entity named by its short name}, the last two only when
   there are such arguments; nil for a component that is not a death (its key does not begin
   \"death.\")."
  [c]
  (let [k (translate-key c)
        args (mapv plain (when (map? c) (parts (get c "with"))))]
    (when (and k (str/starts-with? k death-prefix))
      (cond-> {:death/key k}
        (seq args) (assoc :death/victim (first args))
        (> (count args) 1) (assoc :death/by (entity-name (second args)))))))

(defn entity-component
  "The translate component that names mob kind (a keyword, :blaze) as the server sends an entity's name."
  [kind]
  {"translate" (str entity-prefix (name kind))})

(def environmental-keys
  "Death message keys of the causes that are not a mob's: the model's cause keyword to the
   translate key the server uses (DamageType's msgId under \"death.attack.\")."
  {:on_fire "death.attack.onFire"
   :in_fire "death.attack.inFire"
   :lava "death.attack.lava"
   :fall "death.attack.fall"
   :starve "death.attack.starve"
   :drown "death.attack.drown"
   :scheduled "death.attack.outOfWorld"
   :unknown "death.attack.generic"})

(defn death-component
  "The component the server sends for a player's death: key (\"death.attack.mob\") with the victim's
   name and, when there is one, the killer's component, as the \"with\" arguments."
  ([key victim] {"translate" key "with" [{"text" victim}]})
  ([key victim killer] {"translate" key "with" [{"text" victim} killer]}))

(defn cause-component
  "The death message component for the player named victim dying of cause as the model keeps it
   (a mob's kind, or one of environmental-keys' causes) by how the blow came (nil, :fireball or
   :arrow, naming the killer under death.attack.fireball or .arrow; :wither, the effect's own
   message, whoever put it on): \"death.attack.mob\" with the victim and the mob's entity name for
   a mob's blow."
  [victim cause how]
  (cond (= :wither how) (death-component "death.attack.wither" victim)
        (contains? environmental-keys cause) (death-component (environmental-keys cause) victim)
        (contains? #{:fireball :arrow} how)
        (death-component (str death-prefix "attack." (name how)) victim (entity-component cause))
        :else (death-component "death.attack.mob" victim (entity-component cause))))
