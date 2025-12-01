(ns graphql-server.schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphql-server.schema :as schema]
   [malli.core :as mc]
   [malli.experimental.time :as met]
   [malli.registry :as mr]))

(mr/set-default-registry!
 (mr/composite-registry
  (mc/default-schemas)
  (met/schemas)))

(deftest simple-query-with-string-return
  (testing "Query field returning string"
    (let [resolver-map {[:Query :hello]
                        [[:=> [:cat :any :any :any] :string]
                         (fn [_ _ _] "world")]}
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:getId {:type '(non-null Uuid)}}}}}
             result)))))

(deftest simple-query-with-double-return
  (testing "Query field returning double"
    (let [resolver-map {[:Query :getPrice]
                        [[:=> [:cat :any :any :any] :double]
                         (fn [_ _ _] 99.99)]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:getPrice {:type '(non-null Float)}}}}}
             result)))))

(deftest simple-query-with-float-return
  (testing "Query field returning float"
    (let [resolver-map {[:Query :getRating]
                        [[:=> [:cat :any :any :any] :float]
                         (fn [_ _ _] 4.5)]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:getRating {:type '(non-null Float)}}}}}
             result)))))

(deftest query-with-maybe-return
  (testing "Query field returning optional string"
    (let [resolver-map {[:Query :optionalName]
                        [[:=> [:cat :any :any :any] [:maybe :string]]
                         (fn [_ _ _] nil)]}
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:status {:type '(non-null :Status)}}}}
              :enums
              {:Status {:values #{"ACTIVE" "INACTIVE" "PENDING"}}}}
             result)))))

(deftest query-with-enum-without-type
  (testing "Enum without :graphql/type throws error"
    (let [resolver-map {[:Query :status]
                        [[:=> [:cat :any :any :any] [:enum :active :inactive :pending]]
                         (fn [_ _ _] :active)]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"enum schemas must have :graphql/type property"
                            (schema/->graphql-schema resolver-map))))))

(deftest query-with-object-return
  (testing "Query field returning custom object type"
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:name :string]
                        [:email :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice" :email "alice@example.com"})]}
          result       (schema/->graphql-schema resolver-map)]
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
          user-schema    [:map {:graphql/type :User}
                          [:id :uuid]
                          [:name :string]
                          [:address address-schema]]
          resolver-map   {[:Query :user]
                          [[:=> [:cat :any :any :any] user-schema]
                           (fn [_ _ _] {:id (random-uuid)
                                        :name "Alice"
                                        :address {:street "123 Main" :city "NYC"}})]}
          result         (schema/->graphql-schema resolver-map)]
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
          resolver-map   {[:Query :node]
                          [[:=> [:cat :any :any :any] node-interface]
                           (fn [_ _ _] {:id (random-uuid)})]}
          result         (schema/->graphql-schema resolver-map)]
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
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:type [:= :user]]
                        [:name :string]]
          org-schema   [:map {:graphql/type :Organization}
                        [:id :uuid]
                        [:type [:= :org]]
                        [:orgName :string]]
          actor-schema [:multi {:graphql/type :Actor
                                :dispatch :type}
                        [:user user-schema]
                        [:org org-schema]]
          resolver-map {[:Query :actor]
                        [[:=> [:cat :any :any :any] actor-schema]
                         (fn [_ _ _] {:type :user :id (random-uuid) :name "Alice"})]}
          result       (schema/->graphql-schema resolver-map)]
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
          user-schema    [:map {:graphql/type :User
                                :graphql/implements [node-interface]}
                          [:id :uuid]
                          [:name :string]]
          resolver-map   {[:Query :user]
                          [[:=> [:cat :any :any :any] user-schema]
                           (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result         (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
                         (fn [_ {:keys [_query _limit]} _] [])]}
          result       (schema/->graphql-schema resolver-map)]
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
                         (fn [_ {:keys [_limit]} _] [])]}
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
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
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:name :string]
                        [:password {:graphql/hidden true} :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice" :password "secret"})]}
          result       (schema/->graphql-schema resolver-map)]
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
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:event {:type '(non-null :Event)}}}
               :Event
               {:fields
                {:id {:type '(non-null Uuid)}
                 :createdAt {:type '(non-null Date)}}}}}
             result)))))

(deftest mutation-with-nested-input-objects
  (testing "Mutation field with nested input objects"
    (let [resolver-map {[:Mutation :createPost]
                        [[:=> [:cat :any
                               [:map [:input [:map {:graphql/type :CreatePostInput}
                                              [:title :string]
                                              [:author [:map {:graphql/type :AuthorInput}
                                                        [:name :string]
                                                        [:email :string]]]]]]
                               :any]
                          [:map {:graphql/type :Post}
                           [:id :uuid]
                           [:title :string]]]
                         (fn [_ {:keys [input]} _]
                           {:id (random-uuid) :title (:title input)})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Mutation
               {:fields
                {:createPost {:type '(non-null :Post)
                              :args {:input {:type '(non-null :CreatePostInput)}}}}}
               :Post
               {:fields
                {:id {:type '(non-null Uuid)}
                 :title {:type '(non-null String)}}}}
              :input-objects
              {:CreatePostInput
               {:fields
                {:title {:type '(non-null String)}
                 :author {:type '(non-null :AuthorInput)}}}
               :AuthorInput
               {:fields
                {:name {:type '(non-null String)}
                 :email {:type '(non-null String)}}}}}
             result)))))

(deftest query-with-field-description
  (testing "Query field with description from tuple"
    (let [resolver-map {[:Query :hello]
                        [[:=> [:cat :any :any :any] :string]
                         (fn [_ _ _] "world")
                         "Returns a greeting"]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:hello {:type '(non-null String)
                         :description "Returns a greeting"}}}}}
             result)))))

(deftest query-with-object-description
  (testing "Object type with :graphql/description"
    (let [user-schema  [:map {:graphql/type :User
                              :graphql/description "A user in the system"}
                        [:id :uuid]
                        [:name :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}}
                :description "A user in the system"}}}
             result)))))

(deftest query-with-field-property-description
  (testing "Map field with :graphql/description property"
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:name {:graphql/description "The user's full name"} :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)
                        :description "The user's full name"}}}}}
             result)))))

(deftest query-with-enum-description
  (testing "Enum with :graphql/description"
    (let [resolver-map {[:Query :status]
                        [[:=> [:cat :any :any :any]
                          [:enum {:graphql/type :Status
                                  :graphql/description "User account status"}
                           :active :inactive :pending]]
                         (fn [_ _ _] :active)]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:status {:type '(non-null :Status)}}}}
              :enums
              {:Status {:values #{"ACTIVE" "INACTIVE" "PENDING"}
                        :description "User account status"}}}
             result)))))

(deftest query-with-interface-description
  (testing "Interface with :graphql/description"
    (let [node-interface [:map {:graphql/interface :Node
                                :graphql/description "Object with unique identifier"}
                          [:id :uuid]]
          resolver-map   {[:Query :node]
                          [[:=> [:cat :any :any :any] node-interface]
                           (fn [_ _ _] {:id (random-uuid)})]}
          result         (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:node {:type '(non-null :Node)}}}}
              :interfaces
              {:Node
               {:fields
                {:id {:type '(non-null Uuid)}}
                :description "Object with unique identifier"}}}
             result)))))

(deftest query-with-optional-object-field
  (testing "Object with optional field becomes nullable in GraphQL"
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:name :string]
                        [:nickname {:optional true} :string]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :nickname {:type 'String}}}}}
             result)))))

(deftest query-with-optional-nested-object-field
  (testing "Object with optional nested object field becomes nullable"
    (let [address-schema [:map {:graphql/type :Address}
                          [:street :string]
                          [:city :string]]
          user-schema    [:map {:graphql/type :User}
                          [:id :uuid]
                          [:name :string]
                          [:address {:optional true} address-schema]]
          resolver-map   {[:Query :user]
                          [[:=> [:cat :any :any :any] user-schema]
                           (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result         (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :address {:type :Address}}}
               :Address
               {:fields
                {:street {:type '(non-null String)}
                 :city {:type '(non-null String)}}}}}
             result)))))

(deftest query-with-optional-and-maybe-field
  (testing "Optional field with :maybe type stays nullable (no double unwrap)"
    (let [user-schema  [:map {:graphql/type :User}
                        [:id :uuid]
                        [:name :string]
                        [:bio {:optional true} [:maybe :string]]]
          resolver-map {[:Query :user]
                        [[:=> [:cat :any :any :any] user-schema]
                         (fn [_ _ _] {:id (random-uuid) :name "Alice"})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:user {:type '(non-null :User)}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}
                 :bio {:type 'String}}}}}
             result)))))

(deftest mutation-with-optional-input-field
  (testing "Input object with optional field becomes nullable"
    (let [resolver-map {[:Mutation :createUser]
                        [[:=> [:cat :any [:map [:input [:map {:graphql/type :CreateUserInput}
                                                        [:name :string]
                                                        [:email {:optional true} :string]]]] :any]
                          [:map {:graphql/type :User}
                           [:id :uuid]
                           [:name :string]]]
                         (fn [_ {:keys [input]} _]
                           (assoc input :id (random-uuid)))]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Mutation
               {:fields
                {:createUser {:type '(non-null :User)
                              :args {:input {:type '(non-null :CreateUserInput)}}}}}
               :User
               {:fields
                {:id {:type '(non-null Uuid)}
                 :name {:type '(non-null String)}}}}
              :input-objects
              {:CreateUserInput
               {:fields
                {:name {:type '(non-null String)}
                 :email {:type 'String}}}}}
             result)))))

(deftest query-with-enum-argument
  (testing "Query field with enum argument adds enum to schema"
    (let [status-enum  [:enum {:graphql/type :Status} :active :inactive :pending]
          resolver-map {[:Query :usersByStatus]
                        [[:=> [:cat :any [:map [:status status-enum]] :any] [:vector :string]]
                         (fn [_ {:keys [_status]} _] [])]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:usersByStatus {:type '(list (non-null String))
                                 :args {:status {:type '(non-null :Status)}}}}}}
              :enums
              {:Status {:values #{"ACTIVE" "INACTIVE" "PENDING"}}}}
             result))))

  (testing "Query field with optional enum argument"
    (let [status-enum  [:enum {:graphql/type :Status} :active :inactive :pending]
          resolver-map {[:Query :users]
                        [[:=> [:cat :any [:map [:status {:optional true} status-enum]] :any] [:vector :string]]
                         (fn [_ {:keys [_status]} _] [])]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:users {:type '(list (non-null String))
                         :args {:status {:type :Status}}}}}}
              :enums
              {:Status {:values #{"ACTIVE" "INACTIVE" "PENDING"}}}}
             result))))

  (testing "Multiple enums in arguments and return types are all included"
    (let [status-enum   [:enum {:graphql/type :Status} :active :inactive :pending]
          role-enum     [:enum {:graphql/type :Role} :admin :user :guest]
          priority-enum [:enum {:graphql/type :Priority} :low :medium :high]
          resolver-map  {[:Query :usersByStatus]
                         [[:=> [:cat :any [:map [:status status-enum] [:role role-enum]] :any] priority-enum]
                          (fn [_ _ _] :medium)]}
          result        (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:usersByStatus {:type '(non-null :Priority)
                                 :args {:status {:type '(non-null :Status)}
                                        :role {:type '(non-null :Role)}}}}}}}
             (select-keys result [:objects])))
      (is (= {:Status {:values #{"ACTIVE" "INACTIVE" "PENDING"}}
              :Role {:values #{"ADMIN" "USER" "GUEST"}}
              :Priority {:values #{"LOW" "MEDIUM" "HIGH"}}}
             (:enums result))))))

(deftest or-schema-with-graphql-scalar
  (testing ":or schema with :graphql/scalar maps to specified scalar type"
    (let [datetime-schema [:or {:graphql/scalar :Date}
                           [:re #"\d{4}-\d{2}-\d{2}"]
                           inst?]
          event-schema    [:map {:graphql/type :Event}
                           [:id :uuid]
                           [:occurredAt datetime-schema]]
          resolver-map    {[:Query :event]
                           [[:=> [:cat :any :any :any] event-schema]
                            (fn [_ _ _] {:id (random-uuid) :occurredAt "2024-01-15"})]}
          result          (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:event {:type '(non-null :Event)}}}
               :Event
               {:fields
                {:id {:type '(non-null Uuid)}
                 :occurredAt {:type '(non-null Date)}}}}}
             result)))))

(deftest or-schema-optional-with-graphql-scalar
  (testing "Optional :or schema with :graphql/scalar becomes nullable"
    (let [datetime-schema [:or {:graphql/scalar :Date}
                           [:re #"\d{4}-\d{2}-\d{2}"]
                           inst?]
          event-schema    [:map {:graphql/type :Event}
                           [:id :uuid]
                           [:deletedAt {:optional true} datetime-schema]]
          resolver-map    {[:Query :event]
                           [[:=> [:cat :any :any :any] event-schema]
                            (fn [_ _ _] {:id (random-uuid)})]}
          result          (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:event {:type '(non-null :Event)}}}
               :Event
               {:fields
                {:id {:type '(non-null Uuid)}
                 :deletedAt {:type 'Date}}}}}
             result)))))

(deftest or-schema-maybe-with-graphql-scalar
  (testing ":maybe wrapping :or schema with :graphql/scalar becomes nullable"
    (let [datetime-schema [:or {:graphql/scalar :Date}
                           [:re #"\d{4}-\d{2}-\d{2}"]
                           inst?]
          event-schema    [:map {:graphql/type :Event}
                           [:id :uuid]
                           [:deletedAt [:maybe datetime-schema]]]
          resolver-map    {[:Query :event]
                           [[:=> [:cat :any :any :any] event-schema]
                            (fn [_ _ _] {:id (random-uuid)})]}
          result          (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:event {:type '(non-null :Event)}}}
               :Event
               {:fields
                {:id {:type '(non-null Uuid)}
                 :deletedAt {:type 'Date}}}}}
             result)))))

(deftest or-schema-without-graphql-scalar-throws
  (testing ":or schema without :graphql/scalar throws error"
    (let [bad-schema   [:or [:re #"\d+"] :string]
          resolver-map {[:Query :value]
                        [[:=> [:cat :any :any :any] bad-schema]
                         (fn [_ _ _] "123")]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":or schemas must have :graphql/scalar property"
                            (schema/->graphql-schema resolver-map))))))

;; =============================================================================
;; JSON scalar tests
;; =============================================================================

(deftest map-of-returns-json-scalar
  (testing ":map-of type maps to Json scalar"
    (let [config-schema [:map {:graphql/type :Config}
                         [:id :uuid]
                         [:settings [:map-of :string :string]]]
          resolver-map  {[:Query :config]
                         [[:=> [:cat :any :any :any] config-schema]
                          (fn [_ _ _] {:id (random-uuid) :settings {"key" "value"}})]}
          result        (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:config {:type '(non-null :Config)}}}
               :Config
               {:fields
                {:id {:type '(non-null Uuid)}
                 :settings {:type '(non-null Json)}}}}}
             result)))))

(deftest tuple-returns-json-scalar
  (testing ":tuple type maps to Json scalar"
    (let [point-schema [:map {:graphql/type :Point}
                        [:id :uuid]
                        [:coordinates [:tuple :int :int]]]
          resolver-map {[:Query :point]
                        [[:=> [:cat :any :any :any] point-schema]
                         (fn [_ _ _] {:id (random-uuid) :coordinates [10 20]})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:point {:type '(non-null :Point)}}}
               :Point
               {:fields
                {:id {:type '(non-null Uuid)}
                 :coordinates {:type '(non-null Json)}}}}}
             result)))))

(deftest bare-map-returns-json-scalar
  (testing "Bare :map without fields maps to Json scalar"
    (let [data-schema  [:map {:graphql/type :Data}
                        [:id :uuid]
                        [:metadata :map]]
          resolver-map {[:Query :data]
                        [[:=> [:cat :any :any :any] data-schema]
                         (fn [_ _ _] {:id (random-uuid) :metadata {:foo "bar"}})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:data {:type '(non-null :Data)}}}
               :Data
               {:fields
                {:id {:type '(non-null Uuid)}
                 :metadata {:type '(non-null Json)}}}}}
             result)))))

(deftest keyword-returns-string-scalar
  (testing ":keyword type maps to String scalar"
    (let [item-schema  [:map {:graphql/type :Item}
                        [:id :uuid]
                        [:status :keyword]]
          resolver-map {[:Query :item]
                        [[:=> [:cat :any :any :any] item-schema]
                         (fn [_ _ _] {:id (random-uuid) :status :active})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:item {:type '(non-null :Item)}}}
               :Item
               {:fields
                {:id {:type '(non-null Uuid)}
                 :status {:type '(non-null String)}}}}}
             result)))))

(deftest optional-map-of-returns-nullable-json
  (testing "Optional :map-of becomes nullable Json"
    (let [config-schema [:map {:graphql/type :Config}
                         [:id :uuid]
                         [:settings {:optional true} [:map-of :string :string]]]
          resolver-map  {[:Query :config]
                         [[:=> [:cat :any :any :any] config-schema]
                          (fn [_ _ _] {:id (random-uuid)})]}
          result        (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:config {:type '(non-null :Config)}}}
               :Config
               {:fields
                {:id {:type '(non-null Uuid)}
                 :settings {:type 'Json}}}}}
             result)))))

(deftest optional-tuple-returns-nullable-json
  (testing "Optional :tuple becomes nullable Json"
    (let [point-schema [:map {:graphql/type :Point}
                        [:id :uuid]
                        [:coordinates {:optional true} [:tuple :int :int]]]
          resolver-map {[:Query :point]
                        [[:=> [:cat :any :any :any] point-schema]
                         (fn [_ _ _] {:id (random-uuid)})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query
               {:fields
                {:point {:type '(non-null :Point)}}}
               :Point
               {:fields
                {:id {:type '(non-null Uuid)}
                 :coordinates {:type 'Json}}}}}
             result)))))

(deftest tuple-with-graphql-scalar-uses-custom-type
  (testing ":tuple with :graphql/scalar uses custom scalar type"
    (let [hex-position [:tuple {:graphql/scalar :HexPosition} :int :int]
          point-schema [:map {:graphql/type :Point}
                        [:id :uuid]
                        [:position hex-position]]
          resolver-map {[:Query :point]
                        [[:=> [:cat :any :any :any] point-schema]
                         (fn [_ _ _] {:id (random-uuid) :position [1 2]})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query {:fields {:point {:type '(non-null :Point)}}}
               :Point {:fields {:id {:type '(non-null Uuid)}
                                :position {:type '(non-null HexPosition)}}}}}
             result)))))

(deftest optional-tuple-with-graphql-scalar-is-nullable
  (testing "Optional :tuple with :graphql/scalar becomes nullable custom type"
    (let [hex-position [:tuple {:graphql/scalar :HexPosition} :int :int]
          point-schema [:map {:graphql/type :Point}
                        [:id :uuid]
                        [:position {:optional true} hex-position]]
          resolver-map {[:Query :point]
                        [[:=> [:cat :any :any :any] point-schema]
                         (fn [_ _ _] {:id (random-uuid)})]}
          result       (schema/->graphql-schema resolver-map)]
      (is (= {:objects
              {:Query {:fields {:point {:type '(non-null :Point)}}}
               :Point {:fields {:id {:type '(non-null Uuid)}
                                :position {:type 'HexPosition}}}}}
             result)))))
