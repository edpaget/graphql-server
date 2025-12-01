(ns graphql-server.test-streamers
  (:require
   [clojure.core.async :as async]
   [graphql-server.core :refer [defstreamer def-resolver-map]]))

(def GameState
  "Game state schema for testing."
  [:map {:graphql/type :GameState}
   [:id :uuid]
   [:phase :string]
   [:player-count :int]])

(def GameStatus
  "Game status enum for testing."
  [:enum {:graphql/type :GameStatus}
   :waiting :active :finished])

(defstreamer :Subscription :messageAdded
  [:=> [:cat :any :any :any] :string]
  [_ctx _args]
  (async/chan 10))

(defstreamer :Subscription :gameUpdated
  "Subscribe to game state changes"
  [:=> [:cat :any [:map [:game-id :uuid]] :any] GameState]
  [_ctx {:keys [game-id]}]
  (let [ch (async/chan 10)]
    (async/put! ch {:id game-id :phase "active" :player-count 2})
    ch))

(defstreamer :Subscription :statusChanged
  [:=> [:cat :any :any :any] GameStatus]
  [_ctx _args]
  (async/chan 10))

(def-resolver-map)
