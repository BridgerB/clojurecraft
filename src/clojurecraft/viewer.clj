(ns clojurecraft.viewer
  "A browser view of what the bot sees, as an observer of the world atom (--viewer <port>): the
   blocks round it, itself, the entities, and what it is thinking (the intent, its goal and its
   children, the chain reached). A watch hands each new world to a thread of its own, which keeps
   the latest frame; the page polls the frame and fetches a column again when its version moves.
   Nothing in game, plan or intent knows this namespace exists, and it never writes the atom.

   The page draws with steve's renderer (typecraft's viewer: the vanilla block models and
   blockstates, a texture atlas, biome tints, ambient occlusion, sky and fog), vendored as
   resources/clojurecraft/viewer/web/ and fed what it was fed in steve: an assets message and
   chunk columns in the network format (column-bytes). The assets are the game's own, read from
   the client jar on this machine (--viewer-jar, Prism's 26.1.2 by default) at start, and the
   few files the page fetches by name are extracted to cache/viewer/, which git ignores: no
   Mojang asset is in the repository."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.entity :as entity]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def radius 6)                        ; chunk columns each side of the bot's: 13x13, about 100 blocks round it
(def entity-range 64.0)               ; blocks from the bot an entity is shown within
(def frame-every-ms 100)              ; the frame thread's rest between two frames: ten a second at most
(def reach 4.5)                       ; blocks the eyes' ray goes for the block under the crosshair (a survival player's reach)
(def arm-packets #{:swing :attack :use-item :use-item-on}) ; what the bot sends that moves its arm
(def focus-kept 60)                   ; focus changes the frame keeps, newest last
(def direct-bits 15)                  ; bits a cell in a section with more than 256 states: the server's global palette

(def block-states
  "{block-name [[property [value ...]] ...]}: the generated report order, last property fastest."
  (edn/read-string (slurp (io/resource "clojurecraft/block-states.edn"))))

(def cutout
  "Blocks whose full cube shows what is behind it (holes, translucency): a neighbour's face shows."
  #"leaves|glass|^ice$|frosted_ice|spawner|grate|slime_block|honey_block|^mangrove_roots$|barrier")

(def tint-defaults
  "The renderer's tints for what no biome names: steve's, the game's plains grass and water."
  {:grassDefault [0.48 0.74 0.31] :foliageDefault [0.48 0.74 0.31] :waterDefault [0.25 0.29 0.98]})

;; ---------------------------------------------------------------- the game's models, as data

(defn short-id
  "A model or texture reference without its namespace: \"minecraft:block/dirt\" → \"block/dirt\"."
  [s]
  (str/replace (str s) #"^minecraft:" ""))

(defn model-chain
  "The model named id and its parents, leaf first, from assets {\"models/<id>\" json}: a
   chain of at most 16 (a cycle cannot hang it), each with its \"id\"."
  [assets id]
  (->> [id 0]
       (iterate (fn [[id n]]
                  (when-let [m (get assets (str "models/" id))]
                    (when (< n 16)
                      [(some-> (get m "parent")
                               short-id) (inc n)]))))
       (take-while some?)
       (map first)
       (take-while some?)
       (keep (fn [id]
               (some-> (get assets (str "models/" id))
                       (assoc "id" id))))))

(defn plain-textures
  "A model with every texture variable a plain reference: 26.x writes some as
   {\"sprite\" ref \"force_translucent\" true} (glass, panes, redstone dust), which steve's
   renderer, made against older models, reads as a string."
  [model]
  (cond-> model
    (map? (get model "textures"))
    (update "textures"
            #(into {}
                   (map (fn [[k v]] [k
                                     (cond (map? v) (get v "sprite")
                                           :else v)]))
                   %))))

(defn textures-of
  "The texture variables of a model chain, a child's over its parent's, as plain references."
  [chain]
  (reduce (fn [m model] (merge (get (plain-textures model) "textures") m)) {} chain))

(defn resolve-texture
  "The texture a reference names (\"#side\" through textures, at most 8 hops), as \"block/x\"; nil
   when it names none."
  [textures ref]
  (some->> ref
           (iterate #(when (and (string? %) (str/starts-with? % "#")) (get textures (subs % 1))))
           (take 9)
           (take-while some?)
           (remove #(str/starts-with? % "#"))
           first
           short-id))

(defn first-model
  "The model id a block name is drawn from by default: its blockstate's \"\" or \"axis=y\" variant,
   else the first by key, else the first multipart's; the first of a weighted list. Nil without
   one."
  [assets block-name]
  (let [{:strs [variants multipart]} (get assets (str "blockstates/" block-name))
        pick (fn [v]
               (cond (sequential? v) (first v)
                     :else v))
        v (cond (seq variants)
                (or (get variants "") (get variants "axis=y") (val (first (sort-by key variants))))
                (seq multipart) (get (first multipart) "apply"))]
    (some-> (pick v)
            (get "model")
            short-id)))

(defn opaque-cube?
  "Does the block hide what is behind each face: its default model is only full cubes and it is
   not one of the cutout blocks? The renderer culls a face against such a neighbour; a guess from
   the models where steve's registry had a flag (11 of 1168 differ, none common)."
  [assets block-name]
  (let [elements (some #(get % "elements") (model-chain assets (first-model assets block-name)))]
    (and (seq elements)
         (every? #(and (= [0 0 0] (mapv long (get % "from")))
                       (= [16 16 16] (mapv long (get % "to"))))
                 elements)
         (not (re-find cutout block-name)))))

(defn registry-blocks
  "The renderer's block registry: for every block, in state order, its name, whether it lets a
   neighbour's face show (transparent), whether it collides (boundingBox), its first state id and
   its properties (block-states), from which a state id's model variant is chosen."
  [assets]
  (vec (for [[n _ lo] blocks/table
             :let [s (name n)]]
         {:name s
          :transparent (not (opaque-cube? assets s))
          :boundingBox (cond (blocks/solid? lo) "block"
                             :else "empty")
          :minStateId lo
          :states (mapv (fn [[p vs]] {:name p :values vs}) (get block-states n))})))

(defn colour
  "A \"#rrggbb\" or an int colour as the renderer's [r g b], each 0-1."
  [c]
  (let [v (cond (string? c) (Long/parseLong (subs c 1) 16)
                :else (long c))]
    (mapv #(/ (double (bit-and (bit-shift-right v %) 0xff)) 255.0) [16 8 0])))

(defn redstone-tint
  "The colour of redstone wire at power 0-15, as the game draws it."
  [power]
  (let [f (/ (double power) 15.0)]
    (mapv #(max 0.0 (min 1.0 %))
          [(+ (* f 0.6)
              (cond (pos? f) 0.4
                    :else 0.3)) (- (* f f 0.7) 0.5) (- (* f f 0.6) 0.7)])))

(defn biome-names
  "The biome names in the server's registry order: the jar's biome files by name, the order
   vanilla's registry (and steve's table of 65) has them in. A biome id in a chunk is an index."
  [assets]
  (vec (sort (for [[path] assets :when (str/starts-with? path "biome/")] (subs path 6)))))

(defn colormap-colour
  "The colour vanilla's colormap (256x256 ARGB, row-major) gives a climate: temperature and
   downfall clamped to 0-1, the downfall scaled by the temperature, the pixel at
   x = (1 - temperature) * 255, y = (1 - downfall) * 255 (ColorMapColorUtil.get)."
  [^ints m temperature downfall]
  (let [t (max 0.0 (min 1.0 (double temperature)))
        d (* t (max 0.0 (min 1.0 (double downfall))))
        i (bit-or (bit-shift-left (long (* (- 1.0 d) 255.0)) 8) (long (* (- 1.0 t) 255.0)))]
    (cond (< i (alength m)) (bit-and (aget m i) 0xffffff)
          :else 0xff00ff)))

(defn grass-colour
  "A biome's grass colour as vanilla works it out: its own grass_color, else the grass colormap
   at its climate, then its modifier (a swamp's fixed dark green, a dark forest's darkening)."
  [assets biome]
  (let [{:strs [temperature downfall effects]} biome
        base (cond (get effects "grass_color")
                   (Long/parseLong (subs (get effects "grass_color") 1) 16)
                   :else (colormap-colour (get assets "colormap/grass") temperature downfall))]
    (case (get effects "grass_color_modifier")
      "swamp" 0x6a7039
      "dark_forest" (bit-shift-right (+ (bit-and base 0xfefefe) 0x28340a) 1)
      base)))

(defn foliage-colour
  "A biome's leaf colour as vanilla works it out: its own foliage_color, else the foliage
   colormap at its climate."
  [assets biome]
  (let [{:strs [temperature downfall effects]} biome]
    (cond (get effects "foliage_color") (Long/parseLong (subs (get effects "foliage_color") 1) 16)
          :else (colormap-colour (get assets "colormap/foliage") temperature downfall))))

(defn by-biome
  "{biome-name (f assets biome-json)} for every biome file in assets, nils dropped."
  [assets f]
  (into (sorted-map)
        (for [[path j] assets
              :when (str/starts-with? path "biome/")
              :let [c (f assets j)]
              :when c]
          [(subs path 6) c])))

(defn tints
  "The renderer's tints: grass and leaf colours by biome (grass-colour, foliage-colour; from the
   colormaps when the jar has them), water by biome, redstone by power, the fixed leaf and lily
   pad colours vanilla never tints by biome, and the defaults."
  [assets]
  (let [maps? (and (get assets "colormap/grass") (get assets "colormap/foliage"))]
    (merge {:grass (cond maps? (update-vals (by-biome assets grass-colour) colour)
                         :else {})
            :foliage (cond maps? (update-vals (by-biome assets foliage-colour) colour)
                           :else {})
            :water (update-vals (by-biome assets #(get-in %2 ["effects" "water_color"])) colour)
            :redstone (into (sorted-map) (for [p (range 16)] [(str p) (redstone-tint p)]))
            :constant {"attached_stem" [0.9 0.9 0.1]
                       "lily_pad" (colour 0x208030)
                       "birch_leaves" (colour 0x80a755)
                       "spruce_leaves" (colour 0x619961)}}
           tint-defaults)))

(defn skies
  "{biome-name [r g b]}: each biome's sky colour (its visual/sky_color attribute)."
  [assets]
  (update-vals (by-biome assets #(get-in %2 ["attributes" "minecraft:visual/sky_color"])) colour))

(defn assets-message
  "The renderer's assets, as steve's server sent them: every blockstate and block model by name,
   every block texture (textures: {name base64 png}), the tints, the block registry, the biomes by
   id, and (beyond steve's) each biome's sky colour."
  [assets textures]
  (let [under (fn [prefix]
                (into (sorted-map)
                      (for [[path j] assets
                            :when (str/starts-with? path prefix)]
                        [(subs path (count prefix)) j])))]
    {:type "assets"
     :blockStates (under "blockstates/")
     :blockModels (update-vals (under "models/block/") plain-textures)
     :textureNames (vec (sort (keys textures)))
     :textureData textures
     :tints (tints assets)
     :blocks (registry-blocks assets)
     :biomes (vec (map-indexed (fn [i n] {:id i :name n}) (biome-names assets)))
     :skies (skies assets)
     :entityModels {}}))

(defn item-model
  "The model id an item definition (assets \"items/<name>\") draws with: its first \"model\" string,
   found depth first through selects and conditions; nil without one."
  [assets item-name]
  (some->> (tree-seq coll? seq (get assets (str "items/" item-name)))
           (some (fn [x] (when (and (map-entry? x) (= "model" (key x)) (string? (val x))) (val x))))
           short-id))

(defn icon-texture
  "The texture an item's icon is: its flat model's layer0, else its block model's particle
   texture (a log's side, a table's front); nil when the jar names none."
  [assets item-name]
  (when-let [id (item-model assets item-name)]
    (let [t (textures-of (model-chain assets id))]
      (or (resolve-texture t "#layer0") (resolve-texture t "#particle")))))

(defn icons
  "{item-name texture} for every item whose icon the jar resolves."
  [assets]
  (into {} (for [[n] blocks/items :let [s (name n) t (icon-texture assets s)] :when t] [s t])))

;; ---------------------------------------------------------------- the frame: the bot, its thoughts

(defn plain
  "x with every keyword a string (its namespace kept: \"intent/kind\"), sets sorted vectors and
   anything else not JSON a string, ready for json/write-str."
  [x]
  (cond (keyword? x) (subs (str x) 1)
        (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
        (set? x) (mapv plain (sort-by str x))
        (sequential? x) (mapv plain x)
        (or (number? x) (string? x) (boolean? x) (nil? x)) x
        :else (str x)))

(def intent-keys
  "What an intent shows of itself: what it is, why, at what and how far along."
  [:intent/kind :intent/goal :intent/target :intent/stage :intent/for :intent/cell :intent/item
   :intent/recipe :intent/status :intent/id :intent/build :intent/op :intent/reason])

(defn intent-view
  "The intent's shown keys (intent-keys), with its child's under :intent/child, and so on down;
   nil for no intent."
  [i]
  (when (map? i)
    (cond-> (select-keys i intent-keys)
      (map? (:intent/child i)) (assoc :intent/child (intent-view (:intent/child i))))))

(defn chunk-of
  "The column key [cx cz] a block or feet position is in."
  [[x _ z]]
  [(bit-shift-right (long (math/floor (double x))) 4)
   (bit-shift-right (long (math/floor (double z))) 4)])

(defn overlay-by-column
  "The block overlay (:world/blocks) grouped by column: {[cx cz] {pos id}}."
  [world]
  (reduce-kv (fn [m pos id] (assoc-in m [(chunk-of pos) pos] id)) {} (:world/blocks world)))

(defn columns
  "{\"cx,cz\" version} of the loaded columns within radius of the bot: the version moves when the
   column is replaced or a block over it changes, never otherwise (the column's identity, the
   overlay's hash)."
  [world]
  (let [[cx cz] (chunk-of (:player/pos world))
        overlay (overlay-by-column world)]
    (into {}
          (for [x (range (- cx radius) (+ cx radius 1))
                z (range (- cz radius) (+ cz radius 1))
                :let [col (get (:world/chunks world) [x z])]
                :when col]
            [(str x "," z) (str (System/identityHashCode col) "-" (hash (get overlay [x z])))]))))

(defn entities-view
  "The entities within entity-range of the bot: id, kind, position, yaw in degrees, health,
   hostile?, and a dropped item's name."
  [world]
  (let [[px py pz] (:player/pos world)]
    (vec (for [[id e] (:world/entities world)
               :let [[x y z :as pos] (:entity/pos e)]
               :when (and pos
                          (<= (+ (abs (- (double x) (double px))) (abs (- (double z) (double pz))))
                              (* 2 entity-range))
                          (<= (abs (- (double y) (double py))) entity-range))]
           (cond-> {:id id :kind (entity/kind e) :pos pos :hostile (entity/hostile? e)}
             (:entity/yaw e) (assoc :yaw (:entity/yaw e))
             (:entity/health e) (assoc :health (:entity/health e))
             (get-in e [:entity/item :item])
             (assoc :item (recipe/item-name (get-in e [:entity/item :item]))))))))

(defn hotbar
  "The nine hotbar slots, each {:item name :count n} or nil."
  [world]
  (mapv (fn [k]
          (when-let [{:keys [item count]} (get (:player/inventory world) k)]
            {:item (recipe/item-name item) :count count}))
        (range inventory/hotbar-slots)))

(defn inventory-view
  "Every slot the bot holds, {player-slot {:item name :count n}}: 0-8 the hotbar, 9-35 the main
   inventory, 36-39 the armour (boots to helmet), 40 the offhand."
  [world]
  (into (sorted-map)
        (for [[k {:keys [item count]}] (:player/inventory world)
              :when item]
          [k {:item (recipe/item-name item) :count count}])))

(defn focus
  "What the bot's focus is, for telling a change of it: the intent's goal, kind, target and
   recipe; nil without an intent."
  [frame]
  (when-let [i (:intent frame)] ((juxt :intent/goal :intent/kind :intent/target :intent/recipe) i)))

(defn note-focus
  "history (focus changes, oldest first, at most focus-kept) with frame's focus added when it
   differs from the last one: {:goal :kind :target :recipe :stage :at}."
  [history frame]
  (let [f (focus frame)
        last-f (some-> (peek history)
                       ((juxt :goal :kind :target :recipe)))]
    (cond (or (nil? f) (= f last-f)) history
          :else
          (let [[goal kind target recipe] f
                h (conj (or history [])
                        {:goal goal
                         :kind kind
                         :target target
                         :recipe recipe
                         :stage (get-in frame [:intent :intent/stage])
                         :at (:now frame)})]
            (cond (> (count h) focus-kept) (subvec h (- (count h) focus-kept))
                  :else h)))))

(defn note
  "A flagged note as a line of the notes file: its number, when, what was written, and the bot's
   state then from frame (where, health, food, the held slot, the inventory, the intent)."
  [n at text frame]
  {:n n
   :at at
   :note text
   :pos (:pos frame)
   :health (:health frame)
   :food (:food frame)
   :held (:held frame)
   :inventory (:inventory frame)
   :intent (:intent frame)})

(defn biome-at
  "The biome id at a block or feet position from its column's :chunk/biomes (cells of 4x4x4,
   y then z then x), or nil: not loaded, or a column without biomes."
  [world [x y z :as pos]]
  (let [x (long (math/floor (double x)))
        y (long (math/floor (double y)))
        z (long (math/floor (double z)))
        col (get (:world/chunks world) (chunk-of pos))
        si (bit-shift-right (- y (chunk/column-min-y col)) 4)
        biomes (get-in col [:chunk/biomes si])]
    (when biomes
      (chunk/section-get biomes
                         (bit-or (bit-shift-left (bit-shift-right (bit-and y 15) 2) 4)
                                 (bit-shift-left (bit-shift-right (bit-and z 15) 2) 2)
                                 (bit-shift-right (bit-and x 15) 2))))))

(defn intents
  "The intent and its children, outermost first."
  [world]
  (take-while some? (iterate :intent/child (:plan/intent world))))

(defn dig-view
  "The block the bot is breaking and how far: {:pos target :progress 0-1}, the elapsed time
   since its START over the dig's own expected time (:intent/dig-ms, dig/dig-time with the held
   tool and penalties when it began); nil when no :dig is at its :digging stage."
  [world]
  (when-let [{:intent/keys [target started dig-ms]}
             (first (filter #(and (= :dig (:intent/kind %)) (= :digging (:intent/stage %)))
                            (intents world)))]
    {:pos target
     :progress (max 0.0
                    (min 1.0 (/ (double (- (:time/now world) started)) (double (max 1 dig-ms)))))}))

(defn looked-at
  "The block cell under the crosshair: the first that is neither air nor a fluid along the eyes'
   ray within reach, by the bot's look (physics/direction); nil when none."
  [world]
  (when-let [pos (:player/pos world)]
    (some
     (fn [cell]
       (let [id (terrain/block-at world cell)]
         (when (and id (not (blocks/air? id)) (nil? (blocks/fluid id))) cell)))
     (physics/cells-along (physics/eye pos) (physics/direction (:player/look world)) reach 0.05))))

(defn arm-moves
  "How many of effects send a packet that moves the arm (arm-packets): a swing, an attack, a use."
  [effects]
  (count (filter #(and (= :send (:effect/kind %))
                       (arm-packets (get-in % [:effect/packet :packet/name])))
                 effects)))

(defn frame
  "What the page shows of one world: who and where the bot is, how it looks, its health, food
   and hotbar and every slot it holds, the block it breaks (dig-view) and the one under its crosshair (looked-at), the
   biome under its feet, the entities round it, what it intends (intent-view) and has reached, and the
   version of every column round it (columns). Absent attributes stay absent."
  [world]
  (let [w world]
    (into {}
          (remove (comp nil? val))
          {:name (:bot/name w)
           :tick (:time/tick w)
           :now (:time/now w)
           :dimension (:player/dimension w)
           :pos (:player/pos w)
           :look (:player/look w)
           :biome (some->> (:player/pos w)
                           (biome-at w))
           :health (:player/health w)
           :food (:player/food w)
           :air (:player/air w)
           :held (:player/held-slot w)
           :hotbar (hotbar w)
           :inventory (inventory-view w)
           :dig (dig-view w)
           :looking (when (:player/look w) (looked-at w))
           :entities (entities-view w)
           :intent (intent-view (:plan/intent w))
           :failed (intent-view (:plan/failed w))
           :reached (:plan/reached w)
           :goals (:plan/goals w)
           :wait (:plan/wait w)
           :waiting (:plan/waiting w)
           :status (:plan/status w)
           :columns (when (:player/pos w) (columns w))})))

(defn meta-view
  "What the page needs once: the chain a fresh run plays (plan/through the current stage) and the
   rows that can take the focus from it (the always rows), each with its doc."
  []
  (let [docs (into {} (map (juxt :goal/id :goal/doc)) plan/goals)
        row (fn [id] {:id id :doc (docs id)})]
    {:chain (mapv row (plan/through (plan/current-until)))
     :always (mapv (comp row :goal/id) (filter :goal/always? plan/targets))
     :radius radius}))

;; ---------------------------------------------------------------- a column in the network format

(defn section-ids
  "The 4096 state ids of section si of column key in world, in chunk order (y, then z, then x),
   with the overlay's blocks over the chunk's."
  [world key si overlay]
  (let [col (get-in world [:world/chunks key])
        section (nth (:sections col) si)
        y0 (+ (chunk/column-min-y col) (* 16 si))
        [cx cz] key
        ids (long-array 4096)]
    (dotimes [i 4096] (aset ids i (chunk/section-get section i)))
    (doseq [[[x y z] id] overlay
            :when (<= y0 y (+ y0 15))]
      (aset ids (chunk/block-index (- x (* 16 cx)) (- y y0) (- z (* 16 cz))) (long id)))
    ids))

(defn bits-for
  "Bits a cell of a section with n distinct states takes on the wire: 0 for one, 4 to 8 through a
   palette, direct-bits past 256."
  ^long [^long n]
  (cond (= 1 n) 0
        (<= n 256) (max 4 (long (math/ceil (/ (math/log n) (math/log 2)))))
        :else direct-bits))

(defn pack
  "Cell values (each under 2^bits) packed into longs as the protocol packs them: 64/bits a long,
   the first in the low bits, none straddling two longs."
  ^longs [^long bits values]
  (let [per (quot 64 bits)
        out (long-array (long (math/ceil (/ 4096.0 per))))]
    (reduce (fn [^long i ^long v]
              (let [li (quot i per)]
                (aset out li (bit-or (aget out li) (bit-shift-left v (* bits (rem i per)))))
                (inc i)))
            0
            values)
    out))

(defn write-container!
  "Write a paletted container as the wire holds it (chunk/read-container's shape back): the bits,
   the value or the palette, the longs with no count."
  [o {:keys [single bits palette ^longs longs]}]
  (cond single (do (b/write-u8 o 0) (b/write-varint o single))
        :else
        (do (b/write-u8 o bits)
            (when palette (b/write-varint o (count palette)) (run! #(b/write-varint o %) palette))
            (run! #(b/write-i64 o %) longs))))

(defn write-section!
  "Write one section as level_chunk_with_light holds it in 26.1 (two counts, the block container
   without an array length, the biome container) to o."
  [o ^longs ids biomes]
  (let [palette (vec (distinct ids))
        bits (bits-for (count palette))]
    (b/write-i16 o (count (remove blocks/air? ids)))
    (b/write-i16 o 0)
    (b/write-u8 o bits)
    (cond (zero? bits) (b/write-varint o (first palette))
          (= direct-bits bits) (run! #(b/write-i64 o %) (pack bits ids))
          :else
          (let [index (zipmap palette (range))]
            (b/write-varint o (count palette))
            (run! #(b/write-varint o %) palette)
            (run! #(b/write-i64 o %) (pack bits (map index ids)))))
    (write-container! o biomes)))

(defn column-bytes
  "Column key of world as chunk data bytes: every section in order, the overlay applied, its own
   biomes (:chunk/biomes) or the container fallback where it has none; the format the
   renderer's loadChunkColumn reads (no array lengths)."
  [world key fallback]
  (let [col (get-in world [:world/chunks key])
        overlay (get (overlay-by-column world) key)]
    (b/with-out (fn [o]
                  (dotimes [si (count (:sections col))]
                    (write-section! o
                                    (section-ids world key si overlay)
                                    (get-in col [:chunk/biomes si] fallback)))))))

(defn column-view
  "Column key [cx cz] of world for the page: {:x :z :minY :height :buf base64 column-bytes}, with
   fallback where it has no biomes; nil when the column is not loaded."
  [world [cx cz :as key] fallback]
  (when-let [col (get-in world [:world/chunks key])]
    {:x cx
     :z cz
     :minY (chunk/column-min-y col)
     :height (* 16 (count (:sections col)))
     :buf (.encodeToString (java.util.Base64/getEncoder) (column-bytes world key fallback))}))

(defn jar-entry-kind
  "What the viewer takes from a client jar entry name: :json for a blockstate, model or item
   definition, :biome for a biome file, :block-texture for a block texture, :colormap for the
   grass or foliage colormap, :file for an item
   texture or the player skin (served by name), nil for the rest."
  [n]
  (condp re-matches n
    #"assets/minecraft/(blockstates|models|items)/.*\.json" :json
    #"data/minecraft/worldgen/biome/[^/]+\.json" :biome
    #"assets/minecraft/textures/block/[^/]+\.png" :block-texture
    #"assets/minecraft/textures/colormap/(grass|foliage)\.png" :colormap
    #"assets/minecraft/textures/(item/[^/]+|entity/player/wide/steve)\.png" :file
    nil))

;;;; I/O: the jar, the frame thread, the HTTP server ;;;;

(def default-jar
  (str
   (System/getProperty "user.home")
   "/Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar"))

(def cache-dir "cache/viewer/26.1.2") ; the files the page fetches by name; git ignores cache/

(defn read-assets!
  "From the client jar: [assets textures]: every blockstate, model, item definition and biome
   file as {\"models/block/dirt\" json, \"biome/plains\" json}, the two colormaps as int arrays
   (\"colormap/grass\"), and every block texture as {name
   base64}; item textures and the player skin are extracted under cache-dir (once: a file
   already there is kept). Both empty when the jar is missing, logged."
  [jar]
  (cond (not (.exists (io/file jar))) (do (cli/log "viewer: no client jar at" jar) [{} {}])
        :else
        (with-open [z (java.util.zip.ZipFile. ^String jar)]
          (reduce
           (fn [[assets textures :as acc] ^java.util.zip.ZipEntry e]
             (let [n (.getName e)
                   read-json #(with-open [r (io/reader (.getInputStream z e))] (json/read r))
                   stem (str/replace n #"^.*/([^/]+)\.[a-z]+$" "$1")]
               (case (jar-entry-kind n)
                 :json
                 [(assoc assets (str/replace n #"^assets/minecraft/(.*)\.json$" "$1") (read-json))
                  textures]
                 :biome [(assoc assets (str "biome/" stem) (read-json)) textures]
                 :block-texture
                 [assets
                  (assoc textures
                         stem
                         (with-open [in (.getInputStream z e)]
                           (.encodeToString (java.util.Base64/getEncoder) (.readAllBytes in))))]
                 :colormap
                 [(assoc assets
                         (str "colormap/" stem)
                         (with-open [in (.getInputStream z e)]
                           (let [img (javax.imageio.ImageIO/read in)
                                 w (.getWidth img)
                                 h (.getHeight img)]
                             (.getRGB img 0 0 w h nil 0 w)))) textures]
                 :file
                 (let [f (io/file cache-dir (str/replace n #"^assets/minecraft/" ""))]
                   (when-not (.exists f)
                     (io/make-parents f)
                     (with-open [in (.getInputStream z e)] (io/copy in f)))
                   acc)
                 acc)))
           [{} {}]
           (enumeration-seq (.entries z))))))

(defn respond!
  "Answer ex with status, a content type and body bytes; cache says whether the browser may keep
   it."
  [^com.sun.net.httpserver.HttpExchange ex status ^String type ^bytes body cache]
  (.add (.getResponseHeaders ex) "Content-Type" type)
  (.add (.getResponseHeaders ex)
        "Cache-Control"
        (cond cache "max-age=3600"
              :else "no-store"))
  (.sendResponseHeaders ex status (alength body))
  (with-open [o (.getResponseBody ex)] (.write o body)))

(defn json!
  "Answer ex with x as JSON (plain), never cached."
  [ex x]
  (respond! ex 200 "application/json" (.getBytes ^String (json/write-str (plain x)) "UTF-8") false))

(defn query
  "The query string of ex as {name value}, values undecoded."
  [^com.sun.net.httpserver.HttpExchange ex]
  (into {}
        (for [kv (str/split (or (.getRawQuery (.getRequestURI ex)) "") #"&")
              :let [[k v] (str/split kv #"=" 2)]
              :when v]
          [k v])))

(defn resource!
  "Answer ex with the resource at path under clojurecraft/viewer/, or 404."
  [ex path type]
  (let [r (io/resource (str "clojurecraft/viewer/" path))]
    (cond r (respond! ex 200 type (with-open [in (io/input-stream r)] (.readAllBytes in)) false)
          :else (respond! ex 404 "text/plain" (.getBytes "not found") false))))

(defn body
  "The request body of ex as a string, at most 500 characters."
  [^com.sun.net.httpserver.HttpExchange ex]
  (let [s (slurp (.getRequestBody ex) :encoding "UTF-8")] (subs s 0 (min 500 (count s)))))

(defn rcon!
  "Run one command on the server over RCON ({:host :port :pass}); its answer, or the error's
   message when it could not be run (no RCON given, refused, down)."
  [{:keys [host port pass]} command]
  (cond (not (and port pass))
        "no rcon: start the bot with --viewer-rcon-port and --viewer-rcon-pass-file"
        :else
        (try (rcon/with-rcon (or host "127.0.0.1") port pass #(rcon/command! % command))
             (catch Exception e (str "rcon: " (.getMessage e))))))

(defn notes!
  "The notes in the notes file, newest first; empty when there is none."
  [path]
  (let [f (some-> path
                  io/file)]
    (cond (and f (.exists f))
          (reverse (map json/read-str (remove str/blank? (str/split-lines (slurp f)))))
          :else [])))

(defn act!
  "Pause, resume or flag, from the page: pause freezes the server's ticks (/tick freeze) and
   resume unfreezes them, both over RCON with a line in chat; a flag appends a note (note) to the
   notes file. Returns the answer for the page."
  [what text state {:keys [rcon notes]}]
  (case what
    "pause"
    (do (rcon! rcon "tick freeze")
        (rcon! rcon "say paused from the viewer")
        (swap! state assoc :paused true)
        {:paused true})
    "go"
    (do (rcon! rcon "tick unfreeze")
        (rcon! rcon "say resumed from the viewer")
        (swap! state assoc :paused false)
        {:paused false})
    "bad"
    (let [n (inc (count (notes! notes)))
          line (note n (str (java.time.LocalTime/now)) text (:frame @state))]
      (io/make-parents (io/file notes))
      (spit notes (str (json/write-str (plain line)) "\n") :append true)
      (rcon! rcon (str "say noted #" n))
      {:n n})))

(defn route!
  "Answer one request from the latest state (:world, :frame, :history, :paused) and the static
   data: the page and its scripts, the frame (with the focus history and the pause), the meta,
   the icons, the assets, a column, a cached texture, the notes, and POST pause, go and bad;
   404 otherwise."
  [^com.sun.net.httpserver.HttpExchange ex state static]
  (let [path (.getPath (.getRequestURI ex))
        {:keys [world frame history paused]} @state]
    (cond (= "POST" (.getRequestMethod ex))
          (cond (#{"/pause" "/go" "/bad"} path)
                (json! ex (act! (subs path 1) (body ex) state static))
                :else (respond! ex 404 "text/plain" (.getBytes "not found") false))
          (= "/" path) (resource! ex "index.html" "text/html; charset=utf-8")
          (= "/notes" path) (json! ex (notes! (:notes static)))
          (#{"/web/viewer.js" "/web/worker.js"} path) (resource! ex (subs path 1) "text/javascript")
          (= "/frame" path)
          (json! ex (assoc (or frame {}) :history history :paused (boolean paused)))
          (= "/meta" path) (json! ex (:meta static))
          (= "/icons" path) (json! ex (:icons static))
          (= "/assets.json" path) (respond! ex 200 "application/json" (:assets static) false)
          (= "/column" path)
          (let [{:strs [x z]} (query ex)]
            (json! ex
                   (or (some-> world
                               (column-view [(parse-long x) (parse-long z)] (:biome static)))
                       {})))
          (re-matches #"/tex/(item/[a-z0-9_]+|entity/player/wide/steve)\.png" path)
          (let [f (io/file cache-dir "textures" (subs path 5))]
            (cond (.exists f)
                  (respond! ex 200 "image/png" (java.nio.file.Files/readAllBytes (.toPath f)) true)
                  :else (respond! ex 404 "text/plain" (.getBytes "no such texture") false)))
          :else (respond! ex 404 "text/plain" (.getBytes "not found") false))))

(defn serve!
  "Watch world* and serve the page on 127.0.0.1:port (opts: :jar the client jar, :rcon
   {:host :port :pass} for pause and resume, :notes the file flags go to): the watch offers each new world to a
   one-slot sliding channel (it never waits) and counts the arm's packets among the effects the
   loop is about to send (arm-moves, the frame's :swings), a thread of its own turns the latest into a frame
   at most every frame-every-ms, and HTTP threads answer from that. Returns {:close (fn [])}."
  ([world* port] (serve! world* port {}))
  ([world* port {:keys [jar rcon notes]}]
   (let [[assets textures] (read-assets! (or jar default-jar))
         plains (max 0 (.indexOf ^java.util.List (biome-names assets) "plains"))
         static {:biome {:single plains}
                 :assets (.getBytes ^String (json/write-str (assets-message assets textures))
                                    "UTF-8")
                 :icons (icons assets)
                 :meta (meta-view)
                 :rcon rcon
                 :notes (or notes "data/viewer-notes.ndjson")}
         state (atom {})
         swings (atom 0)
         ch (async/chan (async/sliding-buffer 1))
         done (async/thread
               (loop []
                 (when-let [w (async/<!! ch)]
                   (let [f (assoc (frame w) :swings @swings)]
                     (swap! state
                       #(assoc % :world w :frame f :history (note-focus (:history %) f))))
                   (Thread/sleep (long frame-every-ms))
                   (recur))))
         server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1"
                                                                                       (int port))
                                                          0)
         pool (java.util.concurrent.Executors/newFixedThreadPool 4)]
     (.createContext server
                     "/"
                     (reify
                      com.sun.net.httpserver.HttpHandler
                        (handle [_ ex]
                          (try (route! ex state static)
                               (catch Exception e
                                 (cli/log "viewer:" (.getMessage e))
                                 (.close ^com.sun.net.httpserver.HttpExchange ex))))))
     (.setExecutor server pool)
     (.start server)
     (add-watch world*
                ::viewer
                (fn [_ _ old new]
                  (when-not (identical? (:bot/effects old) (:bot/effects new))
                    (swap! swings + (arm-moves (:bot/effects new))))
                  (async/offer! ch new)))
     (async/offer! ch @world*)
     (cli/log (str "viewer on http://127.0.0.1:" port "/"))
     {:close (fn []
               (remove-watch world* ::viewer)
               (async/close! ch)
               (async/<!! done)
               (.stop server 0)
               (.shutdown pool))})))
