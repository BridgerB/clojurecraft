;; Fold of a recording through the planner, for a probe (never run on the Mac). Counts :tunnel-to
;; and :pocket from 200 s to 360 s after :go, and prints why the first few tunnels are dropped.
;; z16 job 5 (plan 41276280629) alternates the two from about 233 s and never chooses a move.
;;   clojure -J-Xmx12g -M scripts/flip_stall.clj run.edn.zst
;; Run the old half from a checkout of the commit that made the recording. The fix half is that
;; checkout with this branch's plan.clj copied over it.
(require '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.main :as main]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.record :as record])

(def window-from 200000)
(def window-to 360000)
(def cuts-to-print 4)

(defn cut-line
  "Why before's running tunnel is no longer the intent of after. One map, or {:cut-threw m}
   when a predicate the recording's code does not have is asked."
  [before after]
  (try (let [feet (intent/feet before)
             site (:pocket/site before)
             goal (plan/choose before plan/goals)
             i (:plan/intent before)
             blaze (fn [sym] (requiring-resolve (symbol "clojurecraft.blaze" (name sym))))
             spawner ((blaze 'nearest-spawner) before)
             nxt (when goal (plan/next-intent before goal plan/goals))
             flag (fn [sym] (boolean ((blaze sym) before)))]
         {:feet feet
          :hp (:player/health before)
          :fire (boolean (:player/on-fire? before))
          :site (:pocket/cell site)
          :here? (boolean (:pocket/here? site))
          :in? (boolean ((requiring-resolve 'clojurecraft.pocket/in?) before))
          :unlayable (boolean ((requiring-resolve 'clojurecraft.pocket/unlayable?) before feet))
          :interrupts? (boolean (and goal i (plan/interrupts? before goal i)))
          :next (select-keys nxt [:intent/kind :intent/target :intent/for])
          :refuge (flag 'refuge-needed?)
          :reignited (flag 'reignited?)
          :outnumbered (flag 'outnumbered?)
          :watchers (count ((blaze 'watchers) before))
          :passive (flag 'passive?)
          :prepare (boolean (some? ((blaze 'prepare-intent) before spawner)))
          :sortie (flag 'sortie-over?)
          :from-id (:intent/id i)
          :from-move (:intent/move i)
          :to (select-keys (:plan/intent after) [:intent/id :intent/kind :intent/target])})
       (catch Exception e {:cut-threw (ex-message e) :cut-ex (class e)})))

(let [[path] *command-line-args*
      _ (when-not path (throw (ex-info "path required" {})))
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
      note-run
      (fn [id]
        (if (= id @run-id)
          (vswap! run-n inc)
          (do (vreset! max-run (max @max-run @run-n)) (vreset! run-id id) (vreset! run-n 1))))
      break-run
      (fn [] (vreset! max-run (max @max-run @run-n)) (vreset! run-id nil) (vreset! run-n 0))
      final (record/reduce-entries
             (fn [w e]
               (if-not (:event/kind e)
                 w
                 (let [before w
                       w (assoc (main/step w e) :bot/effects [])
                       now (:time/now w 0)]
                   (when (and (nil? @go-at) (= :go (:event/kind e))) (vreset! go-at now))
                   (if-not @go-at
                     w
                     (let [t (- now @go-at)
                           m (quot t 60000)]
                       (when (> m @minute) (vreset! minute m) (println "replay" m "min"))
                       (cond (< t window-from) w
                             (> t window-to) (reduced w)
                             :else
                             (let [i (:plan/intent w)
                                   kind (:intent/kind i)
                                   id (:intent/id i)
                                   prev (:plan/intent before)]
                               (if (= :tunnel-to kind)
                                 (do (when-not (@tunnel-ids id) (vswap! tunnel-ids conj id))
                                     (when (:intent/move i) (vswap! moved conj id))
                                     (note-run id))
                                 (do (when (= :pocket kind) (vswap! pockets inc)) (break-run)))
                               (when (and (= :tunnel-to (:intent/kind prev))
                                          (not= :tunnel-to kind)
                                          (< @cuts cuts-to-print))
                                 (vswap! cuts inc)
                                 (prn (assoc (cut-line before w) :t t)))
                               w)))))))
             world0
             path)]
  (break-run)
  (prn {:summary true
        :tunnel-ids (count @tunnel-ids)
        :with-move (count @moved)
        :pocket-ticks @pockets
        :max-run @max-run
        :feet (try (intent/feet final) (catch Exception _ nil))
        :pos (:player/pos final)
        :last (select-keys (:plan/intent final)
                           [:intent/id :intent/kind :intent/target :intent/move])
        :hp (:player/health final)
        :fire (boolean (:player/on-fire? final))})
  (println "done"))
(shutdown-agents)
