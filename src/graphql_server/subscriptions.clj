(ns graphql-server.subscriptions
  "Topic-based pub/sub subscription infrastructure using core.async channels.

  Provides a pluggable [[ISubscriptionManager]] protocol for managing GraphQL
  subscriptions. Topics are vectors like `[:game game-id]` or `[:lobby]`.
  Subscribers receive core.async channels that emit messages published to
  their topic.

  The default [[CoreAsyncSubscriptionManager]] implementation uses sliding
  buffers to prevent slow consumers from blocking publishers. Users can
  implement their own subscription manager (Redis, Kafka, etc.) by
  implementing the [[ISubscriptionManager]] protocol."
  (:require [clojure.core.async :as async]
            [malli.core :as m]))

(def Topic
  "A topic is a vector identifying what to subscribe to.
   Examples: `[:game game-id]`, `[:lobby]`, `[:user user-id :notifications]`"
  [:vector :any])

(def Message
  "A message published to subscribers. Contains a `:type` keyword and
   optional `:data` payload."
  [:map
   [:type :keyword]
   [:data {:optional true} :any]])

(defprotocol ISubscriptionManager
  "Protocol for managing pub/sub subscriptions.

  Implementations handle the mechanics of tracking subscribers and
  delivering messages. The protocol is transport-agnostic - implementations
  can use core.async, Redis pub/sub, Kafka, or any other messaging system."

  (subscribe! [this topic]
    "Subscribes to a topic and returns a core.async channel.

    The returned channel receives all messages published to the topic.
    Implementations should use buffered channels to prevent slow consumers
    from blocking publishers. Returns the subscription channel.")

  (unsubscribe! [this topic channel]
    "Unsubscribes a channel from a topic.

    Closes the channel and removes it from the subscription list. Safe to
    call multiple times. Returns nil.")

  (publish! [this topic message]
    "Publishes a message to all subscribers of a topic.

    Non-blocking - messages are offered to subscriber channels. If a
    subscriber's buffer is full, the message may be dropped (sliding buffer)
    or the oldest message discarded. Returns nil.")

  (subscriber-count [this topic]
    "Returns the number of active subscribers for a topic.

    Returns 0 if the topic has no subscribers.")

  (topics [this]
    "Returns a sequence of all topics with active subscribers."))

(defrecord CoreAsyncSubscriptionManager [subscriptions]
  ISubscriptionManager

  (subscribe! [_ topic]
    {:pre [(m/validate Topic topic)]}
    (let [ch (async/chan (async/sliding-buffer 10))]
      (swap! subscriptions update topic (fnil conj #{}) ch)
      ch))

  (unsubscribe! [_ topic channel]
    (async/close! channel)
    (swap! subscriptions
           (fn [subs]
             (let [updated (update subs topic disj channel)]
               (if (empty? (get updated topic))
                 (dissoc updated topic)
                 updated))))
    nil)

  (publish! [_ topic message]
    (doseq [ch (get @subscriptions topic)]
      (async/offer! ch message))
    nil)

  (subscriber-count [_ topic]
    (count (get @subscriptions topic)))

  (topics [_]
    (keys @subscriptions)))

(defn create-subscription-manager
  "Creates a new [[CoreAsyncSubscriptionManager]].

  The subscription manager uses an atom to track subscriptions and core.async
  channels with sliding buffers for message delivery."
  []
  (->CoreAsyncSubscriptionManager (atom {})))
