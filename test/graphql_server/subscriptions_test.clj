(ns graphql-server.subscriptions-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.test :refer [deftest is testing]]
            [graphql-server.subscriptions :as subs]))

(deftest subscribe-returns-channel-test
  (testing "subscribe! returns a core.async channel"
    (let [mgr (subs/create-subscription-manager)
          ch  (subs/subscribe! mgr [:test])]
      (is (some? ch))
      (is (satisfies? async-protocols/Channel ch))
      (subs/unsubscribe! mgr [:test] ch))))

(deftest publish-delivers-to-subscriber-test
  (testing "published message arrives on subscribed channel"
    (let [mgr (subs/create-subscription-manager)
          ch  (subs/subscribe! mgr [:game "abc"])
          msg {:type :update :data {:score 10}}]
      (subs/publish! mgr [:game "abc"] msg)
      (is (= msg (async/poll! ch)))
      (subs/unsubscribe! mgr [:game "abc"] ch))))

(deftest multiple-subscribers-receive-message-test
  (testing "all subscribers receive published messages"
    (let [mgr (subs/create-subscription-manager)
          ch1 (subs/subscribe! mgr [:lobby])
          ch2 (subs/subscribe! mgr [:lobby])
          ch3 (subs/subscribe! mgr [:lobby])
          msg {:type :join :data {:user "alice"}}]
      (subs/publish! mgr [:lobby] msg)
      (is (= msg (async/poll! ch1)))
      (is (= msg (async/poll! ch2)))
      (is (= msg (async/poll! ch3)))
      (subs/unsubscribe! mgr [:lobby] ch1)
      (subs/unsubscribe! mgr [:lobby] ch2)
      (subs/unsubscribe! mgr [:lobby] ch3))))

(deftest unsubscribe-closes-channel-test
  (testing "unsubscribe closes the channel"
    (let [mgr (subs/create-subscription-manager)
          ch  (subs/subscribe! mgr [:test])]
      (subs/unsubscribe! mgr [:test] ch)
      (is (nil? (async/poll! ch))))))

(deftest unsubscribe-removes-from-subscriptions-test
  (testing "subscriber count decrements after unsubscribe"
    (let [mgr (subs/create-subscription-manager)
          ch1 (subs/subscribe! mgr [:game "x"])
          ch2 (subs/subscribe! mgr [:game "x"])]
      (is (= 2 (subs/subscriber-count mgr [:game "x"])))
      (subs/unsubscribe! mgr [:game "x"] ch1)
      (is (= 1 (subs/subscriber-count mgr [:game "x"])))
      (subs/unsubscribe! mgr [:game "x"] ch2)
      (is (= 0 (subs/subscriber-count mgr [:game "x"]))))))

(deftest empty-topic-removed-after-unsubscribe-test
  (testing "topic is removed when no subscribers remain"
    (let [mgr (subs/create-subscription-manager)
          ch  (subs/subscribe! mgr [:temp])]
      (is (contains? (set (subs/topics mgr)) [:temp]))
      (subs/unsubscribe! mgr [:temp] ch)
      (is (not (contains? (set (subs/topics mgr)) [:temp]))))))

(deftest subscriber-count-accurate-test
  (testing "subscriber-count returns correct count"
    (let [mgr (subs/create-subscription-manager)]
      (is (= 0 (subs/subscriber-count mgr [:empty])))
      (let [ch1 (subs/subscribe! mgr [:count-test])
            _   (is (= 1 (subs/subscriber-count mgr [:count-test])))
            ch2 (subs/subscribe! mgr [:count-test])
            _   (is (= 2 (subs/subscriber-count mgr [:count-test])))
            ch3 (subs/subscribe! mgr [:count-test])]
        (is (= 3 (subs/subscriber-count mgr [:count-test])))
        (subs/unsubscribe! mgr [:count-test] ch1)
        (subs/unsubscribe! mgr [:count-test] ch2)
        (subs/unsubscribe! mgr [:count-test] ch3)))))

(deftest topics-returns-active-test
  (testing "topics returns all topics with active subscribers"
    (let [mgr (subs/create-subscription-manager)
          ch1 (subs/subscribe! mgr [:game "a"])
          ch2 (subs/subscribe! mgr [:game "b"])
          ch3 (subs/subscribe! mgr [:lobby])]
      (is (= #{[:game "a"] [:game "b"] [:lobby]}
             (set (subs/topics mgr))))
      (subs/unsubscribe! mgr [:game "a"] ch1)
      (subs/unsubscribe! mgr [:game "b"] ch2)
      (subs/unsubscribe! mgr [:lobby] ch3))))

(deftest publish-to-empty-topic-safe-test
  (testing "publishing to topic with no subscribers does not error"
    (let [mgr (subs/create-subscription-manager)]
      (is (nil? (subs/publish! mgr [:nonexistent] {:type :test}))))))

(deftest unsubscribe-idempotent-test
  (testing "unsubscribe can be called multiple times safely"
    (let [mgr (subs/create-subscription-manager)
          ch  (subs/subscribe! mgr [:test])]
      (subs/unsubscribe! mgr [:test] ch)
      (subs/unsubscribe! mgr [:test] ch)
      (is (= 0 (subs/subscriber-count mgr [:test]))))))

(deftest different-topics-isolated-test
  (testing "subscribers only receive messages for their topic"
    (let [mgr  (subs/create-subscription-manager)
          ch-a (subs/subscribe! mgr [:game "a"])
          ch-b (subs/subscribe! mgr [:game "b"])
          msg  {:type :update}]
      (subs/publish! mgr [:game "a"] msg)
      (is (= msg (async/poll! ch-a)))
      (is (nil? (async/poll! ch-b)))
      (subs/unsubscribe! mgr [:game "a"] ch-a)
      (subs/unsubscribe! mgr [:game "b"] ch-b))))
