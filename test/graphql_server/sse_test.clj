(ns graphql-server.sse-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [graphql-server.sse :as sse]
            [hato.client :as hato]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols])
  (:import [java.io BufferedReader ByteArrayOutputStream InputStreamReader]
           [java.net ServerSocket]))

(deftest sse-response-status-test
  (testing "sse-response returns 200 status"
    (let [ch       (async/chan)
          response (sse/sse-response ch)]
      (is (= 200 (:status response)))
      (async/close! ch))))

(deftest sse-response-headers-test
  (testing "sse-response has correct SSE headers"
    (let [ch       (async/chan)
          response (sse/sse-response ch)
          headers  (:headers response)]
      (is (= "text/event-stream" (get headers "Content-Type")))
      (is (= "no-cache, no-store, must-revalidate" (get headers "Cache-Control")))
      (is (= "keep-alive" (get headers "Connection")))
      (is (= "no" (get headers "X-Accel-Buffering")))
      (async/close! ch))))

(deftest sse-response-cors-default-test
  (testing "sse-response has default CORS origin *"
    (let [ch       (async/chan)
          response (sse/sse-response ch)
          headers  (:headers response)]
      (is (= "*" (get headers "Access-Control-Allow-Origin")))
      (is (= "true" (get headers "Access-Control-Allow-Credentials")))
      (async/close! ch))))

(deftest sse-response-cors-custom-test
  (testing "sse-response accepts custom CORS origin"
    (let [ch       (async/chan)
          response (sse/sse-response ch :cors-origin "https://example.com")
          headers  (:headers response)]
      (is (= "https://example.com" (get headers "Access-Control-Allow-Origin")))
      (async/close! ch))))

(deftest sse-response-body-is-streamable-test
  (testing "sse-response body implements StreamableResponseBody"
    (let [ch       (async/chan)
          response (sse/sse-response ch)]
      (is (satisfies? protocols/StreamableResponseBody (:body response)))
      (async/close! ch))))

(deftest sse-response-streams-events-test
  (testing "sse-response streams channel messages as SSE events"
    (let [ch       (async/chan)
          response (sse/sse-response ch)
          out      (ByteArrayOutputStream.)]
      (future
        (Thread/sleep 10)
        (async/>!! ch {:type :update :data {:score 10}})
        (Thread/sleep 10)
        (async/>!! ch {:type :game-end :data {:winner "home"}})
        (Thread/sleep 10)
        (async/close! ch))
      (protocols/write-body-to-stream (:body response) response out)
      (is (= (str ": ok\n\n"
                  "event: update\ndata: {\"score\":10}\n\n"
                  "event: game-end\ndata: {\"winner\":\"home\"}\n\n")
             (.toString out "UTF-8"))))))

(deftest write-sse-event-format-test
  (testing "write-sse-event produces correct SSE format"
    (let [out (ByteArrayOutputStream.)]
      (sse/write-sse-event out :update {:score 10})
      (is (= "event: update\ndata: {\"score\":10}\n\n"
             (.toString out "UTF-8"))))))

(deftest write-sse-event-string-type-test
  (testing "write-sse-event handles keyword event types"
    (let [out (ByteArrayOutputStream.)]
      (sse/write-sse-event out :game-state {:phase "active"})
      (is (= "event: game-state\ndata: {\"phase\":\"active\"}\n\n"
             (.toString out "UTF-8"))))))

(deftest write-sse-keepalive-format-test
  (testing "write-sse-keepalive produces correct SSE comment"
    (let [out (ByteArrayOutputStream.)]
      (sse/write-sse-keepalive out)
      (is (= ": keepalive\n\n" (.toString out "UTF-8"))))))

(deftest sse-headers-constant-test
  (testing "sse-headers contains required headers"
    (is (= "text/event-stream" (get sse/sse-headers "Content-Type")))
    (is (= "no-cache, no-store, must-revalidate" (get sse/sse-headers "Cache-Control")))
    (is (= "keep-alive" (get sse/sse-headers "Connection")))
    (is (= "no" (get sse/sse-headers "X-Accel-Buffering")))))

;; Integration tests with actual HTTP server

(defn- find-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- read-sse-event
  "Reads a single SSE event from a BufferedReader, returning a map with :event and :data.
  Skips comment-only blocks (keepalives) and returns the first real event."
  [^BufferedReader reader]
  (loop [event nil
         data  nil]
    (let [line (.readLine reader)]
      (cond
        (nil? line) nil
        ;; Blank line ends an event block - but only return if we have content
        (str/blank? line) (if (or event data)
                            {:event event :data data}
                            (recur nil nil))
        (str/starts-with? line "event: ") (recur (subs line 7) data)
        (str/starts-with? line "data: ") (recur event (subs line 6))
        ;; Comments are skipped
        (str/starts-with? line ":") (recur event data)
        :else (recur event data)))))

(deftest http-sse-headers-received-immediately-test
  (testing "HTTP request receives SSE headers before any events"
    (let [port     (find-free-port)
          event-ch (async/chan)
          handler  (fn [_req] (sse/sse-response event-ch))
          server   (jetty/run-jetty handler {:port port :join? false})
          response (hato/get (str "http://localhost:" port "/sse")
                             {:as :stream :timeout 5000})]
      (try
        (is (= 200 (:status response)))
        (is (= "text/event-stream" (get-in response [:headers "content-type"])))
        (finally
          (async/close! event-ch)
          (.stop server))))))

(deftest http-sse-streams-initial-event-test
  (testing "HTTP request receives SSE event after headers"
    (let [port     (find-free-port)
          event-ch (async/chan)
          handler  (fn [_req] (sse/sse-response event-ch))
          server   (jetty/run-jetty handler {:port port :join? false})
          response (hato/get (str "http://localhost:" port "/sse")
                             {:as :stream :timeout 5000})
          reader   (BufferedReader. (InputStreamReader. (:body response) "UTF-8"))]
      (try
        (is (= 200 (:status response)))
        (async/>!! event-ch {:type :connected :data {:session-id "abc123"}})
        (let [event (read-sse-event reader)]
          (is (= "connected" (:event event)))
          (is (str/includes? (:data event) "abc123")))
        (finally
          (async/close! event-ch)
          (.close reader)
          (.stop server))))))

(deftest http-sse-streams-multiple-events-test
  (testing "HTTP request receives multiple SSE events in sequence"
    (let [port     (find-free-port)
          event-ch (async/chan)
          handler  (fn [_req] (sse/sse-response event-ch))
          server   (jetty/run-jetty handler {:port port :join? false})
          response (hato/get (str "http://localhost:" port "/sse")
                             {:as :stream :timeout 5000})
          reader   (BufferedReader. (InputStreamReader. (:body response) "UTF-8"))]
      (try
        (async/>!! event-ch {:type :event-1 :data {:n 1}})
        (async/>!! event-ch {:type :event-2 :data {:n 2}})
        (let [e1 (read-sse-event reader)
              e2 (read-sse-event reader)]
          (is (= "event-1" (:event e1)))
          (is (= "event-2" (:event e2))))
        (finally
          (async/close! event-ch)
          (.close reader)
          (.stop server))))))
