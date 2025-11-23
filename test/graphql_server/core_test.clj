(ns graphql-server.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphql-server.core :as core]
   [graphql-server.schema :as schema]
   [graphql-server.test-resolvers :as test-resolvers]
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

(deftest integration-with-schema
  (testing "defresolver works with ->graphql-schema"
    (let [gql-schema (schema/->graphql-schema test-resolvers/resolvers)]
      (is (contains? (:objects gql-schema) :Query))
      (is (contains? (:objects gql-schema) :Mutation))
      (is (contains? (:objects gql-schema) :User))
      (is (contains? (get-in gql-schema [:objects :Query :fields]) :users))
      (is (contains? (get-in gql-schema [:objects :Mutation :fields]) :createUser)))))
