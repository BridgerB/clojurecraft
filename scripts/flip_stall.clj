;; Fold of a recording through the planner, for a probe (never run on the Mac). Counts :tunnel-to
;; and :pocket from 200 s to 360 s after :go, and prints why the first few tunnels are dropped.
;; z16 job 5 (plan 41276280629) alternates the two from about 233 s and never chooses a move.
;;   clojure -J-Xmx12g -M scripts/flip_stall.clj run.edn.zst [old|fix]
;; Run the old half from a checkout of the commit that made the recording. The fix half is that
;; checkout with this branch's plan.clj copied over it. The old half still flips every tick. The
;; fix half's tunnel does take a move (the hollow-pocket guard), and the replay then still starts
;; a :pocket and a :seal whose site steer has already dropped, which NPEs in pocket/plus (probes
;; 41303425892 and 41304181213). After 233 s the fix half puts that tunnel back, so the window is
;; one run. A dig the recording never made is ignored the same way. The old half does neither.
(require '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.main :as main]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.record :as record])

(def window-from 200000)
(def window-to 360000)
(def stall-from 233000) ; the 5:21 flip: :pocket and :tunnel-to every tick, move still nil
(def cuts-to-print 4)
(def swallows-to-print 4)
(def real-fail intent/fail)

(defn cut-line
  "Why before's running tunnel is no longer the intent of after. One map, or {:cut-threw m}
   when a predicate the recording's code does not have is asked."
  [before after t]
  (try
    (let [feet (intent/feet before)
          site (:pocket/site before)
          goal (plan/choose before plan/goals)
          i (:plan/intent before)
          child (:intent/child i)
          blaze (fn [sym] (requiring-resolve (symbol "clojurecraft.blaze" (name sym))))
          spawner ((blaze 'nearest-spawner) before)
          nxt (when goal (plan/next-intent before goal plan/goals))
          flag (fn [sym] (boolean ((blaze sym) before)))]
      {:t t
       :feet feet
       :hp (:player/health before)
       :fire (boolean (:player/on-fire? before))
       :site (:pocket/cell site)
       :here? (boolean (:pocket/here? site))
       :in? (boolean ((requiring-resolve 'clojurecraft.pocket/in?) before))
       :unlayable (boolean ((requiring-resolve 'clojurecraft.pocket/unlayable?) before feet))
       :interrupts? (boolean (and goal i (plan/interrupts? before goal i)))
       :next (select-keys nxt [:intent/kind :intent/target :intent/for])
       :next-cell (:pocket/cell (:intent/site nxt))
       :status (:intent/status i)
       :reason (:intent/reason i)
       :child (select-keys child [:intent/kind :intent/reason :intent/stage :intent/target])
       :refuge (flag 'refuge-needed?)
       :reignited (flag 'reignited?)
       :outnumbered (flag 'outnumbered?)
       :watchers (count ((blaze 'watchers) before))
       :passive (flag 'passive?)
       :prepare (boolean (some? ((blaze 'prepare-intent) before spawner)))
       :sortie (flag 'sortie-over?)
       :from-id (:intent/id i)
       :from-move (:intent/move i)
       :to (select-keys (:plan/intent after) [:intent/id :intent/kind :intent/target])
       :to-cell (:pocket/cell (:intent/site (:plan/intent after)))
       :site-after (:pocket/cell (:pocket/site after))})
    (catch Exception e {:cut-threw (ex-message e) :cut-ex (class e) :t t})))

(let [[path half] *command-line-args*
      _ (when-not path (throw (ex-info "path required" {})))
      _ (println "half" (or half "none"))
      world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
      go-at (volatile! nil)
      minute (volatile! -1)
      cuts (volatile! 0)
      tunnel-ids (volatile! #{})
      moved (volatile! #{})
      pockets (volatile! 0)
      run-id (volatile! nil)
      run-n (volatile! 0)
      max-run (volatile! 0)
      stall-id (volatile! nil)
      stall-n (volatile! 0)
      stall-max (volatile! 0)
      stall-flips (volatile! 0)
      stall-moved (volatile! false)
      swallowed (volatile! 0)
      threw (volatile! false)
      note-run
      (fn [id]
        (if (= id @run-id)
          (vswap! run-n inc)
          (do (vreset! max-run (max @max-run @run-n)) (vreset! run-id id) (vreset! run-n 1))))
      break-run
      (fn [] (vreset! max-run (max @max-run @run-n)) (vreset! run-id nil) (vreset! run-n 0))
      note-stall (fn [id move]
                   (when move (vreset! stall-moved true))
                   (if (= id @stall-id)
                     (vswap! stall-n inc)
                     (do (vreset! stall-max (max @stall-max @stall-n))
                         (vreset! stall-id id)
                         (vreset! stall-n 1))))
      break-stall (fn []
                    (when @stall-id (vswap! stall-flips inc))
                    (vreset! stall-max (max @stall-max @stall-n))
                    (vreset! stall-id nil)
                    (vreset! stall-n 0))
      finish-stall (fn [] (vreset! stall-max (max @stall-max @stall-n)))
      ;; a step the recording never dug: its ack is already in, the block still stands, and
      ;; failing the tunnel starts the pocket whose nil cell NPEs. The planner's cut does not
      ;; come through here (abandon-intent drops the intent itself).
      _ (alter-var-root
         #'intent/fail
         (fn [_]
           (fn [world reason]
             (let [i (:plan/intent world)
                   t (when @go-at (- (:time/now world 0) @go-at))]
               (if (and t (>= t stall-from) (= :tunnel-to (:intent/kind i)) (:intent/move i))
                 (do (when (< @swallowed swallows-to-print)
                       (prn {:replay-keeps-tunnel true
                             :reason reason
                             :t t
                             :id (:intent/id i)
                             :child (select-keys (:intent/child i)
                                                 [:intent/kind :intent/reason :intent/stage
                                                  :intent/target])}))
                     (vswap! swallowed inc)
                     (-> world
                         (intent/dissoc-intent :intent/child :intent/reason)
                         (intent/assoc-intent :intent/status :active)))
                 (real-fail world reason))))))
      ;; the fix half only. A :pocket, :seal or :pocket-hold the replay started in place of a
      ;; tunnel is undone: the recording never contains it, and the :seal calls pocket/plus on
      ;; the nil cell steer just dropped. The old half is left flipping, which is the bug.
      keep-tunnel (fn [w before]
                    (let [site (:pocket/site before)]
                      (cond-> (assoc w
                                     :plan/intent (:plan/intent before)
                                     :plan/intents (:plan/intents before)
                                     :player/controls (:player/controls before))
                        (some? site) (assoc :pocket/site site)
                        (nil? site) (dissoc :pocket/site))))
      stacked (volatile! false)
      note-keep (fn [t replaced to-id]
                  (when (< @swallowed swallows-to-print)
                    (prn {:replay-keeps-tunnel true :t t :replaced replaced :to-id to-id}))
                  (vswap! swallowed inc))
      final
      (record/reduce-entries
       (fn [w e]
         (if-not (:event/kind e)
           w
           (let [before w
                 outcome
                 (try (assoc (main/step w e) :bot/effects [])
                      (catch Exception ex
                        (let [t (when @go-at (- (:time/now before 0) @go-at))]
                          (if (and (= half "fix")
                                   t
                                   (>= t stall-from)
                                   (= :tunnel-to (:intent/kind (:plan/intent before))))
                            (do (note-keep t :threw nil)
                                (when-not @stacked
                                  (vreset! stacked true)
                                  (println "replay keeps tunnel through" (.getMessage ex))
                                  (.printStackTrace ex (java.io.PrintWriter. *out* true)))
                                (assoc before :time/now (or (:event/now e) (:time/now before))))
                            (do (println "step threw" (.getMessage ex))
                                (.printStackTrace ex (java.io.PrintWriter. *out* true))
                                (vreset! threw true)
                                ::threw)))))]
             (if (= outcome ::threw)
               (reduced w)
               (let [stepped outcome
                     now (:time/now stepped 0)]
                 (when (and (nil? @go-at) (= :go (:event/kind e))) (vreset! go-at now))
                 (if-not @go-at
                   stepped
                   (let [t (- now @go-at)
                         m (quot t 60000)]
                     (when (> m @minute) (vreset! minute m) (println "replay" m "min"))
                     (cond (< t window-from) stepped
                           (> t window-to) (reduced stepped)
                           :else
                           (let [prev (:plan/intent before)
                                 stepped-kind (:intent/kind (:plan/intent stepped))
                                 keep? (and (= half "fix")
                                            (>= t stall-from)
                                            (= :tunnel-to (:intent/kind prev))
                                            (contains? #{:pocket :seal :pocket-hold} stepped-kind))
                                 _ (when keep?
                                     (note-keep t stepped-kind (:intent/id (:plan/intent stepped))))
                                 _ (when (and (= :tunnel-to (:intent/kind prev))
                                              (not= :tunnel-to stepped-kind)
                                              (< @cuts cuts-to-print))
                                     (vswap! cuts inc)
                                     (prn (cut-line before stepped t)))
                                 w (if keep? (keep-tunnel stepped before) stepped)
                                 i (:plan/intent w)
                                 kind (:intent/kind i)
                                 id (:intent/id i)]
                             (if (= :tunnel-to kind)
                               (do (when-not (@tunnel-ids id) (vswap! tunnel-ids conj id))
                                   (when (:intent/move i) (vswap! moved conj id))
                                   (note-run id)
                                   (when (>= t stall-from) (note-stall id (:intent/move i))))
                               (do (when (= :pocket kind) (vswap! pockets inc))
                                   (break-run)
                                   (when (>= t stall-from) (break-stall))))
                             w)))))))))
       world0
       path)
      _ (break-run)
      _ (finish-stall)
      stall {:stall-flips @stall-flips
             :stall-max-run @stall-max
             :stall-moved @stall-moved
             :replay-ignores @swallowed
             :threw @threw}
      ok (and (not @threw) @stall-moved (>= @stall-max 200) (<= @stall-flips 5))]
  (prn (merge {:summary true
               :half (or half "none")
               :tunnel-ids (count @tunnel-ids)
               :with-move (count @moved)
               :pocket-ticks @pockets
               :max-run @max-run
               :feet (try (intent/feet final) (catch Exception _ nil))
               :pos (:player/pos final)
               :last (select-keys (:plan/intent final)
                                  [:intent/id :intent/kind :intent/target :intent/move])
               :hp (:player/health final)
               :fire (boolean (:player/on-fire? final))}
              stall))
  (println "done")
  (shutdown-agents)
  (when (= half "fix") (if ok (println "FIX-OK") (do (println "FIX-BAD") (System/exit 1)))))
