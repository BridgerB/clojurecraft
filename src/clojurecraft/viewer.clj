(ns clojurecraft.viewer
  "A browser view of what the bot sees, as an observer of the world atom (--viewer <port>): the
   blocks round it, itself, the entities, and what it is thinking (the intent, its goal and its
   children, the chain reached). A watch hands each new world to a thread of its own, which keeps
   the latest frame; the page polls the frame and fetches a column again when its version moves.
   Nothing in game, plan or intent knows this namespace exists, and it never writes the atom.

   The textures are the game's own, read from the client jar on this machine (--viewer-jar,
   Prism's 26.1.2 by default) into cache/viewer/, which git ignores: no Mojang asset is in the
   repository. A block is drawn from its first blockstate variant's model, cut down to a box with
   a texture a face, a cross (plants) or a liquid; that is enough to tell stone from ore from
   water, and the page does the rest (resources/clojurecraft/viewer/index.html, three.js)."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.entity :as entity]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def radius 3)                        ; chunk columns each side of the bot's: 7x7, about 48 blocks round it
(def entity-range 64.0)               ; blocks from the bot an entity is shown within
(def frame-every-ms 100)              ; the frame thread's rest between two frames: ten a second at most

(def tints
  "The colour a tinted face is drawn in, by what it is: the game's plains grass and foliage, its
   two fixed leaf colours, and its default water. A biome's own colour is not known to the bot."
  {:grass "#79c05a" :foliage "#59ae30" :birch "#80a755" :spruce "#619961" :water "#3f76e4"})

(def cutout
  "Name parts of blocks that have holes or show what is behind them: a face next to one is drawn."
  ["leaves" "glass" "ice" "_door" "trapdoor" "spawner" "grate" "cobweb" "bars" "scaffolding"
   "mangrove_roots" "slime" "honey_block"])

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

(defn textures-of
  "The texture variables of a model chain, a child's over its parent's."
  [chain]
  (reduce (fn [m model] (merge (get model "textures") m)) {} chain))

(defn resolve-texture
  "The texture a reference names (\"#side\" through textures, at most 8 hops), as \"block/x\"; nil
   when it names none."
  [textures ref]
  (->> ref
       (iterate #(when (and (string? %) (str/starts-with? % "#")) (get textures (subs % 1))))
       (take 9)
       (take-while some?)
       (remove #(str/starts-with? % "#"))
       first
       (#(some-> %
                 short-id))))

(defn first-model
  "The model id a block name is drawn from: its blockstate's \"\" or \"axis=y\" variant, else
   the first by key, else the first multipart's; the first of a weighted list. Nil without one."
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

(defn tint-of
  "The tint colour a block's tinted faces take (tints)."
  [block-name]
  (cond (= "water" block-name) (:water tints)
        (str/starts-with? block-name "birch") (:birch tints)
        (str/starts-with? block-name "spruce") (:spruce tints)
        (or (str/includes? block-name "leaves") (str/includes? block-name "vine")) (:foliage tints)
        :else (:grass tints)))

(defn box-of
  "The box [x0 y0 z0 x1 y1 z1], in blocks (0-1), that holds every element of a model."
  [elements]
  (let [froms (map #(get % "from") elements)
        tos (map #(get % "to") elements)
        lo (fn [i] (/ (double (apply min (map #(nth % i) froms))) 16.0))
        hi (fn [i] (/ (double (apply max (map #(nth % i) tos))) 16.0))]
    [(lo 0) (lo 1) (lo 2) (hi 0) (hi 1) (hi 2)]))

(defn model-def
  "How the page draws a block named block-name with model id: {:kind \"cross\" :tex t} for a
   plant, {:kind \"cube\" :box b :faces {face texture} :tint {face colour} :opaque bool} for
   anything with elements (the first element's faces over the box of them all), or
   {:kind \"none\"} for a model with no elements (air, light, barrier)."
  [assets block-name id]
  (let [chain (model-chain assets id)
        ids (map #(get % "id") chain)
        textures (textures-of chain)
        elements (some #(get % "elements") chain)
        faces (get (first elements) "faces")
        box (when (seq elements) (box-of elements))]
    (cond (some #(str/ends-with? % "cross") ids)
          (cond-> {:kind "cross" :tex (resolve-texture textures "#cross")}
            (some #(str/includes? % "tinted") ids) (assoc :tint (tint-of block-name)))
          (empty? elements) {:kind "none"}
          :else
          {:kind "cube"
           :box box
           :faces (into {} (for [[f v] faces] [f (resolve-texture textures (get v "texture"))]))
           :tint (into {} (for [[f v] faces :when (get v "tintindex")] [f (tint-of block-name)]))
           :opaque (and (= [0.0 0.0 0.0 1.0 1.0 1.0] box)
                        (not-any? #(str/includes? block-name %) cutout))})))

(defn render-def
  "How the page draws the block named block-name (a string), from assets: air draws nothing,
   water and lava are liquids of their still texture, anything else its model (model-def)."
  [assets block-name]
  (let [id (first-model assets block-name)]
    (cond (contains? blocks/air-names (keyword block-name)) {:kind "none"}
          (= "water" block-name) {:kind "liquid" :tex "block/water_still" :tint (tint-of "water")}
          (= "lava" block-name) {:kind "liquid" :tex "block/lava_still"}
          (nil? id) {:kind "none"}
          :else (model-def assets block-name id))))

(defn item-model
  "The model id an item definition (assets \"items/<name>\") draws with: its first \"model\" string,
   found depth first through selects and conditions; nil without one."
  [assets item-name]
  (->> (tree-seq coll? seq (get assets (str "items/" item-name)))
       (some (fn [x] (when (and (map-entry? x) (= "model" (key x)) (string? (val x))) (val x))))
       (#(some-> %
                 short-id))))

(defn icon-texture
  "The texture an item's icon is: its flat model's layer0, or its block's cross or north face;
   nil when the jar names none."
  [assets item-name]
  (when-let [id (item-model assets item-name)]
    (let [layer0 (resolve-texture (textures-of (model-chain assets id)) "#layer0")
          d (model-def assets item-name id)]
      (or layer0 (:tex d) (get-in d [:faces "north"]) (first (vals (:faces d)))))))

(defn defs
  "{block-name render-def} for every block the generated tables know."
  [assets]
  (into {} (for [[n] blocks/table :let [s (name n)]] [s (render-def assets s)])))

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
  "The entities within entity-range of the bot: id, kind, position, yaw, health, hostile?, and a
   dropped item's name."
  [world]
  (let [[px py pz] (:player/pos world)]
    (vec (for [[id e] (:world/entities world)
               :let [[x y z :as pos] (:entity/pos e)]
               :when (and pos
                          (<= (+ (Math/abs (double (- x px))) (Math/abs (double (- z pz))))
                              (* 2 entity-range))
                          (<= (Math/abs (double (- y py))) entity-range))]
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

(defn frame
  "What the page shows of one world: who and where the bot is, how it looks, its health, food
   and hotbar, the entities round it, what it intends (intent-view) and has reached, and the
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
           :health (:player/health w)
           :food (:player/food w)
           :air (:player/air w)
           :held (:player/held-slot w)
           :hotbar (hotbar w)
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

;; ---------------------------------------------------------------- a column's blocks for the page

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

(defn encode-section
  "One section for the page: {:y its lowest block y :palette [name ...] :w 1|2 :data base64}, each
   cell its palette index in w bytes (little-endian), or nil when it is all air."
  [y0 ^longs ids]
  (let [names (mapv #(or (some-> (blocks/name-of %)
                                 name)
                         "air")
                    ids)
        palette (vec (distinct names))]
    (when-not (every? #(contains? blocks/air-names (keyword %)) palette)
      (let [index (zipmap palette (range))
            w (cond (<= (count palette) 256) 1
                    :else 2)
            out (byte-array (* w 4096))]
        (dotimes [i 4096]
          (let [v (long (index (nth names i)))]
            (aset out (* w i) (unchecked-byte v))
            (when (= 2 w) (aset out (inc (* w i)) (unchecked-byte (bit-shift-right v 8))))))
        {:y y0 :palette palette :w w :data (.encodeToString (java.util.Base64/getEncoder) out)}))))

(defn column-view
  "Column key [cx cz] of world as the page draws it: {:x :z :sections [...]} with every section
   that is not all air (encode-section), the overlay applied; nil when the column is not loaded."
  [world [cx cz :as key]]
  (when-let [col (get-in world [:world/chunks key])]
    (let [overlay (get (overlay-by-column world) key)]
      {:x cx
       :z cz
       :sections (into []
                       (keep (fn [si]
                               (encode-section (+ (chunk/column-min-y col) (* 16 si))
                                               (section-ids world key si overlay))))
                       (range (count (:sections col))))})))

(defn jar-entry-kind
  "What the viewer takes from a client jar entry name: :json for a blockstate, model or item
   definition, :texture for a block or item texture, nil for the rest."
  [n]
  (condp re-matches n
    #"assets/minecraft/(blockstates|models|items)/.*\.json" :json
    #"assets/minecraft/textures/(block|item)/[^/]+\.png" :texture
    nil))

;;;; I/O: the jar, the frame thread, the HTTP server ;;;;

(def default-jar
  (str
   (System/getProperty "user.home")
   "/Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar"))

(def cache-dir "cache/viewer/26.1.2") ; extracted textures; git ignores cache/

(defn read-assets!
  "Every blockstate, model and item definition in the jar, {\"models/block/dirt\" json}, and the
   block and item textures extracted under cache-dir (once: a file already there is kept). Empty
   when the jar is missing, logged: the page then draws untextured."
  [jar]
  (cond (not (.exists (io/file jar)))
        (do (cli/log "viewer: no client jar at" jar "- untextured") {})
        :else
        (with-open [z (java.util.zip.ZipFile. ^String jar)]
          (reduce (fn [m ^java.util.zip.ZipEntry e]
                    (let [n (.getName e)
                          rel (str/replace n #"^assets/minecraft/" "")]
                      (case (jar-entry-kind n)
                        :json
                        (assoc m
                               (str/replace rel #"\.json$" "")
                               (with-open [r (io/reader (.getInputStream z e))] (json/read r)))
                        :texture
                        (let [f (io/file cache-dir rel)]
                          (when-not (.exists f)
                            (io/make-parents f)
                            (with-open [in (.getInputStream z e)] (io/copy in f)))
                          m)
                        m)))
                  {}
                  (enumeration-seq (.entries z))))))

(defn respond!
  "Answer ex with status, a content type and body bytes, never cached."
  [^com.sun.net.httpserver.HttpExchange ex status ^String type ^bytes body]
  (.add (.getResponseHeaders ex) "Content-Type" type)
  (.add (.getResponseHeaders ex) "Cache-Control" "no-store")
  (.sendResponseHeaders ex status (alength body))
  (with-open [o (.getResponseBody ex)] (.write o body)))

(defn json!
  "Answer ex with x as JSON (plain)."
  [ex x]
  (respond! ex 200 "application/json" (.getBytes ^String (json/write-str (plain x)) "UTF-8")))

(defn query
  "The query string of ex as {name value}, values undecoded."
  [^com.sun.net.httpserver.HttpExchange ex]
  (into {}
        (for [kv (str/split (or (.getRawQuery (.getRequestURI ex)) "") #"&")
              :let [[k v] (str/split kv #"=" 2)]
              :when v]
          [k v])))

(defn route!
  "Answer one request from the latest state (:world, :frame) and the static data: the page, the
   frame, the meta, the block defs and icons, a column, a texture; 404 otherwise."
  [^com.sun.net.httpserver.HttpExchange ex state static]
  (let [path (.getPath (.getRequestURI ex))
        {:keys [world frame]} @state]
    (cond (= "/" path)
          (respond! ex
                    200
                    "text/html; charset=utf-8"
                    (.getBytes ^String (slurp (io/resource "clojurecraft/viewer/index.html"))
                               "UTF-8"))
          (= "/frame" path) (json! ex (or frame {}))
          (= "/meta" path) (json! ex (:meta static))
          (= "/defs" path) (json! ex {:blocks (:defs static) :icons (:icons static)})
          (= "/column" path)
          (let [{:strs [x z]} (query ex)]
            (json! ex
                   (or (some-> world
                               (column-view [(parse-long x) (parse-long z)]))
                       {})))
          (re-matches #"/tex/(block|item)/[a-z0-9_]+\.png" path)
          (let [f (io/file cache-dir "textures" (subs path 5))]
            (cond (.exists f)
                  (respond! ex 200 "image/png" (java.nio.file.Files/readAllBytes (.toPath f)))
                  :else (respond! ex 404 "text/plain" (.getBytes "no such texture"))))
          :else (respond! ex 404 "text/plain" (.getBytes "not found")))))

(defn serve!
  "Watch world* and serve the page on 127.0.0.1:port: the watch offers each new world to a
   one-slot sliding channel (it never waits), a thread of its own turns the latest into a frame
   at most every frame-every-ms, and HTTP threads answer from that. Returns {:close (fn [])}."
  ([world* port] (serve! world* port default-jar))
  ([world* port jar]
   (let [assets (read-assets! jar)
         static {:defs (defs assets) :icons (icons assets) :meta (meta-view)}
         state (atom {})
         ch (async/chan (async/sliding-buffer 1))
         done (async/thread (loop []
                              (when-let [w (async/<!! ch)]
                                (swap! state assoc :world w :frame (frame w))
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
     (add-watch world* ::viewer (fn [_ _ _ new] (async/offer! ch new)))
     (async/offer! ch @world*)
     (cli/log (str "viewer on http://127.0.0.1:" port "/"))
     {:close (fn []
               (remove-watch world* ::viewer)
               (async/close! ch)
               (async/<!! done)
               (.stop server 0)
               (.shutdown pool))})))
