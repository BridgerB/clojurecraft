(ns clojurecraft.record
  "Every input is a value, so a run is a file. `tap` writes each event as one EDN line and,
   after it, the effects that event produced as one {:record/effects [...]} line (events with
   no effects get none). `events` reads the events back; `replay` folds a reducer over them with
   no server; `verify` also checks that the reducer, given the same events, asks for exactly the
   same effects. Byte arrays print as #clojurecraft/bytes \"base64\". Recordings made before
   effects were recorded are read unchanged; they just have nothing to verify."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader Writer]
           [java.util Base64]))

(defmethod print-method (Class/forName "[B") [^bytes b ^Writer w]
  (.write w "#clojurecraft/bytes \"")
  (.write w (.encodeToString (Base64/getEncoder) b))
  (.write w "\""))

(def readers {'clojurecraft/bytes (fn [^String s] (.decode (Base64/getDecoder) s))})

(def derived
  "Packet keys that are pure functions of the wire bytes, added on the reader thread. A
   recording keeps the bytes and drops these; the reducer derives them again on replay."
  #{:chunk/column})

(defn wire
  "The event as it came off the wire: derived packet keys removed."
  [event]
  (if (:event/packet event) (update event :event/packet #(apply dissoc % derived)) event))

(defn tap
  "A writer for a recording: {:write (fn [event]) :effects (fn [effects]) :close (fn [])}.
   Call :write before applying an event and :effects with what applying it produced. Events are
   written as they came off the wire (see wire)."
  [path]
  (let [w (io/writer path)]
    {:write (fn [event] (binding [*out* w] (prn (wire event))))
     :effects (fn [effects] (when (seq effects) (binding [*out* w] (prn {:record/effects effects}))))
     :close (fn [] (.close w))}))

(defn entries
  "Every line of a recording, in order: event maps and {:record/effects ...} maps."
  [path]
  (with-open [r (PushbackReader. (io/reader path))]
    (into [] (take-while some? (repeatedly #(edn/read {:readers readers :eof nil} r))))))

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
      (loop [[e & more] es acc []]
        (cond (nil? e) acc
              (:event/kind e) (let [fx (:record/effects (first more))]
                                (recur (if fx (rest more) more) (conj acc (or fx []))))
              :else (recur more acc))))))

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
    (loop [w world0 [e & more] (events path) [r & rs] recorded i 0]
      (if (nil? e)
        nil
        (let [w (step w e)
              produced (:bot/effects w)]
          (if (= produced r)
            (recur (assoc w :bot/effects []) more rs (inc i))
            {:record/mismatch {:index i :event e :recorded r :replayed produced}}))))
    :record/no-effects))
