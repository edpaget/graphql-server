(ns graphql-server.test-resolvers
  (:require [graphql-server.core :refer [defresolver def-resolver-map]]))

(def User
  "User object schema for testing."
  [:map {:graphql/type :User}
   [:id :uuid]
   [:name :string]])

(defresolver :Query :hello
  [:=> [:cat :any :any :any] :string]
  [_ctx _args _value]
  "world")

(defresolver :Query :greet
  "Returns a greeting"
  [:=> [:cat :any [:map [:name :string]] :any] :string]
  [_ctx {:keys [name]} _value]
  (str "Hello, " name))

(defresolver :Query :users
  [:=> [:cat :any :any :any] [:vector User]]
  [_ctx _args _value]
  [])

(defresolver :Mutation :createUser
  [:=> [:cat :any [:map [:name :string]] :any] User]
  [_ctx _args _value]
  {})

(def-resolver-map)
