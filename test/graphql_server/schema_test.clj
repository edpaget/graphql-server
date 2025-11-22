(ns graphql-server.schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphql-server.schema :as schema]
   [malli.core :as m]))

(deftest simple-query-with-string-return
  (testing "Query field returning string"
    (let [resolver-map {[:Query :hello]
                        [[:=> [:cat :any :any :any] :string]
                         (fn [_ _ _] "world")]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:hello {:type '(non-null String)}}}}}
             result)))))

(deftest simple-query-with-int-return
  (testing "Query field returning int"
    (let [resolver-map {[:Query :count]
                        [[:=> [:cat :any :any :any] :int]
                         (fn [_ _ _] 42)]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:count {:type '(non-null Int)}}}}}
             result)))))

(deftest simple-query-with-boolean-return
  (testing "Query field returning boolean"
    (let [resolver-map {[:Query :isActive]
                        [[:=> [:cat :any :any :any] :boolean]
                         (fn [_ _ _] true)]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:isActive {:type '(non-null Boolean)}}}}}
             result)))))

(deftest simple-query-with-uuid-return
  (testing "Query field returning UUID"
    (let [resolver-map {[:Query :getId]
                        [[:=> [:cat :any :any :any] :uuid]
                         (fn [_ _ _] (random-uuid))]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:getId {:type '(non-null Uuid)}}}}}
             result)))))

(deftest query-with-maybe-return
  (testing "Query field returning optional string"
    (let [resolver-map {[:Query :optionalName]
                        [[:=> [:cat :any :any :any] [:maybe :string]]
                         (fn [_ _ _] nil)]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:optionalName {:type 'String}}}}}
             result)))))

(deftest query-with-vector-return
  (testing "Query field returning list of strings"
    (let [resolver-map {[:Query :tags]
                        [[:=> [:cat :any :any :any] [:vector :string]]
                         (fn [_ _ _] ["tag1" "tag2"])]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:tags {:type '(list (non-null String))}}}}}
             result)))))

(deftest query-with-enum-return
  (testing "Query field returning enum"
    (let [resolver-map {[:Query :status]
                        [[:=> [:cat :any :any :any] [:enum {:graphql/type :Status} :active :inactive :pending]]
                         (fn [_ _ _] :active)]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:status {:type '(non-null :Status)}}}}
              :enums
              {:Status {:values #{"active" "inactive" "pending"}}}}
             result)))))

(deftest query-with-object-return
  (testing "Query field returning custom object type"
    (let [user-schema [:map {:graphql/type :User}
                       [:id :uuid]
                       [:name :string]
                       [:email :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice" :email "alice@example.com"})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :email {:type '(non-null String)}}}}}
             result)))))

(deftest query-with-nested-objects
  (testing "Query field returning object with nested object"
    (let [address-schema [:map {:graphql/type :Address}
                          [:street :string]
                          [:city :string]]
          user-schema [:map {:graphql/type :User}
                       [:id :uuid]
                       [:name :string]
                       [:address address-schema]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid)
                                      :name "Alice"
                                      :address {:street "123 Main" :city "NYC"}})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :address {:type '(non-null :Address)}}}
               :Address
               {:fields
                {:street {:type '(non-null String)}
                 :city {:type '(non-null String)}}}}}
             result)))))

(deftest query-with-interface-return
  (testing "Query field returning interface type"
    (let [node-interface [:map {:graphql/interface :Node}
                          [:id :uuid]]
          resolver-map {[:Query :node]
                        [[:=> [:cat :any :any :any] node-interface]
                         (fn [_ _ _] {:id (random-uuid)})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:node {:type '(non-null :Node)}}}}
              :interfaces
              {:Node
               {:fields
                {:id {:type '(non-null Uuid)}}}}}
             result)))))

(deftest query-with-union-return
  (testing "Query field returning union type"
    (let [user-schema [:map {:graphql/type :User}
                       [:id :uuid]
                       [:name :string]]
          org-schema [:map {:graphql/type :Organization}
                      [:id :uuid]
                      [:orgName :string]]
          actor-schema [:multi {:graphql/type :Actor
                                :dispatch :type}
                        [:user user-schema]
                        [:org org-schema]]
          resolver-map {[:Query :actor]
                        [[:=> [:cat :any :any :any] actor-schema]
                         (fn [_ _ _] {:type :user :id (random-uuid) :name "Alice"})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:actor {:type '(non-null :Actor)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}}}
               :Organization
               {:fields
                {:id {:type '(non-null Uuid)}
                 :orgName {:type '(non-null String)}}}}
              :unions
              {:Actor {:members [:User :Organization]}}}
             result)))))

(deftest query-with-object-implementing-interface
  (testing "Query field returning object that implements interface"
    (let [node-interface [:map {:graphql/interface :Node}
                          [:id :uuid]]
          user-schema [:map {:graphql/type :User
                             :graphql/implements [:Node]}
                       [:id :uuid]
                       [:name :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}}
                :implements [:Node]}}
              :interfaces
              {:Node
               {:fields
                {:id {:type '(non-null Uuid)}}}}}
             result)))))

(deftest query-with-string-args
  (testing "Query field with string argument"
    (let [resolver-map {[:Query :greet]
                        [[:=> [:cat :any [:map [:name :string]] :any] :string]
                         (fn [_ {:keys [name]} _] (str "Hello, " name))]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:greet {:type '(non-null String)
                         :args {:name {:type '(non-null String)}}}}}}}
             result)))))

(deftest query-with-multiple-args
  (testing "Query field with multiple arguments"
    (let [resolver-map {[:Query :search]
                        [[:=> [:cat :any [:map [:query :string] [:limit :int]] :any] [:vector :string]]
                         (fn [_ {:keys [query limit]} _] [])]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:search {:type '(list (non-null String))
                          :args {:query {:type '(non-null String)}
                                 :limit {:type '(non-null Int)}}}}}}}
             result)))))

(deftest query-with-optional-args
  (testing "Query field with optional arguments"
    (let [resolver-map {[:Query :users]
                        [[:=> [:cat :any [:map [:limit [:maybe :int]]] :any] [:vector :string]]
                         (fn [_ {:keys [limit]} _] [])]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:users {:type '(list (non-null String))
                         :args {:limit {:type 'Int}}}}}}}
             result)))))

(deftest mutation-with-input-object
  (testing "Mutation field with input object args"
    (let [resolver-map {[:Mutation :createUser]
                        [[:=> [:cat :any [:map [:input [:map {:graphql/type :CreateUserInput}
                                                        [:name :string]
                                                        [:email :string]]]] :any]
                          [:map {:graphql/type :User}
                           [:id :uuid]
                           [:name :string]
                           [:email :string]]]
                         (fn [_ {:keys [input]} _]
                           (assoc input :id (random-uuid)))]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Mutation
               {:fields
                {:createUser {:type '(non-null :User)
                              :args {:input {:type '(non-null :CreateUserInput)}}}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :email {:type '(non-null String)}}}}
              :input-objects
              {:CreateUserInput
               {:fields
                {:name {:type '(non-null String)}
                 :email {:type '(non-null String)}}}}}
             result)))))

(deftest multiple-queries-and-mutations
  (testing "Schema with multiple queries and mutations"
    (let [resolver-map {[:Query :hello]
                        [[:=> [:cat :any :any :any] :string]
                         (fn [_ _ _] "world")]
                        [:Query :count]
                        [[:=> [:cat :any :any :any] :int]
                         (fn [_ _ _] 42)]
                        [:Mutation :increment]
                        [[:=> [:cat :any :any :any] :int]
                         (fn [_ _ _] 43)]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:hello {:type '(non-null String)}
                 :count {:type '(non-null Int)}}}
               :Mutation
               {:fields
                {:increment {:type '(non-null Int)}}}}}
             result)))))

(deftest query-with-hidden-field
  (testing "Map with hidden field should not appear in GraphQL schema"
    (let [user-schema [:map {:graphql/type :User}
                       [:id :uuid]
                       [:name :string]
                       [:password {:graphql/hidden true} :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice" :password "secret"})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}}}}}
             result)))))

(deftest query-with-instant-field
  (testing "Query field with time/instant type"
    (let [event-schema [:map {:graphql/type :Event}
                        [:id :uuid]
                        [:createdAt :time/instant]]
          resolver-map {[:Query :event]
                        [[:=> [:cat :any :any :any] event-schema]
                         (fn [_ _ _] {:id (random-uuid) :createdAt (java.time.Instant/now)})]}
          result (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:event {:type '(non-null :Event)}}}
               :Event
               {:fields
                {:id {:type '(non-null Uuid)}
                 :createdAt {:type '(non-null Date)}}}}}
             result)))))
