(ns graphql-server.ring-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [graphql-server.core :refer [defresolver defstreamer def-resolver-map]]
   [graphql-server.ring :as ring]
   [graphql-server.subscriptions :as subs]
   [java-time.api :as t]
   [ring.core.protocols :as protocols]))

(defresolver :Query :hello
  [:=> [:cat :any :any :any] :string]
  [_ctx _args _value]
  "Hello, World!")

(defresolver :Query :greet
  [:=> [:cat :any [:map [:name :string]] :any] :string]
  [_ctx {:keys [name]} _value]
  (str "Hello, " name "!"))

(defresolver :Query :echo
  [:=> [:cat :any [:map [:message :string]] :any] :string]
  [_ctx {:keys [message]} _value]
  message)

(defresolver :Query :timestamp
  [:=> [:cat :any :any :any] :time/instant]
  [_ctx _args _value]
  (t/instant "2025-01-01T00:00:00Z"))

(defresolver :Query :uuid
  [:=> [:cat :any :any :any] :uuid]
  [_ctx _args _value]
  #uuid "550e8400-e29b-41d4-a716-446655440000")

(defresolver :Query :contextValue
  [:=> [:cat :any :any :any] :string]
  [ctx _args _value]
  (get-in ctx [:request :user]))

(defresolver :Mutation :createMessage
  [:=> [:cat :any [:map [:text :string]] :any] :string]
  [_ctx {:keys [text]} _value]
  (str "Created: " text))

(defstreamer :Subscription :onMessage
  "Test subscription streamer"
  [:=> [:cat :any :any :any] :string]
  [ctx _args]
  (let [sub-mgr (:subscription-manager ctx)
        ch      (subs/subscribe! sub-mgr [:messages])]
    ch))

(def-resolver-map)

(defn- make-graphql-request
  "Creates a Ring request map for a GraphQL query with pre-parsed body."
  ([query]
   (make-graphql-request query nil))
  ([query variables]
   (make-graphql-request query variables {}))
  ([query variables extra-keys]
   (merge
    {:request-method :post
     :uri "/graphql"
     :headers {"content-type" "application/json"}
     :body (cond-> {:query query}
             variables (assoc :variables variables))}
    extra-keys)))

(defn- passthrough-handler
  "A handler that just returns :not-graphql for non-GraphQL requests."
  [_request]
  {:status 200 :body "not-graphql"})

(deftest build-lacinia-schema-test
  (testing "build-lacinia-schema creates a compiled Lacinia schema"
    (let [schema (ring/build-lacinia-schema resolvers)]
      (is (some? schema))
      (is (map? schema)))))

(deftest graphql-middleware-basic-query-test
  (testing "graphql-middleware handles basic queries"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request "{ hello }")
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "Hello, World!" (get-in body [:data :hello])))
      (is (nil? (:errors body))))))

(deftest graphql-middleware-query-with-arguments-test
  (testing "graphql-middleware handles queries with arguments"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request
                    "query Greet($name: String!) { greet(name: $name) }"
                    {:name "Alice"})
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Hello, Alice!" (get-in body [:data :greet]))))))

(deftest graphql-middleware-mutation-test
  (testing "graphql-middleware handles mutations"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request
                    "mutation CreateMsg($text: String!) { createMessage(text: $text) }"
                    {:text "Test message"})
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Created: Test message" (get-in body [:data :createMessage]))))))

(deftest graphql-middleware-date-scalar-test
  (testing "graphql-middleware handles Date scalars"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request "{ timestamp }")
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "2025-01-01T00:00:00Z" (get-in body [:data :timestamp]))))))

(deftest graphql-middleware-uuid-scalar-test
  (testing "graphql-middleware handles UUID scalars"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request "{ uuid }")
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "550e8400-e29b-41d4-a716-446655440000" (get-in body [:data :uuid]))))))

(deftest graphql-middleware-custom-path-test
  (testing "graphql-middleware respects custom path"
    (let [handler              (ring/graphql-middleware
                                passthrough-handler
                                {:resolver-map resolvers
                                 :path "/api/graphql"})
          default-path-request (make-graphql-request "{ hello }")
          custom-path-request  (assoc default-path-request :uri "/api/graphql")
          default-response     (handler default-path-request)
          custom-response      (handler custom-path-request)
          custom-body          (json/parse-string (:body custom-response) true)]
      (is (= "not-graphql" (:body default-response)))
      (is (= 200 (:status custom-response)))
      (is (= "Hello, World!" (get-in custom-body [:data :hello]))))))

(deftest graphql-middleware-context-fn-test
  (testing "graphql-middleware uses custom context-fn"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers
                     :context-fn (fn [req] {:request req})})
          request  (make-graphql-request "{ contextValue }" nil {:user "Alice"})
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Alice" (get-in body [:data :contextValue]))))))

(deftest graphql-middleware-non-post-request-test
  (testing "graphql-middleware ignores non-POST/GET requests"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  {:request-method :put
                    :uri "/graphql"}
          response (handler request)]
      (is (= "not-graphql" (:body response))))))

(deftest graphql-middleware-non-graphql-path-test
  (testing "graphql-middleware ignores non-GraphQL paths"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  {:request-method :post
                    :uri "/api/other"}
          response (handler request)]
      (is (= "not-graphql" (:body response))))))

(deftest graphql-middleware-graphql-error-test
  (testing "graphql-middleware handles GraphQL errors"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  (make-graphql-request "{ nonexistentField }")
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (some? (:errors body))))))

(deftest graphql-middleware-get-request-graphiql-test
  (testing "graphql-middleware serves GraphiQL on GET requests"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  {:request-method :get
                    :uri "/graphql"}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "text/html" (get-in response [:headers "Content-Type"])))
      (is (str/includes? (:body response) "GraphiQL"))
      (is (str/includes? (:body response) "esm.sh/graphiql")))))

(deftest graphql-middleware-graphiql-disabled-test
  (testing "graphql-middleware does not serve GraphiQL when disabled"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers
                     :enable-graphiql? false})
          request  {:request-method :get
                    :uri "/graphql"}
          response (handler request)]
      (is (= "not-graphql" (:body response))))))

;; Subscription tests

(deftest subscription-disabled-by-default-test
  (testing "subscription endpoint is disabled by default"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  {:request-method :get
                    :uri "/graphql/subscriptions"
                    :query-string "query=subscription%20%7B%20onMessage%20%7D"}
          response (handler request)]
      (is (= "not-graphql" (:body response))))))

(deftest subscription-endpoint-returns-sse-test
  (testing "subscription endpoint returns SSE response when enabled"
    (let [sub-mgr  (subs/create-subscription-manager)
          handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers
                     :enable-subscriptions? true
                     :context-fn (fn [_] {:subscription-manager sub-mgr})})
          request  {:request-method :get
                    :uri "/graphql/subscriptions"
                    :query-string "query=subscription%20%7B%20onMessage%20%7D"}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "text/event-stream" (get-in response [:headers "Content-Type"])))
      (is (satisfies? protocols/StreamableResponseBody (:body response))))))

(deftest subscription-and-graphiql-coexist-test
  (testing "subscriptions and GraphiQL work together with default paths"
    (let [sub-mgr           (subs/create-subscription-manager)
          handler           (ring/graphql-middleware
                             passthrough-handler
                             {:resolver-map resolvers
                              :enable-subscriptions? true
                              :context-fn (fn [_] {:subscription-manager sub-mgr})})
          graphiql-req      {:request-method :get
                             :uri "/graphql"}
          subscription-req  {:request-method :get
                             :uri "/graphql/subscriptions"
                             :query-string "query=subscription%20%7B%20onMessage%20%7D"}
          graphiql-resp     (handler graphiql-req)
          subscription-resp (handler subscription-req)]
      ;; GraphiQL served at /graphql
      (is (= 200 (:status graphiql-resp)))
      (is (= "text/html" (get-in graphiql-resp [:headers "Content-Type"])))
      (is (str/includes? (:body graphiql-resp) "GraphiQL"))
      ;; Subscriptions handled at /graphql/subscriptions
      (is (= 200 (:status subscription-resp)))
      (is (= "text/event-stream" (get-in subscription-resp [:headers "Content-Type"]))))))

(deftest subscription-options-preflight-test
  (testing "subscription endpoint handles OPTIONS preflight"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers
                     :enable-subscriptions? true})
          request  {:request-method :options
                    :uri "/graphql/subscriptions"}
          response (handler request)]
      (is (= 204 (:status response)))
      (is (= "GET, OPTIONS" (get-in response [:headers "Access-Control-Allow-Methods"])))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"]))))))

(deftest subscription-custom-cors-origin-test
  (testing "subscription endpoint uses custom CORS origin"
    (let [sub-mgr      (subs/create-subscription-manager)
          handler      (ring/graphql-middleware
                        passthrough-handler
                        {:resolver-map resolvers
                         :enable-subscriptions? true
                         :cors-origin "https://example.com"
                         :context-fn (fn [_] {:subscription-manager sub-mgr})})
          options-req  {:request-method :options
                        :uri "/graphql/subscriptions"}
          sub-req      {:request-method :get
                        :uri "/graphql/subscriptions"
                        :query-string "query=subscription%20%7B%20onMessage%20%7D"}
          options-resp (handler options-req)
          sub-resp     (handler sub-req)]
      (is (= "https://example.com" (get-in options-resp [:headers "Access-Control-Allow-Origin"])))
      (is (= "https://example.com" (get-in sub-resp [:headers "Access-Control-Allow-Origin"]))))))

(deftest subscription-missing-query-test
  (testing "subscription endpoint returns error for missing query"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers
                     :enable-subscriptions? true})
          request  {:request-method :get
                    :uri "/graphql/subscriptions"
                    :query-string ""}
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 400 (:status response)))
      (is (str/includes? (get-in body [:errors 0 :message]) "Missing query")))))

(deftest build-lacinia-schema-with-streamers-test
  (testing "build-lacinia-schema compiles schema with subscription streamers"
    (let [schema (ring/build-lacinia-schema resolvers)]
      (is (some? schema))
      (is (map? schema)))))

;; Union type tagging tests

(def ^:private BallPossessed
  [:map {:graphql/type :BallPossessed}
   [:status [:= :possessed]]
   [:holder-id :string]])

(def ^:private BallLoose
  [:map {:graphql/type :BallLoose}
   [:status [:= :loose]]
   [:position [:vector :int]]])

(def ^:private Ball
  [:multi {:dispatch :status :graphql/type :Ball}
   [:possessed BallPossessed]
   [:loose BallLoose]])

(def ^:private GameState
  [:map {:graphql/type :GameState}
   [:turn-number :int]
   [:ball Ball]])

(defresolver :Query :gameState
  "Returns a game state with a possessed ball"
  [:=> [:cat :any :any :any] GameState]
  [_ctx _args _value]
  {:turn-number 1
   :ball {:status :possessed
          :holder-id "player-1"}})

(defresolver :Query :looseBall
  "Returns a game state with a loose ball"
  [:=> [:cat :any :any :any] GameState]
  [_ctx _args _value]
  {:turn-number 2
   :ball {:status :loose
          :position [3 7]}})

(def ^:private union-resolvers
  (merge resolvers
         {[:Query :gameState] [[:=> [:cat :any :any :any] GameState]
                               #'Query-gameState]
          [:Query :looseBall] [[:=> [:cat :any :any :any] GameState]
                               #'Query-looseBall]}))

(deftest graphql-union-type-tagging-test
  (testing "GraphQL query returns correct union type for BallPossessed"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map union-resolvers})
          query    "{ gameState { turnNumber ball { __typename ... on BallPossessed { holderId } } } }"
          request  (make-graphql-request query)
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (nil? (:errors body)) (str "Unexpected errors: " (:errors body)))
      (is (= 1 (get-in body [:data :gameState :turnNumber])))
      (is (= "BallPossessed" (get-in body [:data :gameState :ball :__typename])))
      (is (= "player-1" (get-in body [:data :gameState :ball :holderId])))))

  (testing "GraphQL query returns correct union type for BallLoose"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map union-resolvers})
          query    "{ looseBall { turnNumber ball { __typename ... on BallLoose { position } } } }"
          request  (make-graphql-request query)
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (nil? (:errors body)) (str "Unexpected errors: " (:errors body)))
      (is (= 2 (get-in body [:data :looseBall :turnNumber])))
      (is (= "BallLoose" (get-in body [:data :looseBall :ball :__typename])))
      (is (= [3 7] (get-in body [:data :looseBall :ball :position]))))))
