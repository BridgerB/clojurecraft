(ns clojurecraft.datagen
  "Turn the vanilla server's --reports output, and the data files inside the server jar, into
   the small EDN tables the bot ships with. Run via scripts/datagen.sh (or
   `clojure -M:datagen <reports-dir> <resources-dir> [server-jar]`).

   Recipes, item tags and block tags are not in the --reports output; they live in the inner
   jar META-INF/versions/<v>/server-<v>.jar under data/minecraft/{recipe,tags/item,tags/block}/.
   Block hardness and tool materials are not data files at all: clojurecraft.jar-probe reads
   them from the game's own classes (scripts/datagen.sh runs it) and hands them here as JSON."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.zip ZipInputStream]))

(defn strip-ns
  "s without a leading \"minecraft:\"."
  [s]
  (str/replace s #"^minecraft:" ""))

(defn kebab-kw
  "A vanilla id as a kebab-case keyword: \"minecraft:keep_alive\" → :keep-alive."
  [s]
  (keyword (str/replace (strip-ns s) "_" "-")))

(defn name-kw
  "A vanilla id as a keyword that keeps its underscores: \"minecraft:oak_log\" → :oak_log."
  [s]
  (keyword (strip-ns s)))

(defn version
  "The server's own version facts from the inner jar's version.json: the protocol number is
   data that changes with the server, not a constant in the code."
  [entries]
  (let [v (json/read-str (get entries "version.json"))]
    (sorted-map :version/id (get v "id")
                :version/protocol (get v "protocol_version")
                :version/world (get v "world_version")
                :version/java (get v "java_version"))))

(defn file-stem
  "The file name of a .json path without directory or extension, or nil."
  [path]
  (second (re-find #"/([^/]+)\.json$" path)))

(defn tag-name
  "The tag a data/minecraft/tags/<kind>/*.json path defines (subfolders kept), or nil."
  [kind path]
  (second (re-find (re-pattern (str "^data/minecraft/tags/" kind "/(.+)\\.json$")) path)))

(defn tags-of
  "{tag-kw #{name-kw}} for every tag of one kind (\"item\" or \"block\"), nested tags resolved,
   cycles cut."
  [entries kind]
  (let [prefix (str "data/minecraft/tags/" kind "/")
        raw (into {} (for [[p j] entries :when (str/starts-with? p prefix)]
                       [(keyword (tag-name kind p)) (get (json/read-str j) "values")]))
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

(defn item-tags
  "{tag-kw #{item-kw}} with nested tags resolved."
  [entries]
  (tags-of entries "item"))

(def harvest-tiers
  "The needs_*_tool tags, lowest tier first: a block in a later one needs at least that tier."
  [[:stone :needs_stone_tool] [:iron :needs_iron_tool] [:diamond :needs_diamond_tool]])

(def mineable-kinds
  "The mineable/* tags and the tool kind each names."
  [[:pickaxe (keyword "mineable/pickaxe")] [:axe (keyword "mineable/axe")]
   [:shovel (keyword "mineable/shovel")] [:hoe (keyword "mineable/hoe")]])

(defn harvest
  "{block-kw {:tool kind :needs tier}} from the block tags: which tool kind mines a block
   faster and drops it, and the lowest tier that drops it when one is required. A block in
   no mineable tag is absent (any tool, by hand)."
  [block-tags]
  (let [tool-of (into {} (for [[kind tag] mineable-kinds b (get block-tags tag)] [b kind]))
        needs (into {} (for [[tier tag] harvest-tiers b (get block-tags tag)] [b tier]))]
    (into (sorted-map)
          (for [b (sort (set (concat (keys tool-of) (keys needs))))]
            [b (cond-> {}
                 (tool-of b) (assoc :tool (tool-of b))
                 (needs b) (assoc :needs (needs b)))]))))

(defn hardness
  "{block-kw hardness} from the probe's JSON, keyed like blocks.edn; throws when a block of the
   table has no hardness, so a version bump can never default one silently (ruststeve's
   placeholder-hardness bug)."
  [probe block-rows]
  (let [h (into (sorted-map) (for [[n v] (get probe "hardness")] [(name-kw n) (double v)]))
        missing (remove #(contains? h %) (map first block-rows))]
    (when (seq missing)
      (throw (ex-info "blocks with no hardness from the game" {:blocks (vec missing)})))
    h))

(defn materials
  "{tier-kw {:speed s :durability d :incorrect tag-kw}} from the probe's JSON: the game's own
   ToolMaterial constants. Tiers are named as the item names spell them (:wooden, not WOOD)."
  [probe]
  (let [tier {"WOOD" :wooden "STONE" :stone "COPPER" :copper "IRON" :iron "GOLD" :golden
              "DIAMOND" :diamond "NETHERITE" :netherite}]
    (into (sorted-map)
          (for [[n m] (get probe "materials")]
            [(get tier n (keyword (str/lower-case n)))
             {:speed (double (get m "speed")) :durability (long (get m "durability"))
              :incorrect (name-kw (get m "incorrect"))}]))))

(defn ingredient
  "The sorted set of item keywords a recipe ingredient accepts: an id, a #tag looked up in tags
   (empty when unknown), or a vector of either, unioned."
  [tags v]
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

;;;; I/O: reports, the server jar, the EDN files ;;;;

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

(defn registry
  "{name-kw protocol-id} for the entries of registry reg (e.g. \"minecraft:item\") in
   reports/registries.json, sorted by name."
  [reports reg]
  (let [j (json/read-str (slurp (io/file reports "registries.json")))]
    (into (sorted-map)
          (for [[n m] (get-in j [reg "entries"])] [(name-kw n) (get m "protocol_id")]))))

(defn inner-jar-entries
  "{path json-string} for version.json and every data/minecraft/{recipe,tags/item,tags/block}/*.json
   in the inner jar."
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
              (recur (if (or (= n "version.json") (re-matches #"data/minecraft/(recipe/[^/]+|tags/(item|block)/.+)\.json" n))
                       (assoc acc n (String. (.readAllBytes z) "UTF-8"))
                       acc)))
            acc))))))

(defn write-rows
  "Write rows to file f as an EDN vector, one row per line, after the header comment."
  [f header rows]
  (with-open [w (io/writer f)]
    (.write w header)
    (.write w "[\n")
    (doseq [r rows] (.write w (str " " (pr-str r) "\n")))
    (.write w "]\n")))

(defn write-map
  "Write m to file f as an EDN map, one entry per line in m's order, after the header comment."
  [f header m]
  (with-open [w (io/writer f)]
    (.write w header)
    (.write w "{\n")
    (doseq [[k v] m] (.write w (str " " (pr-str k) " " (pr-str v) "\n")))
    (.write w "}\n")))

(defn -main
  "Write the --reports tables (blocks, packets, items, entity types) into out; with a server
   jar also the jar tables (version, item tags, recipes, harvest); with the probe's JSON too,
   the tables read from the game's classes (hardness, materials)."
  [reports out & [server-jar probe-json]]
  (let [hdr ";; generated by scripts/datagen.sh from vanilla 26.1.2 --reports; do not edit\n"
        jar-hdr ";; generated by scripts/datagen.sh from the vanilla 26.1.2 server jar data; do not edit\n"
        probe-hdr ";; generated by scripts/datagen.sh by clojurecraft.jar-probe: reflection against the vanilla 26.1.2 server's own classes (BlockState.getDestroySpeed, ToolMaterial); not in --reports or any data file; do not edit\n"]
    (when server-jar
      (let [entries (inner-jar-entries server-jar)
            tags (item-tags entries)]
        (write-map (io/file out "version.edn") jar-hdr (version entries))
        (write-map (io/file out "item-tags.edn") jar-hdr tags)
        (write-rows (io/file out "recipes.edn") jar-hdr (recipes entries tags))
        (write-map (io/file out "harvest.edn") jar-hdr (harvest (tags-of entries "block")))
        (println "wrote" (str out "/{version,item-tags,recipes,harvest}.edn"))))
    (when probe-json
      (let [probe (json/read-str (slurp probe-json))]
        (write-map (io/file out "hardness.edn") probe-hdr (hardness probe (blocks reports)))
        (write-map (io/file out "materials.edn") probe-hdr (materials probe))
        (println "wrote" (str out "/{hardness,materials}.edn"))))
    (write-rows (io/file out "blocks.edn") hdr (blocks reports))
    (write-map (io/file out "packets.edn") hdr (packets reports))
    (write-map (io/file out "items.edn") hdr (registry reports "minecraft:item"))
    (write-map (io/file out "entity-types.edn") hdr (registry reports "minecraft:entity_type"))
    (println "wrote" (str out "/{blocks,packets,items,entity-types}.edn"))))
