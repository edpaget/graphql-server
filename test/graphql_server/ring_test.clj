(ns graphql-server.ring-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [graphql-server.core :refer [defresolver def-resolver-map]]
   [graphql-server.ring :as ring]
   [java-time.api :as t])
  (:import
   [java.io ByteArrayInputStream]))

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

(def-resolver-map)

(defn- string->input-stream
  "Converts a string to an input stream for Ring request body."
  [s]
  (ByteArrayInputStream. (.getBytes s)))

(defn- make-graphql-request
  "Creates a Ring request map for a GraphQL query."
  ([query]
   (make-graphql-request query nil))
  ([query variables]
   (make-graphql-request query variables {}))
  ([query variables extra-keys]
   (merge
    {:request-method :post
     :uri "/graphql"
     :headers {"content-type" "application/json"}
     :body (string->input-stream
            (json/generate-string
             (cond-> {:query query}
               variables (assoc :variables variables))))}
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

(deftest graphql-middleware-invalid-json-test
  (testing "graphql-middleware handles invalid JSON"
    (let [handler  (ring/graphql-middleware
                    passthrough-handler
                    {:resolver-map resolvers})
          request  {:request-method :post
                    :uri "/graphql"
                    :body (string->input-stream "not valid json")}
          response (handler request)
          body     (json/parse-string (:body response) true)]
      (is (= 400 (:status response)))
      (is (some? (:errors body)))
      (is (= "bad-request" (get-in body [:errors 0 :type]))))))

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
      (is (str/includes? (:body response) "graphiql.min.js")))))

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
