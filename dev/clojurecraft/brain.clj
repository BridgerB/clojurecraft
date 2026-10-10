(ns clojurecraft.brain
  "The brain checker: the four invariants at zero. clojure -M:brain [brain-dir]

   - every [[link]] resolves to exactly one note (broken, ambiguous)
   - every non-hub note has an inbound link (orphans)
   - every sourceRef `path#anchor` resolves to exactly one place in that file
   - a verified note has sourceRefs and a pin

   Anchors match what the text says: list markers and presentation characters are dropped
   and whitespace collapsed on both sides, the file is flattened so a wrapped line still
   matches. `steve:` and `ruststeve:` prefixes resolve against the sibling checkouts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn frontmatter
  "The YAML between the opening \"---\" line and the next \"---\", or nil when the note has none."
  [text]
  (when (str/starts-with? text "---\n")
    (let [end (str/index-of text "\n---" 4)]
      (subs text 4 end))))

(defn fm-field
  "The trimmed one-line value of field k in frontmatter fm, or nil."
  [fm k]
  (some->> (re-find (re-pattern (str "(?m)^" k ":\\s*(.*)$")) fm) second str/trim))

(defn fm-list
  "The items of the \"- \" list under field k in frontmatter fm, unquoted, or nil when k
   has no list."
  [fm k]
  (let [m (re-find (re-pattern (str "(?m)^" k ":\\s*\\n((?:\\s+- .*\\n?)*)")) fm)]
    (when m (->> (str/split-lines (second m)) (map str/trim) (filter #(str/starts-with? % "- ")) (map #(subs % 2)) (map str/trim) (map #(str/replace % #"^\"|\"$" "")) vec))))

(defn strip-code
  "text without fenced blocks and inline code, so [[links]] inside code are not counted."
  [text]
  (-> text (str/replace #"(?s)```.*?```" "") (str/replace #"`[^`\n]*`" "")))

(defn plain
  "s as an anchor compares: no list marker, no *_`# characters, whitespace collapsed and trimmed.
   Applied to anchors and to source lines alike, so both sides match the same way."
  [s]
  (-> s (str/replace #"^\s*([-*+]|\d+\.)\s+" "") (str/replace #"[*_`#]" "") (str/replace #"\s+" " ") str/trim))

(defn flatten-file
  "[flat line-of]: the file's non-blank lines, each made plain, joined with single spaces into
   one string (so a wrapped sentence matches), and a list from every char index of flat to its
   1-based source line."
  [text]
  (let [lines (str/split-lines text)
        sb (StringBuilder.)
        line-of (java.util.ArrayList.)]
    (doseq [[i raw] (map-indexed vector lines)]
      (let [s (plain raw)]
        (when (seq s)
          (.append sb " ")
          (.add line-of (inc i))
          (doseq [_ s] (.add line-of (inc i)))
          (.append sb s))))
    [(str sb) line-of]))

(defn occurrences
  "Every index where needle starts in hay, overlapping matches included."
  [^String hay ^String needle]
  (loop [from 0 acc []]
    (let [i (str/index-of hay needle from)]
      (if (nil? i) acc (recur (inc i) (conj acc i))))))

(defn hub?
  "Is the note a hub (an _moc, the _index, or a top-level manual note)? Hubs need no inbound link
   and no sourceRefs."
  [rel]
  (or (str/ends-with? rel "_moc") (str/ends-with? rel "_index")
      (contains? #{"operating-manual" "conventions" "rules" "glossary" "questions"} (last (str/split rel #"/")))))

;;;; I/O: the note files, the sources, the report ;;;;

(def sibling-dirs
  {"steve" (or (System/getenv "STEVE_DIR") "/Users/bridger/Developer/mc/upstream/steve")
   "ruststeve" (or (System/getenv "RUSTSTEVE_DIR") "/Users/bridger/Developer/mc/upstream/ruststeve")})

(defn notes
  "Every .md file under root as {:file :rel :text}; :rel is the path from root without .md."
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".md")))
       (map (fn [^java.io.File f]
              (let [rel (subs (.getPath f) (inc (count (.getPath (io/file root)))))]
                {:file f :rel (str/replace rel #"\.md$" "") :text (slurp f)})))))

(defn resolve-path
  "The file a sourceRef path names: under a sibling checkout for a steve: or ruststeve: prefix,
   else relative to the repo root."
  [repo path]
  (if-let [[_ prefix rest] (re-matches #"(steve|ruststeve):(.*)" path)]
    (io/file (sibling-dirs prefix) rest)
    (io/file repo path)))

(defn sibling-missing?
  "Is path a sibling sourceRef (steve:, ruststeve:) whose checkout is not on this machine? Such a
   ref cannot be checked here (a CI runner has no sibling checkouts), which is not the same as
   being wrong; it is counted as unchecked instead of unresolved."
  [path]
  (when-let [[_ prefix] (re-matches #"(steve|ruststeve):.*" path)]
    (not (.isDirectory (io/file (sibling-dirs prefix))))))

(defn check
  "Check every note under brain-dir; returns {:notes n :broken :ambiguous :orphans :unresolved
   :meta}, each a vector of offenders, plus :unchecked, sibling refs whose checkout is absent.
   The repo root is brain-dir's parent."
  [brain-dir]
  (let [root (io/file brain-dir)
        repo (.getParentFile (.getAbsoluteFile root))
        ns* (notes root)
        by-path (into {} (map (fn [n] [(:rel n) n]) ns*))
        by-stem (group-by #(last (str/split (:rel %) #"/")) ns*)
        inbound (atom (zipmap (map :rel ns*) (repeat 0)))
        broken (atom []) ambiguous (atom []) unresolved (atom []) meta-errors (atom []) unchecked (atom [])]
    (doseq [n ns*]
      (doseq [[_ link] (re-seq #"\[\[([^\]]+)\]\]" (strip-code (:text n)))]
        (let [t (-> link (str/replace "\\|" "|") (str/split #"\|") first (str/split #"#") first str/trim)]
          (when (seq t)
            (let [resolved (or (some-> (by-path t) :rel)
                               (let [c (by-stem (last (str/split t #"/")))]
                                 (cond (= 1 (count c)) (:rel (first c))
                                       (> (count c) 1) (do (swap! ambiguous conj [(:rel n) t]) nil)
                                       :else (do (swap! broken conj [(:rel n) t]) nil))))]
              (when (and resolved (not= resolved (:rel n)))
                (swap! inbound update resolved inc))))))
      (let [fm (or (frontmatter (:text n)) "")
            status (fm-field fm "status")
            refs (fm-list fm "sourceRefs")
            pin (fm-field fm "verifiedAgainst")]
        (when (and (= status "verified") (not (hub? (:rel n))) (empty? refs))
          (swap! meta-errors conj [(:rel n) "verified without sourceRefs"]))
        (when (and (= status "verified") (not (hub? (:rel n))) (str/blank? (str pin)))
          (swap! meta-errors conj [(:rel n) "verified without verifiedAgainst"]))
        (doseq [ref refs]
          (let [[path anchor] (let [i (str/index-of ref "#")] (if i [(subs ref 0 i) (subs ref (inc i))] [ref ""]))
                f (resolve-path repo path)]
            (cond
              (sibling-missing? path) (swap! unchecked conj [(:rel n) ref])
              (not (.exists f)) (swap! unresolved conj [(:rel n) ref "no such file"])
              (str/blank? anchor) (swap! unresolved conj [(:rel n) ref "no anchor"])
              :else (let [[flat line-of] (flatten-file (slurp f))
                          hits (map #(.get ^java.util.ArrayList line-of %) (occurrences flat (plain anchor)))]
                      (cond (empty? hits) (swap! unresolved conj [(:rel n) ref "matches nothing"])
                            (> (count hits) 1) (swap! unresolved conj [(:rel n) ref (str "matches " (count hits) " at lines " (vec hits))]))))))))
    (let [orphans (vec (for [[rel c] @inbound :when (and (zero? c) (not (hub? rel)))] rel))]
      {:notes (count ns*) :broken @broken :ambiguous @ambiguous :orphans orphans
       :unresolved @unresolved :meta @meta-errors :unchecked @unchecked})))

(defn -main
  "Print the count and offenders for each invariant; exit 0 only when all are empty."
  [& [dir]]
  (let [r (check (or dir "brain"))]
    (println "notes:" (:notes r))
    (doseq [k [:broken :ambiguous :orphans :unresolved :meta]]
      (println (name k) (count (k r)))
      (doseq [x (k r)] (println "  " (pr-str x))))
    (println "unchecked sibling refs (no checkout here)" (count (:unchecked r)))
    (System/exit (if (every? empty? (map r [:broken :ambiguous :orphans :unresolved :meta])) 0 1))))
