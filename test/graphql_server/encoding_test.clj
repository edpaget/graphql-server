(ns graphql-server.encoding-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphql-server.impl :as impl]))

(def ^:private Status
  [:enum
   {:graphql/type :Status}
   :test.models.status/ACTIVE
   :test.models.status/INACTIVE])

(def ^:private User
  [:map
   {:graphql/type :User}
   [:user-id :uuid]
   [:user-name :string]
   [:status Status]])

(def ^:private AdminUser
  [:map
   {:graphql/type :AdminUser}
   [:user-id :uuid]
   [:user-name :string]
   [:admin-level :int]])

(def ^:private Person
  [:multi
   {:graphql/type :Person
    :dispatch :type}
   [:user User]
   [:admin AdminUser]])

(deftest encode-transforms-keys-test
  (testing "encode transforms kebab-case keys to camelCase"
    (let [data   {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                  :user-name "Alice"
                  :status :test.models.status/ACTIVE}
          result (impl/encode data User)]
      (is (= #uuid "550e8400-e29b-41d4-a716-446655440000" (:userId result)))
      (is (= "Alice" (:userName result)))
      (is (not (contains? result :user-id)))
      (is (not (contains? result :user-name))))))

(deftest encode-transforms-enums-test
  (testing "encode transforms namespaced keyword enums to strings"
    (let [data   {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                  :user-name "Alice"
                  :status :test.models.status/ACTIVE}
          result (impl/encode data User)]
      (is (= "ACTIVE" (:status result))))))

(deftest merge-tag-with-type-test
  (testing "merge-tag-with-type creates a function that tags data with its concrete type"
    (let [tagger     (impl/merge-tag-with-type Person)
          user-data  {:type :user
                      :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :user-name "Alice"
                      :status :test.models.status/ACTIVE}
          admin-data {:type :admin
                      :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :user-name "Bob"
                      :admin-level 5}]
      (is (= :User (tagger user-data)))
      (is (= :AdminUser (tagger admin-data))))))

(deftest wrap-resolver-with-encoding-test
  (testing "wrap-resolver-with-encoding wraps resolver with encoding and tagging"
    (let [resolver (fn [_ctx _args _value]
                     {:type :user
                      :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :user-name "Alice"
                      :status :test.models.status/ACTIVE})
          wrapped  (impl/wrap-resolver-with-encoding resolver Person)
          result   (wrapped nil nil nil)]
      (is (= #uuid "550e8400-e29b-41d4-a716-446655440000" (:userId result)))
      (is (= "Alice" (:userName result)))
      (is (= "ACTIVE" (:status result)))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta result))))))

  (testing "wrap-resolver-with-encoding works with simple map types"
    (let [resolver (fn [_ctx _args _value]
                     {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :user-name "Alice"
                      :status :test.models.status/ACTIVE})
          wrapped  (impl/wrap-resolver-with-encoding resolver User)
          result   (wrapped nil nil nil)]
      (is (= #uuid "550e8400-e29b-41d4-a716-446655440000" (:userId result)))
      (is (= "Alice" (:userName result)))
      (is (= "ACTIVE" (:status result)))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta result))))))

  (testing "wrap-resolver-with-encoding unwraps :maybe types"
    (let [resolver (fn [_ctx _args _value]
                     {:user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                      :user-name "Alice"
                      :status :test.models.status/ACTIVE})
          wrapped  (impl/wrap-resolver-with-encoding resolver [:maybe User])
          result   (wrapped nil nil nil)]
      (is (= #uuid "550e8400-e29b-41d4-a716-446655440000" (:userId result)))
      (is (= "Alice" (:userName result)))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta result))))))

  (testing "wrap-resolver-with-encoding handles nil for :maybe types"
    (let [resolver (fn [_ctx _args _value] nil)
          wrapped  (impl/wrap-resolver-with-encoding resolver [:maybe User])
          result   (wrapped nil nil nil)]
      (is (nil? result))))

  (testing "wrap-resolver-with-encoding handles nil results"
    (let [resolver (fn [_ctx _args _value] nil)
          wrapped  (impl/wrap-resolver-with-encoding resolver Person)
          result   (wrapped nil nil nil)]
      (is (nil? result))))

  (testing "wrap-resolver-with-encoding handles collection results"
    (let [resolver (fn [_ctx _args _value]
                     [{:type :user
                       :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                       :user-name "Alice"
                       :status :test.models.status/ACTIVE}
                      {:type :admin
                       :user-id #uuid "660e8400-e29b-41d4-a716-446655440000"
                       :user-name "Bob"
                       :admin-level 5}])
          wrapped  (impl/wrap-resolver-with-encoding resolver Person)
          result   (wrapped nil nil nil)]
      (is (= 2 (count result)))
      (is (= "Alice" (:userName (first result))))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta (first result)))))
      (is (= "Bob" (:userName (second result))))
      (is (= :AdminUser (:com.walmartlabs.lacinia.schema/type-name (meta (second result)))))))

  (testing "wrap-resolver-with-encoding tags nested types in response wrapper"
    (let [PageInfo [:map {:graphql/type :PageInfo}
                    [:total :int]
                    [:offset :int]]
          Response [:map {:graphql/type :PeopleResponse}
                    [:data [:vector Person]]
                    [:page-info PageInfo]]
          resolver (fn [_ctx _args _value]
                     {:data [{:type :user
                              :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                              :user-name "Alice"
                              :status :test.models.status/ACTIVE}
                             {:type :admin
                              :user-id #uuid "660e8400-e29b-41d4-a716-446655440000"
                              :user-name "Bob"
                              :admin-level 5}]
                      :page-info {:total 2 :offset 0}})
          wrapped  (impl/wrap-resolver-with-encoding resolver Response)
          result   (wrapped nil nil nil)]
      ;; Top level wrapper is tagged
      (is (= :PeopleResponse (:com.walmartlabs.lacinia.schema/type-name (meta result))))
      ;; Nested items in :data vector are tagged with their concrete types
      (is (= "Alice" (:userName (first (:data result)))))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta (first (:data result))))))
      (is (= :AdminUser (:com.walmartlabs.lacinia.schema/type-name (meta (second (:data result))))))
      ;; Nested pageInfo is tagged
      (is (= :PageInfo (:com.walmartlabs.lacinia.schema/type-name (meta (:pageInfo result))))))))
