(ns clojurecraft.threat
  "What a bot does about a threat, as data and open dispatch: the per-kind facts and the cover
   ladder (resources/clojurecraft/threats.edn), the measures a ladder names (defmethods of
   `measure` in the namespaces that know how), and `nearest-safe`, the one search over candidate
   cells for one no source reaches. Values in, values out."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def data
  "{:kinds {kind facts} :cover-ladder [measure ...] :pack-ladder [measure ...]}:
   resources/clojurecraft/threats.edn."
  (edn/read-string (slurp (io/resource "clojurecraft/threats.edn"))))

(defmulti measure
  "The intent of one in-place measure kind against a threat, or nil when it does not apply to ctx
   (a map the caller builds once from the world). Open on the measure keyword."
  {:arglists '([world ctx kind])}
  (fn [_world _ctx kind] kind))

(defmethod measure :default [_ _ _] nil)

(defn shelter
  "The first non-nil measure of ladder (in order, against ctx), else fallback. The ladder is data;
   its order is the policy."
  [world ctx ladder fallback]
  (or (some #(measure world ctx %) ladder) fallback))

(defn nearest-safe
  "The cell of cells that exposed? is false of and that dist ranks lowest, or nil when every cell
   is exposed. Ties keep the order of cells."
  [cells exposed? dist]
  (->> cells
       (remove exposed?)
       (sort-by dist)
       first))
