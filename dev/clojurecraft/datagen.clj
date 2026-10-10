(ns clojurecraft.datagen
  "Turn the vanilla server's --reports output, and the data files inside the server jar, into
   the small EDN tables the bot ships with. Run via scripts/datagen.sh (or
   `clojure -M:datagen <reports-dir> <resources-dir> [server-jar]`).

   Recipes and item tags are not in the --reports output; they live in the inner jar
   META-INF/versions/<v>/server-<v>.jar under data/minecraft/{recipe,tags/item}/."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.zip ZipInputStream]))

(defn- strip-ns [s] (str/replace s #"^minecraft:" ""))
(defn- kebab-kw [s] (keyword (str/replace (strip-ns s) "_" "-")))
(defn- name-kw [s] (keyword (strip-ns s)))

(defn packets
  "{state {:s2c {name id} :c2s {name id}}} from reports/packets.json."
  [reports]
  (let [j (json/read-str (slurp (io/file reports "packets.json")))
        dir {"clientbound" :s2c "serverbound" :c2s}]
    (into (sorted-map)
          (for [[state dirs] j]
            [(keyword state)
             (into (sorted-map)
                   (for [[d ps] dirs]
                     [(dir d) (into (sorted-map)
                                    (for [[n m] ps] [(kebab-kw n) (get m "protocol_id")]))]))]))))

(defn blocks
  "[[name type min-state max-state] ...] sorted by state id, from reports/blocks.json."
  [reports]
  (let [j (json/read-str (slurp (io/file reports "blocks.json")))]
    (->> j
         (map (fn [[n m]]
                (let [ids (map #(get % "id") (get m "states"))]
                  [(name-kw n) (name-kw (get-in m ["definition" "type"])) (apply min ids) (apply max ids)])))
         (sort-by #(nth % 2))
         vec)))

(defn registry [reports reg]
  (let [j (json/read-str (slurp (io/file reports "registries.json")))]
    (into (sorted-map)
          (for [[n m] (get-in j [reg "entries"])] [(name-kw n) (get m "protocol_id")]))))

;; ---------------------------------------------------------------- jar data

(defn- inner-jar-entries
  "{path json-string} for every data/minecraft/{recipe,tags/item}/*.json in the inner jar."
  [server-jar]
  (with-open [outer (ZipInputStream. (io/input-stream server-jar))]
    (let [inner (loop []
                  (let [e (.getNextEntry outer)]
                    (cond (nil? e) (throw (ex-info "no inner server jar" {:jar server-jar}))
                          (re-matches #"META-INF/versions/.*/server-.*\.jar" (.getName e)) (.readAllBytes outer)
                          :else (recur))))]
      (with-open [z (ZipInputStream. (java.io.ByteArrayInputStream. inner))]
        (loop [acc {}]
          (if-let [e (.getNextEntry z)]
            (let [n (.getName e)]
              (recur (if (or (= n "version.json") (re-matches #"data/minecraft/(recipe/[^/]+|tags/item/.+)\.json" n))
                       (assoc acc n (String. (.readAllBytes z) "UTF-8"))
                       acc)))
            acc))))))

(defn version
  "The server's own version facts from the inner jar's version.json: the protocol number is
   data that changes with the server, not a constant in the code."
  [entries]
  (let [v (json/read-str (get entries "version.json"))]
    (sorted-map :version/id (get v "id")
                :version/protocol (get v "protocol_version")
                :version/world (get v "world_version")
                :version/java (get v "java_version"))))

(defn- file-stem [path] (second (re-find #"/([^/]+)\.json$" path)))
(defn- tag-name [path] (second (re-find #"^data/minecraft/tags/item/(.+)\.json$" path)))

(defn item-tags
  "{tag-kw #{item-kw}} with nested tags resolved."
  [entries]
  (let [raw (into {} (for [[p j] entries :when (str/starts-with? p "data/minecraft/tags/item/")]
                       [(keyword (tag-name p)) (get (json/read-str j) "values")]))
        resolve (fn resolve [tag seen]
                  (into (sorted-set)
                        (mapcat (fn [v]
                                  (let [v (if (map? v) (get v "id") v)]
                                    (if (str/starts-with? v "#")
                                      (let [t (keyword (strip-ns (subs v 1)))]
                                        (when-not (seen t) (resolve t (conj seen t))))
                                      [(name-kw v)])))
                                (get raw tag))))]
    (into (sorted-map) (for [t (keys raw)] [t (resolve t #{t})]))))

(defn- ingredient [tags v]
  (cond
    (sequential? v) (into (sorted-set) (mapcat #(ingredient tags %) v))
    (str/starts-with? v "#") (get tags (keyword (strip-ns (subs v 1))) (sorted-set))
    :else (sorted-set (name-kw v))))

(defn shrink
  "A shaped pattern with blank rows and columns trimmed, as vanilla does when it loads the
   recipe: [\" # \" \" X \"] is a 1-wide recipe that fits anywhere a 1-wide recipe fits."
  [pattern]
  (let [width (apply max (map count pattern))
        rows (mapv #(apply str (take width (concat % (repeat \space)))) pattern)
        blank-row? (fn [r] (every? #{\space} r))
        blank-col? (fn [c] (every? #(= \space (nth % c)) rows))
        rows (->> rows (drop-while blank-row?) reverse (drop-while blank-row?) reverse vec)
        cols (remove blank-col? (range width))
        c0 (first cols) c1 (inc (last cols))]
    (mapv #(subs % c0 c1) rows)))

(defn recipes
  "[{:recipe/id ...}] for every crafting_shaped / crafting_shapeless recipe, tags resolved,
   shaped patterns shrunk like vanilla."
  [entries tags]
  (->> (for [[p j] entries
             :when (str/starts-with? p "data/minecraft/recipe/")
             :let [r (json/read-str j)
                   t (get r "type")]
             :when (#{"minecraft:crafting_shaped" "minecraft:crafting_shapeless"} t)
             :let [base {:recipe/id (keyword (file-stem p))
                         :recipe/result (name-kw (get-in r ["result" "id"]))
                         :recipe/count (get-in r ["result" "count"] 1)}]]
         (if (= t "minecraft:crafting_shaped")
           (let [pattern (shrink (get r "pattern"))]
             (assoc base :recipe/kind :shaped
                    :recipe/pattern pattern
                    :recipe/key (into (sorted-map) (for [[k v] (get r "key")] [k (ingredient tags v)]))
                    :recipe/width (apply max (map count pattern))
                    :recipe/height (count pattern)))
           (assoc base :recipe/kind :shapeless
                  :recipe/ingredients (mapv #(ingredient tags %) (get r "ingredients")))))
       (sort-by :recipe/id)
       vec))

(defn- write-rows [f header rows]
  (with-open [w (io/writer f)]
    (.write w header)
    (.write w "[\n")
    (doseq [r rows] (.write w (str " " (pr-str r) "\n")))
    (.write w "]\n")))

(defn- write-map [f header m]
  (with-open [w (io/writer f)]
    (.write w header)
    (.write w "{\n")
    (doseq [[k v] m] (.write w (str " " (pr-str k) " " (pr-str v) "\n")))
    (.write w "}\n")))

(defn -main [reports out & [server-jar]]
  (let [hdr ";; generated by scripts/datagen.sh from vanilla 26.1.2 --reports; do not edit\n"
        jar-hdr ";; generated by scripts/datagen.sh from the vanilla 26.1.2 server jar data; do not edit\n"]
    (when server-jar
      (let [entries (inner-jar-entries server-jar)
            tags (item-tags entries)]
        (write-map (io/file out "version.edn") jar-hdr (version entries))
        (write-map (io/file out "item-tags.edn") jar-hdr tags)
        (write-rows (io/file out "recipes.edn") jar-hdr (recipes entries tags))
        (println "wrote" (str out "/{version,item-tags,recipes}.edn"))))
    (write-rows (io/file out "blocks.edn") hdr (blocks reports))
    (write-map (io/file out "packets.edn") hdr (packets reports))
    (write-map (io/file out "items.edn") hdr (registry reports "minecraft:item"))
    (write-map (io/file out "entity-types.edn") hdr (registry reports "minecraft:entity_type"))
    (println "wrote" (str out "/{blocks,packets,items,entity-types}.edn"))))
