(ns graphql-server.sse
  "Server-Sent Events response utilities for GraphQL subscriptions.

  Provides Ring response helpers for streaming data from core.async channels
  as SSE events. Handles connection lifecycle, keepalive messages, and proper
  HTTP streaming headers.

  The main entry point is [[sse-response]], which creates a Ring streaming
  response from a core.async channel. Messages on the channel should be maps
  with `:type` and `:data` keys."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [ring.core.protocols :as protocols])
  (:import [java.io OutputStream]))

(def sse-headers
  "Standard HTTP headers for SSE responses.

  - `Content-Type: text/event-stream` - Required for SSE
  - `Cache-Control: no-cache, no-store, must-revalidate` - Prevent caching
  - `Connection: keep-alive` - Maintain persistent connection
  - `X-Accel-Buffering: no` - Disable nginx buffering"
  {"Content-Type"      "text/event-stream"
   "Cache-Control"     "no-cache, no-store, must-revalidate"
   "Connection"        "keep-alive"
   "X-Accel-Buffering" "no"})

(defn write-sse-event
  "Writes a single SSE event to the output stream.

  The event is formatted as:
  ```
  event: <event-type>
  data: <json-data>

  ```

  The `event-type` is converted to a name string. The `data` is JSON-encoded."
  [^OutputStream out event-type data]
  (let [event-str (str "event: " (name event-type) "\n"
                       "data: " (json/generate-string data) "\n\n")]
    (.write out (.getBytes event-str "UTF-8"))
    (.flush out)))

(defn write-sse-keepalive
  "Writes an SSE comment as a keepalive signal.

  SSE comments (lines starting with `:`) are ignored by clients but keep
  the connection alive and detect disconnected clients."
  [^OutputStream out]
  (.write out (.getBytes ": keepalive\n\n" "UTF-8"))
  (.flush out))

(defn- stream-channel-to-output
  "Streams messages from a core.async channel to an output stream as SSE events.

  Writes an initial SSE comment immediately to flush headers to the client.
  Sends keepalive comments every 15 seconds to maintain the connection.
  Closes both the keepalive channel and the source channel on completion."
  [^OutputStream out channel]
  (let [keepalive-ch (async/chan)]
    ;; Write initial comment to flush headers immediately
    (.write out (.getBytes ": ok\n\n" "UTF-8"))
    (.flush out)
    (async/go-loop []
      (async/<! (async/timeout 15000))
      (when (async/>! keepalive-ch :keepalive)
        (recur)))
    (try
      (loop []
        (let [[val _port] (async/alts!! [channel keepalive-ch]
                                        :priority true)]
          (cond
            (nil? val)
            (log/debug "SSE channel closed, ending stream")

            (= val :keepalive)
            (do
              (write-sse-keepalive out)
              (recur))

            :else
            (do
              (write-sse-event out
                               (get val :type :message)
                               (get val :data val))
              (recur)))))
      (catch Exception e
        (log/debug e "SSE stream error (likely client disconnect)"))
      (finally
        (async/close! keepalive-ch)
        (async/close! channel)))))

(defn sse-response
  "Creates a Ring streaming response from a core.async channel.

  The channel should emit maps with `:type` and `:data` keys. Each message
  is formatted as an SSE event and written to the response stream.

  Uses `ring.core.protocols/StreamableResponseBody` to ensure headers are
  written immediately before the body starts streaming.

  Automatically sends keepalive comments every 15 seconds to maintain the
  connection and detect disconnected clients. Closes cleanly when the
  channel closes or the client disconnects.

  Options:
  - `:cors-origin` - Value for Access-Control-Allow-Origin header (default `\"*\"`)"
  [channel & {:keys [cors-origin] :or {cors-origin "*"}}]
  {:status  200
   :headers (merge sse-headers
                   {"Access-Control-Allow-Origin"      cors-origin
                    "Access-Control-Allow-Credentials" "true"})
   :body    (reify protocols/StreamableResponseBody
              (write-body-to-stream [_ _response output-stream]
                (stream-channel-to-output output-stream channel)))})