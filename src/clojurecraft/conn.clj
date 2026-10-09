(ns clojurecraft.conn
  "The socket. Frames in, frames out, compression, and which protocol state the wire is in.
   Decoded packets arrive on the :in channel; packet maps put on :out are encoded and sent.
   This is one of the three namespaces that do I/O (with rcon and main)."
  (:require [clojure.core.async :as a]
            [clojurecraft.bytes :as b]
            [clojurecraft.packet :as p])
  (:import [java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream
            DataInputStream DataOutputStream]
           [java.net InetSocketAddress Socket]
           [java.util.zip Deflater Inflater]))

(defn- read-varint-stream ^long [^DataInputStream in]
  (loop [result 0 shift 0]
    (let [x (.readUnsignedByte in)
          result (bit-or result (bit-shift-left (bit-and x 0x7F) shift))]
      (if (zero? (bit-and x 0x80))
        result
        (recur result (+ shift 7))))))

(defn- inflate ^bytes [^bytes data ^long size]
  (let [inf (Inflater.) out (byte-array size)]
    (.setInput inf data)
    (.inflate inf out)
    (.end inf)
    out))

(defn- deflate ^bytes [^bytes data]
  (let [d (doto (Deflater.) (.setInput data) (.finish))
        buf (byte-array 8192)
        baos (ByteArrayOutputStream.)]
    (while (not (.finished d))
      (.write baos buf 0 (.deflate d buf)))
    (.end d)
    (.toByteArray baos)))

(defn read-frame
  "One frame's packet bytes (id + body), decompressed when a threshold is in force."
  ^bytes [^DataInputStream in ^long threshold]
  (let [raw (byte-array (read-varint-stream in))]
    (.readFully in raw)
    (if (neg? threshold)
      raw
      (let [buf (b/buffer raw)
            size (b/read-varint buf)
            body (b/read-rest buf)]
        (if (zero? size) body (inflate body size))))))

(defn write-frame [^DataOutputStream out ^bytes payload ^long threshold]
  (let [body (cond
               (neg? threshold) payload
               (< (alength payload) threshold)
               (b/with-out (fn [o] (b/write-varint o 0) (b/write-bytes o payload)))
               :else
               (b/with-out (fn [o] (b/write-varint o (alength payload)) (b/write-bytes o (deflate payload)))))]
    (b/write-varint out (alength body))
    (b/write-bytes out body)
    (.flush out)))

(defn open
  "Connect. Returns {:in chan :out chan :close! fn}. The reader thread decodes with the state
   current when a frame has fully arrived; the writer thread flips state before sending a
   state-changing packet, so the server's reply is decoded in the new state."
  [{:keys [host port]}]
  (let [sock (doto (Socket.) (.connect (InetSocketAddress. ^String host (int port)) 10000) (.setTcpNoDelay true))
        in (DataInputStream. (BufferedInputStream. (.getInputStream sock) 65536))
        out (DataOutputStream. (BufferedOutputStream. (.getOutputStream sock) 65536))
        proto (atom {:state :handshake :threshold -1})
        inbox (a/chan 1024)
        outbox (a/chan 256)
        closed (fn [reason] (a/>!! inbox {:packet/name :closed :packet/reason reason}) (a/close! inbox))]
    (a/thread
      (try
        (loop []
          (let [frame (read-frame in (:threshold @proto))
                state (:state @proto)
                pkt (try (p/decode state :s2c frame)
                         (catch Exception e {:packet/name :decode-error :packet/state state
                                             :packet/error (str e) :packet/len (alength frame)}))]
            (when (and (= state :login) (= :login-compression (:packet/name pkt)))
              (swap! proto assoc :threshold (:threshold pkt)))
            (a/>!! inbox pkt)
            (recur)))
        (catch Throwable e (closed (str e)))))
    (a/thread
      (try
        (loop []
          (when-let [pkt (a/<!! outbox)]
            (let [state (:state @proto)
                  payload (p/encode state pkt)]
              (swap! proto update :state p/next-state (:packet/name pkt))
              (write-frame out payload (:threshold @proto))
              (recur))))
        (catch Throwable e (closed (str e)))))
    {:in inbox
     :out outbox
     :close! (fn [] (a/close! outbox) (.close sock))}))
