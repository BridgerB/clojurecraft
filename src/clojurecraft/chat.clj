(ns clojurecraft.chat
  "What the server says in words (the observability plan): every system-chat line (the death of any
   player, the server's warnings) kept in :world/chat, the last chat-kept of them, and the bot's own
   death message (player-combat-kill's text component) kept in :player/death-text, so a death is
   explained by the server's own account and not by a guess from the last hit. Each is a
   defmethod on game/on-packet; the text is clojurecraft.text's reading of the component."
  (:require [clojurecraft.game :as game]
            [clojurecraft.said :as said]
            [clojurecraft.text :as text]))

(set! *warn-on-reflection* true)

(def chat-kept said/chat-kept)

(defn line
  "A heard line as {:at ms :text plain text :chat/via packet name :key translate key} (:key only
   when the component has one) from component c at time now, heard through the packet named via."
  [now c via]
  (let [k (text/translate-key c)]
    (cond-> {:at now :text (text/plain c) :chat/via via} k (assoc :key k))))

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
  (said/say w (line (:time/now w 0) content :system-chat)))

(defmethod game/on-packet [:play :set-action-bar-text]
  [w {:keys [text]}]
  (said/say w (line (:time/now w 0) text :set-action-bar-text)))

(defmethod game/on-packet [:play :set-title-text]
  [w {:keys [text]}]
  (said/say w (line (:time/now w 0) text :set-title-text)))

(defmethod game/on-packet [:play :set-subtitle-text]
  [w {:keys [text]}]
  (said/say w (line (:time/now w 0) text :set-subtitle-text)))

(defmethod game/on-packet [:play :player-combat-kill]
  [w {:keys [message]}]
  (assoc w :player/death-text (death-line (:time/now w 0) message)))
