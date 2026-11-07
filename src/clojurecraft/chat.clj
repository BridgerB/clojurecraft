(ns clojurecraft.chat
  "What the server says in words (the observability plan): every system-chat line (the death of any
   player, the server's warnings) kept in :world/chat, the last chat-kept of them, and the bot's own
   death message (player-combat-kill's text component) kept in :player/death-text, so a death is
   explained by the server's own account and not by a guess from the last hit. Each is a
   defmethod on game/on-packet; the text is clojurecraft.text's reading of the component."
  (:require [clojurecraft.game :as game]
            [clojurecraft.text :as text]))

(set! *warn-on-reflection* true)

(def chat-kept 20)                     ; lines of :world/chat kept, the newest

(defn line
  "A heard line as {:at ms :text plain text :key translate key} (:key only when the component has
   one) from component c at time now."
  [now c]
  (let [k (text/translate-key c)] (cond-> {:at now :text (text/plain c)} k (assoc :key k))))

(defn death-line
  "The bot's death as {:at ms :text plain text :key death key :killer short name} from component
   c at time now (:key and :killer only when the component is a death message and names them)."
  [now c]
  (let [{k :death/key by :death/by} (text/death c)]
    (cond-> {:at now :text (text/plain c)}
      k (assoc :key k)
      by (assoc :killer by))))

(defmethod game/on-packet [:play :system-chat]
  [w {:keys [content]}]
  (update w
          :world/chat
          (fn [chat]
            (let [chat (conj (or chat []) (line (:time/now w 0) content))]
              (cond-> chat (> (count chat) chat-kept) (subvec (- (count chat) chat-kept)))))))

(defmethod game/on-packet [:play :player-combat-kill]
  [w {:keys [message]}]
  (assoc w :player/death-text (death-line (:time/now w 0) message)))
