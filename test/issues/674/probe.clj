;; Issue #674: the scoop walk that flipped between two water cells on snow layers (flag #2,
;; recording run-20261031-161334.edn, 16:27:35-16:28:00 MDT). chunks.edn.gz holds the last level
;; chunk packet before 16:27:36 of every chunk round (-49, 20), copied out of the recording.
;; Prints, for feet cells round the flag's position, what the eyes see and where scoop walks, then
;; the block states between the eye and each water source. Run on a branch with
;;   bb probe run 674 'clojure -M:dev test/issues/674/probe.clj'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.game :as game]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.record :as record]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.terrain :as terrain])

(def packets
  (with-open [r (java.io.PushbackReader. (io/reader (java.util.zip.GZIPInputStream.
                                                     (io/input-stream
                                                      "test/issues/674/chunks.edn.gz"))))]
    (doall (take-while some? (repeatedly #(edn/read {:readers record/readers :eof nil} r))))))

(def base
  (try (reduce (fn [w e] (game/load-chunk w (:event/packet e)))
               (assoc (game/init {:host "h" :port 1 :name "Clj"})
                      :bot/phase :play
                      :player/loaded? true
                      :player/on-ground? true
                      :player/inventory {0 {:item (recipe/item-id :bucket) :count 1}})
               packets)
       (catch Exception t (.printStackTrace t) (System/exit 1))))

(defn name-at
  [w c]
  (some-> (terrain/block-at w c)
          blocks/name-of))

(def lake
  "The three ice cells the bot broke at 16:26:49-16:26:54 (the log's digs), now water sources."
  {[-783 62 322] blocks/water-base [-784 62 321] blocks/water-base [-785 62 321] blocks/water-base})

(defn at [x y z] (assoc base :player/pos [x (double y) z] :world/blocks lake))

(println "chunks" (count packets))
(println "blocks round the flag (x -786..-782, z 320..323), y 61..64, ice broken")
(doseq [y [64 63 62 61]
        z [320 321 322 323]]
  (println y z (mapv #(name-at (at -784 63 322) [% y z]) (range -786 -781))))
(doseq [[x z] [[-783.95 322.5] [-784.5 322.5] [-785.5 322.5] [-784.5 321.5] [-785.5 321.5]
               [-783.5 321.5]]
        :let [w (at x 63 z)]]
  (println "feet" x z "sources" (take 4 (scoop/sources w)) "->" (scoop/scoop-next w))
  (doseq [s (take 3 (scoop/sources w))
          :let [pt (scoop/aim-point s)]]
    (println "  source" s
             "sees?" (scoop/sees? w s)
             "in-reach?" (scoop/in-reach? w s)
             "ray" (mapv (fn [c] [c (name-at w c)]) (scoop/ray-cells w pt)))))
