(ns graphql-server.sse-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [graphql-server.sse :as sse])
  (:import [java.io ByteArrayOutputStream InputStream]))

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

(deftest sse-response-body-is-input-stream-test
  (testing "sse-response body is an InputStream"
    (let [ch       (async/chan)
          response (sse/sse-response ch)]
      (is (instance? InputStream (:body response)))
      (async/close! ch))))

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
