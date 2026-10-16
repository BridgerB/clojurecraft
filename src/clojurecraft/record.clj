(ns clojurecraft.record
  "Every input is a value, so a run is a file. `tap` writes each event as one EDN line and,
   after it, the effects that event produced as one {:record/effects [...]} line (events with
   no effects get none). `events` reads the events back; `replay` folds a reducer over them with
   no server; `verify` also checks that the reducer, given the same events, asks for exactly the
   same effects. Byte arrays print as #clojurecraft/bytes \"base64\". Recordings made before
   effects were recorded are read unchanged; they just have nothing to verify."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader Writer]
           [java.util Base64]
           [java.util.zip GZIPInputStream]))

(set! *warn-on-reflection* true)

(defmethod print-method (Class/forName "[B")
  [^bytes b ^Writer w]
  (.write w "#clojurecraft/bytes \"")
  (.write w (.encodeToString (Base64/getEncoder) b))
  (.write w "\""))

(def readers {'clojurecraft/bytes (fn [^String s] (.decode (Base64/getDecoder) s))})

(def tap-buffer 4096)                 ; lines in flight to the recording's writer before the loop waits

(def derived
  "Packet keys that are pure functions of the wire bytes, added on the reader thread. A
   recording keeps the bytes and drops these; the reducer derives them again on replay."
  #{:chunk/column})

(defn wire
  "The event as it came off the wire: derived packet keys removed."
  [event]
  (if (:event/packet event) (update event :event/packet #(apply dissoc % derived)) event))

;;;; I/O: recording files ;;;;

(defn tap!
  "A recording as a channel tap: {:write (fn [event]) :effects (fn [effects]) :close (fn [])}.
   Call :write before applying an event, :effects with what applying it produced, and :result
   with the RESULT the run printed (the recording ends there); each puts
   one value on a bounded channel and a thread of its own prints it, so the loop never does file
   I/O. The recording is the source of truth, so a full channel blocks the loop rather than
   dropping a line (unlike telemetry). Events are written as they came off the wire (see wire).
   :close waits until every line is on disk. header, when not empty, is the first line: what the
   run is beside its events, {:record/commit sha} the code that made it, since a recording replays
   only with that code."
  ([path] (tap! path {}))
  ([path header]
   (let [ch (async/chan tap-buffer)
         done (async/thread (with-open [w (io/writer path)]
                              (binding [*out* w]
                                (loop []
                                  (when-let [line (async/<!! ch)]
                                    (prn line)
                                    (recur))))))]
     (when (seq header) (async/>!! ch header))
     {:write (fn [event] (async/>!! ch (wire event)))
      :effects (fn [effects] (when (seq effects) (async/>!! ch {:record/effects effects})))
      :result (fn [r] (async/>!! ch {:record/result r}))
      :close (fn [] (async/close! ch) (async/<!! done))})))

(defn input-of
  "The bytes of the recording at path: a path ending in .gz read through gzip (the size
   recordings are kept at in git), one ending in .zst through the zstd command (a race
   artifact's recordings are packed with it at the job's end, issue #299), any other as it is."
  ^java.io.InputStream [path]
  (cond (str/ends-with? (str path) ".gz") (GZIPInputStream. (io/input-stream path))
        (str/ends-with? (str path) ".zst")
        (.getInputStream (.start (ProcessBuilder. ^java.util.List (mapv str ["zstd" "-dcq" path]))))
        :else (io/input-stream path)))

(defn entries
  "Every line of a recording, in order: event maps, {:record/effects ...} and {:record/result
   ...} maps (input-of: .gz and .zst too)."
  [path]
  (with-open [r (PushbackReader. (io/reader (input-of path)))]
    (vec (take-while some? (repeatedly #(edn/read {:readers readers :eof nil} r))))))

(defn reduce-entries
  "f folded over every line of a recording from init, in order, one line read at a time: a race's
   recording is hundreds of thousands of lines, more than entries should hold at once (issue
   #299 folds whole ones on the race's runner). Stops early when f returns a reduced value."
  [f init path]
  (with-open [r (PushbackReader. (io/reader (input-of path)))]
    (loop [acc init]
      (let [e (edn/read {:readers readers :eof nil} r)]
        (cond (reduced? acc) @acc
              (nil? e) acc
              :else (recur (f acc e)))))))

(defn header
  "The recording's header, {:record/commit sha} when the run named its code, else {}: its first
   line when that is no event, read without reading the rest."
  [path]
  (with-open [r (PushbackReader. (io/reader (input-of path)))]
    (let [first-line (edn/read {:readers readers :eof nil} r)]
      (if (contains? first-line :record/commit) first-line {}))))

(defn accretes?
  "Does later hold every fact earlier holds? Maps compare key by key, so attributes added since
   (accretion) are allowed; a value that changed or an attribute that went missing is not."
  [earlier later]
  (cond (and (map? earlier) (map? later))
        (every? (fn [[k v]] (and (contains? later k) (accretes? v (get later k)))) earlier)
        :else (= earlier later)))

(defn recorded-result
  "The RESULT a recording says its run printed, or nil for recordings made before results were
   recorded."
  [path]
  (some :record/result (entries path)))

(defn events "The events in a recording, in order." [path] (filterv :event/kind (entries path)))

(defn effects
  "The recorded effects of each event, aligned with `events` ([] where none were produced), or
   nil when the recording predates effect recording."
  [path]
  (let [es (entries path)]
    (when (some :record/effects es)
      (reduce (fn [acc e]
                (cond (:event/kind e) (conj acc [])                            ; an event: no effects yet
                      (:record/effects e) (conj (pop acc) (:record/effects e)) ; its effects follow it
                      :else acc))
              []
              es))))

(defn replay
  "Fold step over the recorded events from world0; returns the final world."
  [step world0 path]
  (reduce (fn [w e] (assoc (step w e) :bot/effects [])) world0 (events path)))

(defn sent
  "The effects the server saw: the :send ones. A log line reworded since the recording is not
   a changed run, so a replay is held to what went down the wire."
  [effects]
  (filterv #(= :send (:effect/kind %)) effects))

(defn verify
  "Replay the recording and compare what each event makes the reducer send with what was
   recorded. nil when every sent packet matches; {:record/mismatch {:index i :recorded r
   :replayed p}} at the first difference; :record/no-effects for a recording without effects."
  [step world0 path]
  (if-let [recorded (effects path)]
    (let [end (reduce (fn [w [i e r]]
                        (let [w (step w e)
                              produced (:bot/effects w)]
                          (if (= (sent produced) (sent r))
                            (assoc w :bot/effects [])
                            (reduced
                             {:record/mismatch
                              {:index i :event e :recorded (sent r) :replayed (sent produced)}}))))
                      world0
                      (map vector (range) (events path) recorded))]
      (when (:record/mismatch end) end))
    :record/no-effects))
