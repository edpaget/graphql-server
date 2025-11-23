(ns graphql-server.test-resolvers-with-middleware
  (:require [graphql-server.core :refer [defresolver def-resolver-map]]))

(defn test-middleware
  "Test middleware that adds a :middleware-applied key."
  [resolver]
  (fn [ctx args value]
    (let [result (resolver ctx args value)]
      (assoc result :middleware-applied true))))

(defresolver :Query :withMiddleware
  [:=> [:cat :any :any :any] :string]
  [_ctx _args _value]
  {:result "test"})

(def-resolver-map "Resolvers with middleware" [test-middleware])
