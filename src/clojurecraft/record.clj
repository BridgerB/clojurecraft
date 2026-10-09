(ns clojurecraft.record
  "Every input is a value, so a run is a file. `tap` writes each event as one EDN line; `events`
   reads them back; `replay` folds a reducer over them with no server. Byte arrays print as
   #clojurecraft/bytes \"base64\"."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader Writer]
           [java.util Base64]))

(defmethod print-method (Class/forName "[B") [^bytes b ^Writer w]
  (.write w "#clojurecraft/bytes \"")
  (.write w (.encodeToString (Base64/getEncoder) b))
  (.write w "\""))

(def readers {'clojurecraft/bytes (fn [^String s] (.decode (Base64/getDecoder) s))})

(defn tap
  "A writer for events: returns {:write (fn [event]) :close (fn [])}."
  [path]
  (let [w (io/writer path)]
    {:write (fn [event] (binding [*out* w] (prn event)))
     :close (fn [] (.close w))}))

(defn events
  "The events in a recording, in order."
  [path]
  (with-open [r (PushbackReader. (io/reader path))]
    (into [] (take-while some? (repeatedly #(edn/read {:readers readers :eof nil} r))))))

(defn replay
  "Fold step over the recorded events from world0; returns the final world."
  [step world0 path]
  (reduce (fn [w e] (assoc (step w e) :bot/effects [])) world0 (events path)))
