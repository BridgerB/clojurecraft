(ns clojurecraft.board
  "The bot-failure board (AGENTS.md section 3) as one command, so no agent hand-copies jq.

     bb board todo                 the Todo bot-failure issues, oldest first
     bb board heal                 make the board true, and return branchless claims
     bb board move <n> <status>    set issue n's item to todo, progress or done

   heal moves an item only by these rules, each printed as it is applied:
     1. an open bot-failure issue not on the board is added, as Todo;
     2. an open issue whose item is Done or has no status goes back to Todo (reopened);
     3. a closed issue whose item is Todo or In Progress goes to Done;
     4. an open issue In Progress for more than six hours with no fix/<n> or fix/<n>-rK branch on
        origin goes back to Todo: a claim is the branch, and an item with none is an
        agent that stopped before claiming or after releasing (AGENTS.md section 4, step 4).
   A claim that has a branch is never touched here: returning a stale one is by hand (section 3).

   The ids are looked up each run, never written down."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def owner "BridgerB")
(def number 10)
(def stale-ms
  "How long an In Progress item may sit with no branch (section 3's six hours)."
  (* 6 3600 1000))

(def statuses
  "The command-line word for each Status option name."
  {"todo" "Todo" "progress" "In Progress" "done" "Done"})

;; ---------------------------------------------------------------- the board as values

(defn claim-branch?
  "True when branch (a ref name without refs/heads/) is issue n's claim: fix/<n> or fix/<n>-r<K>."
  [n branch]
  (boolean (re-matches (re-pattern (str "fix/" n "(-r\\d+)?")) branch)))

(defn heal-moves
  "The moves that make the board true, [{:number n :to status :why s :item id-or-nil :url u}],
   from items ({:id :number :state :status :updated-ms :url}), the open bot-failure issues
   ({:number :url}), the claim branches on origin (names) and now (ms). Pure: rules 1 to 4 of
   the namespace doc."
  [items open-issues branches now]
  (let [on-board (set (map :number items))
        missing (for [{n :number u :url} open-issues
                      :when (not (on-board n))]
                  {:number n :to "Todo" :why "not on the board" :url u})
        moved (for [{:keys [id state status updated-ms] n :number} items
                    :let [why (cond (and (= state "OPEN") (contains? #{"Done" nil} status))
                                    "open, but Done or unsorted"
                                    (and (= state "CLOSED")
                                         (contains? #{"Todo" "In Progress"} status))
                                    "closed"
                                    (and (= state "OPEN")
                                         (= status "In Progress")
                                         (some? updated-ms)
                                         (> (- now updated-ms) stale-ms)
                                         (not-any? #(claim-branch? n %) branches))
                                    "In Progress over six hours with no claim branch")]
                    :when why]
                {:number n :item id :why why :to (if (= why "closed") "Done" "Todo")})]
    (vec (sort-by :number (concat missing moved)))))

(defn todo
  "The Todo items whose issue is open and labelled bot-failure, oldest (lowest number) first."
  [items]
  (->> items
       (filter
        #(and (= "Todo" (:status %)) (= "OPEN" (:state %)) (some #{"bot-failure"} (:labels %))))
       (sort-by :number)))

;;;; I/O: gh and git ;;;;

(defn gh
  "gh with args, its stdout; throws when it fails, printing gh's error."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "gh " (str/join " " args) ": " (str/trim (:err r))) {})))
    (:out r)))

(defn gh-json
  "gh with args, its stdout parsed as JSON with keyword keys."
  [& args]
  (json/parse-string (apply gh args) true))

(defn ids
  "{:project id :field id :options {name id}}: the board's ids, looked up now."
  []
  (let [field (->> (gh-json "project" "field-list" (str number) "--owner" owner "--format" "json")
                   :fields
                   (filter #(= "Status" (:name %)))
                   first)]
    {:project (:id (gh-json "project" "view" (str number) "--owner" owner "--format" "json"))
     :field (:id field)
     :options (into {} (map (juxt :name :id)) (:options field))}))

(def items-query
  "One page of the board's items: each issue's number, state, labels and Status with its time."
  "query($after: String) { user(login: \"BridgerB\") { projectV2(number: 10) {
     items(first: 100, after: $after) { pageInfo { hasNextPage endCursor } nodes {
       id
       content { ... on Issue { number state url labels(first: 20) { nodes { name } } } }
       fieldValueByName(name: \"Status\") {
         ... on ProjectV2ItemFieldSingleSelectValue { name updatedAt } } } } } } }")

(defn page
  "One page of items after cursor (nil for the first), {:items [...] :next cursor-or-nil}."
  [cursor]
  (let [args (cond-> ["api" "graphql" "-f" (str "query=" items-query)]
               cursor (into ["-f" (str "after=" cursor)]))
        items (get-in (apply gh-json args) [:data :user :projectV2 :items])]
    {:items (for [{:keys [id content fieldValueByName]} (:nodes items)
                  :when (:number content)]
              {:id id
               :number (:number content)
               :state (:state content)
               :url (:url content)
               :labels (map :name (get-in content [:labels :nodes]))
               :status (:name fieldValueByName)
               :updated-ms (some-> (:updatedAt fieldValueByName)
                                   java.time.Instant/parse
                                   .toEpochMilli)})
     :next (when (get-in items [:pageInfo :hasNextPage]) (get-in items [:pageInfo :endCursor]))}))

(defn items
  "Every issue item on the board."
  []
  (reduce (fn [acc _]
            (let [{xs :items nxt :next} (page (:next acc))
                  acc (-> acc
                          (update :items into xs)
                          (assoc :next nxt))]
              (if nxt acc (reduced acc))))
          {:items [] :next nil}
          (range 100)))

(defn open-issues
  "The open bot-failure issues, [{:number :url}]."
  []
  (gh-json "issue" "list"
           "--label" "bot-failure"
           "--state" "open"
           "--limit" "500"
           "--json" "number,url"))

(defn branches
  "The fix/* branch names on origin."
  []
  (->> (:out (p/shell {:out :string} "git" "ls-remote" "--heads" "origin" "fix/*"))
       str/split-lines
       (keep #(second (re-find #"refs/heads/(.+)$" %)))))

(defn set-status!
  "Set an item's Status to the option named."
  [{:keys [project field options]} item status]
  (gh "project" "item-edit"
      "--id" item
      "--project-id" project
      "--field-id" field
      "--single-select-option-id" (get options status)))

(defn apply-move!
  "Apply one heal move, adding the issue to the board first when it is not on it; prints it."
  [ids {:keys [item url to why] n :number}]
  (let [item (or item
                 (:id (gh-json "project"
                               "item-add" (str number)
                               "--owner" owner
                               "--url" url
                               "--format" "json")))]
    (set-status! ids item to)
    (println (str "#" n " -> " to " (" why ")"))))

(defn heal!
  "Make the board true by rules 1 to 4; prints each move, or that there was none."
  []
  (let [moves (heal-moves (:items (items)) (open-issues) (branches) (System/currentTimeMillis))]
    (cond (empty? moves) (println "the board is true")
          :else (let [ids (ids)] (run! #(apply-move! ids %) moves)))))

(defn print-todo!
  "Print the Todo bot-failure issues, oldest first: number, labels, item id."
  []
  (doseq [{n :number :keys [labels id]} (todo (:items (items)))]
    (println (str "#" n) (str/join "," labels) id)))

(defn move!
  "Set issue n's item to the status word (todo, progress, done) and print what it reads now."
  [n word]
  (let [status (statuses word)
        item (first (filter #(= n (:number %)) (:items (items))))]
    (cond (nil? status) (println "status is one of" (str/join ", " (keys statuses)))
          (nil? item) (println (str "#" n " is not on the board: run bb board heal"))
          :else
          (do (set-status! (ids) (:id item) status)
              (println (str "#" n
                            " -> "
                            (:status (first (filter #(= n (:number %)) (:items (items)))))))))))

(defn -main
  "todo | heal | move <n> <todo|progress|done>."
  [& [cmd a b]]
  (case cmd
    "todo" (print-todo!)
    "heal" (heal!)
    "move" (move! (parse-long a) b)
    (println "bb board todo | heal | move <n> <todo|progress|done>")))
