(ns clojurecraft.record
  "Every input is a value, so a run is a file. `tap` writes each event as one EDN line and,
   after it, the effects that event produced as one {:record/effects [...]} line (events with
   no effects get none). `events` reads the events back; `replay` folds a reducer over them with
   no server; `verify` also checks that the reducer, given the same events, asks for exactly the
   same effects. Byte arrays print as #clojurecraft/bytes \"base64\". Recordings made before
   effects were recorded are read unchanged; they just have nothing to verify."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader Writer]
           [java.util Base64]
           [java.util.zip GZIPInputStream]))

(defmethod print-method (Class/forName "[B") [^bytes b ^Writer w]
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

(defn tap
  "A recording as a channel tap: {:write (fn [event]) :effects (fn [effects]) :close (fn [])}.
   Call :write before applying an event, :effects with what applying it produced, and :result
   with the RESULT the run printed (the recording ends there); each puts
   one value on a bounded channel and a thread of its own prints it, so the loop never does file
   I/O. The recording is the source of truth, so a full channel blocks the loop rather than
   dropping a line (unlike telemetry). Events are written as they came off the wire (see wire).
   :close waits until every line is on disk."
  [path]
  (let [ch (a/chan tap-buffer)
        done (a/thread (with-open [w (io/writer path)]
                         (binding [*out* w]
                           (loop []
                             (when-let [line (a/<!! ch)]
                               (prn line)
                               (recur))))))]
    {:write (fn [event] (a/>!! ch (wire event)))
     :effects (fn [effects] (when (seq effects) (a/>!! ch {:record/effects effects})))
     :result (fn [r] (a/>!! ch {:record/result r}))
     :close (fn [] (a/close! ch) (a/<!! done))}))

(defn entries
  "Every line of a recording, in order: event maps, {:record/effects ...} and {:record/result
   ...} maps. A path ending in .gz is read through gzip (the size recordings are kept at in git)."
  [path]
  (with-open [r (PushbackReader. (io/reader (if (str/ends-with? (str path) ".gz")
                                              (GZIPInputStream. (io/input-stream path))
                                              path)))]
    (into [] (take-while some? (repeatedly #(edn/read {:readers readers :eof nil} r))))))

(defn accretes?
  "Does later hold every fact earlier holds? Maps compare key by key, so attributes added since
   (accretion) are allowed; a value that changed or an attribute that went missing is not."
  [earlier later]
  (cond
    (and (map? earlier) (map? later)) (every? (fn [[k v]] (and (contains? later k) (accretes? v (get later k)))) earlier)
    :else (= earlier later)))

(defn recorded-result
  "The RESULT a recording says its run printed, or nil for recordings made before results were
   recorded."
  [path]
  (some :record/result (entries path)))

(defn events
  "The events in a recording, in order."
  [path]
  (filterv :event/kind (entries path)))

(defn effects
  "The recorded effects of each event, aligned with `events` ([] where none were produced), or
   nil when the recording predates effect recording."
  [path]
  (let [es (entries path)]
    (when (some :record/effects es)
      (reduce (fn [acc e]
                (cond (:event/kind e) (conj acc [])                              ; an event: no effects yet
                      (:record/effects e) (conj (pop acc) (:record/effects e))   ; its effects follow it
                      :else acc))
              [] es))))

(defn replay
  "Fold step over the recorded events from world0; returns the final world."
  [step world0 path]
  (reduce (fn [w e] (assoc (step w e) :bot/effects [])) world0 (events path)))

(defn verify
  "Replay the recording and compare what each event makes the reducer ask for with what was
   recorded. nil when every effect matches; {:record/mismatch {:index i :recorded r :replayed p}}
   at the first difference; :record/no-effects for a recording without effects."
  [step world0 path]
  (if-let [recorded (effects path)]
    (let [end (reduce (fn [w [i e r]]
                        (let [w (step w e)
                              produced (:bot/effects w)]
                          (if (= produced r)
                            (assoc w :bot/effects [])
                            (reduced {:record/mismatch {:index i :event e :recorded r :replayed produced}}))))
                      world0 (map vector (range) (events path) recorded))]
      (when (:record/mismatch end) end))
    :record/no-effects))
