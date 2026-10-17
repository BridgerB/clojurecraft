(ns clojurecraft.brief
  "A Copilot session's brief, written before the session starts (copilot-setup-steps.yml), so the
   model spends no turns on what a script can find: each turn re-sends everything read so far, and
   a session's cost is its input (2026-10-17: a first session spent 41.6 AI credits in five
   minutes fetching the issue twice, searching issues and pull requests, judging on main and
   polling the judge, reading 800 lines of build.clj to find the failing op).

     bb brief write     cache/issues/<n>/brief.md for each open bot-failure issue Copilot holds

   Copilot holds one issue at a time (copilot/default-cap), so that is one brief. It holds, in the
   order the profile reads it (.github/agents/bot-fixer.agent.md):
     - the snapshots in cache/issues/<n>/ (bb issue-check fetch put them there);
     - their JUDGE and STORY lines on origin/main (a worktree of it), the judgement the profile asks
       for by minute 10, and on the session's own branch when it differs from main (a follow-up
       session: is the fix already passing?);
     - where the failure's reason is raised (src lines that name the keyword), and the brain notes
       that cite those files;
     - the stage's row in goals.edn and problems.edn;
     - the issue's body and its newest comments, clipped;
     - the closed issues with the same reason and the pull requests that closed them (AGENTS.md
       section 9.3: a regression's closing PR is the first read).
   Everything is clipped: the brief exists to be cheaper than the reads it replaces."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojurecraft.copilot :as copilot]))

(def body-chars 3000)       ; the issue body, clipped
(def comment-chars 1500)    ; each comment, clipped
(def comments-kept 3)       ; the newest comments
(def story-kept 40)         ; a snapshot's STORY lines, the last ones (the failure is at the end)
(def raised-kept 12)        ; src lines that name the reason
(def notes-kept 5)          ; brain notes that cite a file the reason is raised in
(def related-kept 6)        ; closed issues with the same reason
(def forms-kept 3)          ; the functions round the raising lines shown whole
(def form-lines 70)         ; a function longer than this is shown as this many lines round the hit
(def log-lines 60)          ; a red check's log: its last lines

;; ---------------------------------------------------------------- the brief as values

(defn clip
  "s cut to n characters, with a note of how much was cut; s itself when it fits."
  [s n]
  (let [s (str s)]
    (cond (<= (count s) n) s
          :else (str (subs s 0 n) "\n… (" (- (count s) n) " more characters cut)"))))

(defn signature
  "The stage and the reason a bot-failure title names (\"Bot stops at fortress: no-stand\"), as
   {:stage \"fortress\" :reason \"no-stand\"}; nil for a title of another shape."
  [title]
  (when-let [[_ stage reason] (re-find #"(?i)stops at ([\w-]+): ([\w-]+)" (str title))]
    {:stage stage :reason reason}))

(defn judged-lines
  "The JUDGE lines, each snapshot's last story-kept STORY lines, and its SCENE and SLICE lines
   (the bot's facts and the blocks round its feet at the moment and where the play ended,
   simrace/judge!), from a judge's output, in the order printed."
  [out]
  (let [lines (str/split-lines (str out))
        judges (filter #(str/starts-with? % "JUDGE ") lines)
        stories (->> lines
                     (filter #(str/starts-with? % "STORY "))
                     (group-by #(second (str/split % #" " 3))))
        scenes (filter #(or (str/starts-with? % "SCENE ") (str/starts-with? % "SLICE ")) lines)]
    (concat judges (mapcat (fn [[_ ls]] (take-last story-kept ls)) stories) scenes)))

(defn top-level?
  "Does a source line begin a top-level form: an open paren in its first column."
  [line]
  (str/starts-with? (str line) "("))

(defn enclosing
  "The 1-based [first last] lines of the top-level form round line n of a file's lines: from the
   last top-level line at or before n to the line before the next one (or the end), cut to
   form-lines centred on n when longer."
  [lines n]
  (let [start (or (last (filter #(top-level? (nth lines (dec %))) (range 1 (inc n)))) 1)
        nxt (first (filter #(top-level? (nth lines (dec %))) (range (inc n) (inc (count lines)))))
        end (cond nxt (dec nxt)
                  :else (count lines))]
    (cond (<= (inc (- end start)) form-lines) [start end]
          :else
          (let [lo (max start (- n (quot form-lines 2)))] [lo (min end (+ lo (dec form-lines)))]))))

(defn in-code?
  "Is a grep hit's keyword in code rather than in a docstring or comment: no quote and no semicolon
   before it on its line."
  [{:keys [text]} reason]
  (let [i (str/index-of (str text) (str ":" reason))]
    (and i (not (re-find #"[\";]" (subs text 0 i))))))

(defn excerpts
  "The top-level forms round the hits, the ones raising the reason in code first, each once, at
   most forms-kept: [{:file :from :to :lines [text ...]}], given read (a file's lines)."
  [hits reason read]
  (->> (concat (filter #(in-code? % reason) hits) (remove #(in-code? % reason) hits))
       (map (fn [{:keys [file line]}]
              (let [lines (read file)
                    [from to] (enclosing lines line)]
                {:file file :from from :to to :lines (subvec lines (dec from) to)})))
       (reduce (fn [acc e]
                 (cond (some #(= (select-keys % [:file :from]) (select-keys e [:file :from])) acc)
                       acc
                       :else (conj acc e)))
               [])
       (take forms-kept)))

(defn numbered
  "An excerpt's lines, each prefixed with its line number."
  [{:keys [from lines]}]
  (map-indexed (fn [i l] (str (+ from i) "  " l)) lines))

(defn grep-hits
  "grep -n output (path:line:text) as [{:file :line :text}], the text trimmed and clipped."
  [out]
  (for [l (str/split-lines (str out))
        :let [[_ file line text] (re-find #"^([^:]+):(\d+):(.*)$" l)]
        :when file]
    {:file file :line (parse-long line) :text (clip (str/trim text) 160)}))

(defn closing-prs
  "A GraphQL answer for closed issues (aliases i<n>) as {n [{:number :title}]}: the merged pull
   requests that closed each."
  [answer]
  (into {}
        (for [[k v] (get-in answer [:data :repository])]
          [(parse-long (subs (name k) 1))
           (vec (for [pr (get-in v [:closedByPullRequestsReferences :nodes])]
                  (select-keys pr [:number :title])))])))

(defn section
  "A markdown section: a heading and its lines, or the empty text when there are none."
  [heading lines]
  (cond (empty? lines) ""
        :else (str "## " heading "\n\n" (str/join "\n" lines) "\n\n")))

(defn fenced "Lines inside a code fence." [lines] (concat ["```"] lines ["```"]))

(defn brief
  "The brief's markdown for issue i ({:number :title :body :comments}), given what was found: the
   snapshot files, the judge outputs ({:main {:sha :out} :branch {:sha :out}}, :branch only when
   the session's branch differs from main), the red checks of the session's pull request
   ([{:check :url :log}], a follow-up's), where the reason is raised (grep-hits), the functions
   round it (excerpts), the brain
   notes citing those files, the stage's goal and problem rows (grep-hits), and the closed issues
   with the same reason ([{:number :title :prs}])."
  [{:keys [number title body comments]}
   {:keys [snapshots judged raised forms red notes rows related]}]
  (str
   "# Brief for #"
   number
   ": "
   title
   "\n\n"
   "Written by copilot-setup-steps before your session (bb brief write). It replaces fetching the "
   "issue, searching issues and pull requests, judging the snapshots on main and finding where "
   "the reason is raised: do none of those again. Read the files it names, at the lines it names.\n\n"
   (section "Snapshots"
            (cond
              (empty? snapshots)
              [(str "None in cache/issues/"
                    number
                    "/: stop at once as the profile's budget says (no evidence on disk).")]
              :else
              (concat
               (for [s snapshots] (str "- `" s "`"))
               ["" (str "Copy these into `test/issues/" number "/` (the check judges them there).")]
               (when (and (get-in judged [:main :out])
                          (not (re-find #":recurs\? true" (get-in judged [:main :out]))))
                 [""
                  (str "**No snapshot recurs on main: the failure is fixed already. Write `Refs #"
                       number
                       "`, say so, and stop.**")]))))
   (section (str "Judged on main (" (get-in judged [:main :sha]) "), where each must recur")
            (some-> (get-in judged [:main :out])
                    judged-lines
                    seq
                    fenced))
   (section (str "Judged on this branch (" (get-in judged [:branch :sha]) "), where none may recur")
            (some-> (get-in judged [:branch :out])
                    judged-lines
                    seq
                    fenced))
   (section "Your pull request's red checks (fix these first)"
            (for [{:keys [check url log]} red]
              (str "### " check " (" url ")\n\n```\n" log "\n```\n")))
   (section "Where the reason is raised"
            (for [{:keys [file line text]} raised] (str "- `" file ":" line "` " text)))
   (section "The code round it (whole functions, so you need not open these again)"
            (for [{:keys [file from to] :as e} forms]
              (str "### `"
                   file
                   ":"
                   from
                   "-"
                   to
                   "`\n\n```clojure\n"
                   (str/join "\n" (numbered e))
                   "\n```\n")))
   (section "Brain notes citing those files" (for [n notes] (str "- `" n "`")))
   (section "The stage's rows"
            (for [{:keys [file line text]} rows] (str "- `" file ":" line "` " text)))
   (section "Closed issues with the same reason, and what closed them"
            (for [{n :number t :title prs :prs} related]
              (str "- #"
                   n
                   " "
                   t
                   (cond (empty? prs) ""
                         :else
                         (str " (closed by "
                              (str/join ", "
                                        (for [pr prs] (str "PR #" (:number pr) " " (:title pr))))
                              ")")))))
   (section "The issue" [(clip body body-chars)])
   (section (str "Its newest comments ("
                 (min comments-kept (count comments))
                 " of "
                 (count comments)
                 ", newest first)")
            (for [c (take comments-kept (reverse comments))]
              (str "### " (:createdAt c) "\n\n" (clip (:body c) comment-chars) "\n")))))

(defn latest-red
  "The required checks whose newest run failed, from a pull request's status rollup:
   [{:name :detailsUrl ...}], an older failure a newer run superseded left out."
  [rollup]
  (for [[_ runs] (group-by :name (filter #(copilot/required-checks (:name %)) rollup))
        :let [c (last (sort-by #(str (or (:completedAt %) (:startedAt %))) runs))]
        :when (= "FAILURE" (:conclusion c))]
    c))

(defn log-tail
  "A job log's last log-lines lines, each without the job and step columns gh prints."
  [log]
  (->> (str/split-lines (str log))
       (map #(str/replace % #"^[^\t]*\t[^\t]*\t\S+ " ""))
       (take-last log-lines)
       (str/join "\n")))

;;;; I/O: gh, git, grep, the judge, the files ;;;;

(defn sh
  "A command's stdout (its stderr too when err? is true), never throwing: empty when it fails."
  [opts & args]
  (let [r
        (apply p/shell (merge {:out :string :err :string :continue true} (dissoc opts :err?)) args)]
    (str (:out r) (when (:err? opts) (:err r)))))

(defn held
  "The open bot-failure issues Copilot holds, with their body and comments."
  []
  (->> (copilot/gh-json "issue" "list"
                        "--repo" copilot/repo
                        "--label" "bot-failure"
                        "--state" "open"
                        "--limit" "200"
                        "--json" "number,title,body,comments,assignees,labels")
       (filter (fn [i]
                 (and (some #(str/starts-with? (str/lower-case (:login %)) "copilot")
                            (:assignees i))
                      (not-any? (comp copilot/hands-off :name) (:labels i)))))))

(defn related
  "The closed bot-failure issues whose title names reason, newest first, each with the merged pull
   requests that closed it."
  [reason]
  (let [issues (take related-kept
                     (copilot/gh-json "issue" "list"
                                      "--repo" copilot/repo
                                      "--label" "bot-failure"
                                      "--state" "closed"
                                      "--search" (str "\"" reason "\" in:title")
                                      "--limit" (str related-kept)
                                      "--json" "number,title"))
        [owner name] (str/split copilot/repo #"/")
        query (str "query { repository(owner: \""
                   owner
                   "\", name: \""
                   name
                   "\") { "
                   (str/join
                    " "
                    (for [{n :number} issues]
                      (str "i" n
                           ": issue(number: " n
                           ") { closedByPullRequestsReferences"
                           "(first: 3, includeClosedPrs: false) { nodes { number title } } }")))
                   " } }")
        prs (cond (empty? issues) {}
                  :else
                  (closing-prs (json/parse-string
                                (sh {} "gh" "api" "graphql" "-f" (str "query=" query))
                                true)))]
    (for [i issues] (assoc i :prs (get prs (:number i) [])))))

(defn bot-changed?
  "Does the checkout's bot (src/ and resources/) differ from origin/main's? Only then is a judgement
   on the branch worth its minutes: a tooling or test change plays the snapshots exactly as main."
  []
  (not (zero? (:exit (p/shell {:continue true :out :string :err :string}
                              "git" "diff"
                              "--quiet" "origin/main"
                              "HEAD" "--"
                              "src" "resources")))))

(defn relative
  "out with every absolute path into the checkout cut to a path relative to it: the brief's lines
   stay short."
  [out]
  (str/replace (str out) (str (fs/cwd) "/") ""))

(defn judge
  "The judge's output (stdout and stderr) for the snapshot paths, played by the code in dir."
  [dir paths]
  (relative (sh {:dir dir :err? true} "clojure" "-M:simrace" "--judge" (str/join "," paths))))

(defn judged
  "The snapshots judged on origin/main (a worktree of it beside the checkout, sharing its cache)
   and, when the checkout's bot differs from main's (bot-changed?), on the checkout."
  [paths]
  (let [base (str (fs/absolutize "../brief-base"))
        here (str (fs/cwd))]
    (sh {} "git" "fetch" "-q" "origin" "main")
    (when-not (fs/exists? base)
      (sh {} "git" "worktree" "add" "-q" "--detach" base "origin/main")
      (fs/create-sym-link (fs/path base "cache") (fs/path here "cache")))
    (cond-> {:main {:sha (str/trim (sh {} "git" "rev-parse" "--short" "origin/main"))
                    :out (judge base paths)}}
      (bot-changed?)
      (assoc :branch
             {:sha (str/trim (sh {} "git" "rev-parse" "--short" "HEAD"))
              :out (judge here paths)}))))

(defn red-checks
  "The required checks that failed on the open pull request from the session's branch, each with
   its log's tail: [{:check :url :log}]; empty on a first session's branch (no pull request yet)."
  []
  (let [branch (or (System/getenv "GITHUB_REF_NAME")
                   (str/trim (sh {} "git" "rev-parse" "--abbrev-ref" "HEAD")))
        prs (json/parse-string (sh {}
                                   "gh"
                                   "pr" "list"
                                   "--repo" copilot/repo
                                   "--head" branch
                                   "--state" "open"
                                   "--json" "number,statusCheckRollup")
                               true)]
    (for [pr prs
          c (latest-red (:statusCheckRollup pr))
          :let [[_ run job] (re-find #"/runs/(\d+)/job/(\d+)" (str (:detailsUrl c)))]
          :when job]
      {:check (:name c)
       :url (:detailsUrl c)
       :log (log-tail
             (sh {} "gh" "run" "view" run "--repo" copilot/repo "--job" job "--log-failed"))})))

(defn found
  "What the brief says about issue i: its snapshots, their judgements, where its reason is raised,
   the notes citing those files, the stage's rows and the closed issues with the same reason."
  [{:keys [number title]}]
  (let [{:keys [stage reason]} (signature title)
        snapshots (sort (map str (fs/glob (str "cache/issues/" number) "*.edn.gz")))
        paths (map #(str (fs/absolutize %)) snapshots)
        raised (when reason
                 (take raised-kept (grep-hits (sh {} "grep" "-rn" (str ":" reason "\\b") "src"))))
        files (distinct (map :file raised))
        read (memoize (fn [file] (vec (str/split-lines (slurp file)))))]
    {:snapshots snapshots
     :red (vec (red-checks))
     :forms (when reason (excerpts raised reason read))
     :judged (when (seq paths) (judged paths))
     :raised raised
     :notes (take notes-kept
                  (distinct (mapcat #(str/split-lines (sh {} "grep" "-rl" % "brain")) files)))
     :rows (when stage
             (grep-hits (sh {}
                            "grep" "-n"
                            "-e" (str ":goal/id :" stage "\\b")
                            "-e" (str ":problem/goals \\[.*:" stage "\\b")
                            "resources/clojurecraft/goals.edn"
                            "resources/clojurecraft/problems.edn")))
     :related (when reason (related reason))}))

(defn write!
  "Write cache/issues/<n>/brief.md for each issue Copilot holds, printing each."
  []
  (let [issues (held)]
    (when (empty? issues) (println "no issue held by Copilot: no brief"))
    (doseq [i issues]
      (let [text (brief i (found i))
            file (fs/path "cache/issues" (str (:number i)) "brief.md")]
        (fs/create-dirs (fs/parent file))
        (spit (str file) text)
        (println (str "== " file))
        (println text)))))

(defn -main
  "bb brief write."
  [& [command]]
  (cond (= "write" command) (write!)
        :else (println "usage: bb brief write")))
