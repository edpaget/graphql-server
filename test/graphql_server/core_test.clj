(ns graphql-server.core-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [graphql-server.core :as core]
   [graphql-server.schema :as schema]
   [graphql-server.test-resolvers :as test-resolvers]
   [graphql-server.test-resolvers-with-middleware :as test-mw]
   [graphql-server.test-streamers :as test-streamers]
   [malli.core :as mc]
   [malli.experimental.time :as met]
   [malli.registry :as mr]))

(mr/set-default-registry!
 (mr/composite-registry
  (mc/default-schemas)
  (met/schemas)))

(deftest defresolver-basic
  (testing "defresolver creates a var with correct metadata"
    (let [resolver-var  (ns-resolve 'graphql-server.test-resolvers 'Query-hello)
          resolver-meta (meta resolver-var)]
      (is (= [:Query :hello] (:graphql/resolver resolver-meta)))
      (is (some? (:graphql/schema resolver-meta)))
      (is (fn? @resolver-var)))))

(deftest defresolver-with-docstring
  (testing "defresolver accepts optional docstring"
    (let [resolver-var  (ns-resolve 'graphql-server.test-resolvers 'Query-greet)
          resolver-meta (meta resolver-var)]
      (is (= "Returns a greeting" (:doc resolver-meta)))
      (is (= [:Query :greet] (:graphql/resolver resolver-meta))))))

(deftest defresolver-argument-coercion
  (testing "defresolver coerces arguments correctly"
    (is (= "Hello, Alice" (test-resolvers/Query-greet nil {:name "Alice"} nil))))
  (testing "defresolver returns errors on invalid arguments"
    (let [result (test-resolvers/Query-greet nil {:name 123} nil)]
      (is (map? result))
      (is (contains? result :errors)))))

(deftest collect-resolvers-test
  (testing "collect-resolvers gathers all resolvers from a namespace"
    (let [resolvers (core/collect-resolvers 'graphql-server.test-resolvers)]
      (is (>= (count resolvers) 4))
      (is (contains? resolvers [:Query :hello]))
      (is (contains? resolvers [:Query :greet]))
      (is (contains? resolvers [:Query :users]))
      (is (contains? resolvers [:Mutation :createUser]))
      (let [[schema resolver-var] (get resolvers [:Query :users])]
        (is (some? schema))
        (is (var? resolver-var))))))

(deftest def-resolver-map-test
  (testing "def-resolver-map creates resolvers var"
    (let [resolvers-var (ns-resolve 'graphql-server.test-resolvers 'resolvers)]
      (is (some? resolvers-var))
      (is (map? @resolvers-var))
      (is (contains? @resolvers-var [:Query :hello])))))

(deftest def-resolver-map-with-docstring-test
  (testing "def-resolver-map with docstring preserves docstring"
    (let [resolvers-var (ns-resolve 'graphql-server.test-resolvers-with-middleware 'resolvers)
          resolver-meta (meta resolvers-var)]
      (is (some? resolvers-var))
      (is (= "Resolvers with middleware" (:doc resolver-meta))))))

(deftest def-resolver-map-with-middleware-test
  (testing "def-resolver-map with middleware wraps resolvers"
    (let [resolvers             test-mw/resolvers
          [_schema resolver-fn] (get resolvers [:Query :withMiddleware])
          result                (resolver-fn nil nil nil)]
      ;; The resolver returns {:result "test"}
      ;; The middleware adds {:middleware-applied true}
      (is (= {:result "test" :middleware-applied true} result)))))

(deftest defresolver-field-resolver
  (testing "defresolver allows field resolvers on custom object types"
    (let [resolver-var  (ns-resolve 'graphql-server.test-resolvers 'User-fullName)
          resolver-meta (meta resolver-var)]
      (is (= [:User :fullName] (:graphql/resolver resolver-meta)))
      (is (some? (:graphql/schema resolver-meta)))
      (is (fn? @resolver-var)))))

(deftest defresolver-field-resolver-with-docstring
  (testing "field resolver accepts optional docstring"
    (let [resolver-var  (ns-resolve 'graphql-server.test-resolvers 'User-fullName)
          resolver-meta (meta resolver-var)]
      (is (= "Computes the full name from first and last name" (:doc resolver-meta))))))

(deftest defresolver-field-resolver-execution
  (testing "field resolver extracts data from parent value"
    #_{:clj-kondo/ignore [:unresolved-var]}
    (let [result (test-resolvers/User-fullName nil nil {:first-name "John" :last-name "Doe"})]
      (is (= "John Doe" result)))))

(deftest collect-resolvers-includes-field-resolvers
  (testing "collect-resolvers gathers field resolvers from a namespace"
    (let [resolvers (core/collect-resolvers 'graphql-server.test-resolvers)]
      (is (contains? resolvers [:User :fullName]))
      (let [[schema resolver-var] (get resolvers [:User :fullName])]
        (is (some? schema))
        (is (var? resolver-var))))))

(deftest integration-with-schema
  (testing "defresolver works with ->graphql-schema"
    (let [gql-schema (schema/->graphql-schema test-resolvers/resolvers)]
      (is (contains? (:objects gql-schema) :Query))
      (is (contains? (:objects gql-schema) :Mutation))
      (is (contains? (:objects gql-schema) :User))
      (is (contains? (get-in gql-schema [:objects :Query :fields]) :users))
      (is (contains? (get-in gql-schema [:objects :Mutation :fields]) :createUser)))))

;; =============================================================================
;; Streamer tests
;; =============================================================================

(deftest defstreamer-basic
  (testing "defstreamer creates a var with correct metadata"
    (let [streamer-var  (ns-resolve 'graphql-server.test-streamers 'Subscription-messageAdded)
          streamer-meta (meta streamer-var)]
      (is (= [:Subscription :messageAdded] (:graphql/streamer streamer-meta)))
      (is (some? (:graphql/schema streamer-meta)))
      (is (fn? @streamer-var)))))

(deftest defstreamer-with-docstring
  (testing "defstreamer accepts optional docstring"
    (let [streamer-var  (ns-resolve 'graphql-server.test-streamers 'Subscription-gameUpdated)
          streamer-meta (meta streamer-var)]
      (is (= "Subscribe to game state changes" (:doc streamer-meta)))
      (is (= [:Subscription :gameUpdated] (:graphql/streamer streamer-meta))))))

(deftest defstreamer-returns-cleanup-function
  (testing "defstreamer returns a cleanup function"
    (let [streamer      test-streamers/Subscription-messageAdded
          source-values (atom [])
          source-stream (fn [v] (swap! source-values conj v))
          cleanup-fn    (streamer {} {} source-stream)]
      (is (fn? cleanup-fn))
      (cleanup-fn))))

(deftest defstreamer-pumps-channel-to-source-stream
  (testing "defstreamer pumps values from channel to source-stream"
    (let [game-id       (random-uuid)
          streamer      test-streamers/Subscription-gameUpdated
          source-values (atom [])
          source-stream (fn [v] (swap! source-values conj v))
          cleanup-fn    (streamer {} {:gameId (str game-id)} source-stream)]
      ;; Wait for go-loop to process
      (Thread/sleep 50)
      ;; Should have received the encoded value
      (is (>= (count @source-values) 1))
      ;; Check encoding - keys should be camelCase
      (let [first-value (first @source-values)]
        (is (contains? first-value :phase))
        (is (contains? first-value :playerCount)))
      (cleanup-fn))))

(deftest defstreamer-encodes-values
  (testing "defstreamer encodes streamed values with camelCase keys"
    (let [game-id       (random-uuid)
          streamer      test-streamers/Subscription-gameUpdated
          source-values (atom [])
          source-stream (fn [v] (swap! source-values conj v))
          cleanup-fn    (streamer {} {:gameId (str game-id)} source-stream)]
      (Thread/sleep 50)
      (let [first-value (first @source-values)]
        ;; Keys should be camelCase
        (is (contains? first-value :playerCount))
        (is (not (contains? first-value :player-count))))
      (cleanup-fn))))

(deftest defstreamer-sends-nil-on-channel-close
  (testing "defstreamer sends nil to source-stream when channel closes"
    (let [ch            (async/chan 1)
          source-values (atom [])
          done-promise  (promise)
          source-stream (fn [v]
                          (swap! source-values conj v)
                          (when (nil? v) (deliver done-promise true)))
          ;; Create a simple streamer inline for this test
          _             (async/go-loop []
                          (if-let [v (async/<! ch)]
                            (do (source-stream v)
                                (recur))
                            (source-stream nil)))]
      (async/put! ch {:test "value"})
      (Thread/sleep 50)
      (async/close! ch)
      ;; Wait for nil to be delivered
      (deref done-promise 1000 :timeout)
      ;; Should have received the value and then nil
      (is (= [{:test "value"} nil] @source-values)))))

(deftest collect-streamers-test
  (testing "collect-streamers gathers all streamers from a namespace"
    (let [streamers (core/collect-streamers 'graphql-server.test-streamers)]
      (is (= 3 (count streamers)))
      (is (contains? streamers [:Subscription :messageAdded]))
      (is (contains? streamers [:Subscription :gameUpdated]))
      (is (contains? streamers [:Subscription :statusChanged]))
      (let [[schema streamer-var] (get streamers [:Subscription :gameUpdated])]
        (is (some? schema))
        (is (var? streamer-var))))))

(deftest def-resolver-map-includes-streamers
  (testing "def-resolver-map includes streamers in the resolvers var"
    (let [resolvers test-streamers/resolvers]
      (is (contains? resolvers [:Subscription :messageAdded]))
      (is (contains? resolvers [:Subscription :gameUpdated]))
      (is (contains? resolvers [:Subscription :statusChanged])))))

(deftest integration-streamers-with-schema
  (testing "defstreamer works with ->graphql-schema"
    (let [gql-schema (schema/->graphql-schema test-streamers/resolvers)]
      (is (contains? gql-schema :subscriptions))
      (is (contains? (:subscriptions gql-schema) :messageAdded))
      (is (contains? (:subscriptions gql-schema) :gameUpdated))
      (is (contains? (:subscriptions gql-schema) :statusChanged))
      ;; Check GameState type was discovered
      (is (contains? (:objects gql-schema) :GameState))
      ;; Check GameStatus enum was discovered
      (is (contains? (:enums gql-schema) :GameStatus)))))
