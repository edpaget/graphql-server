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

(deftest coerce-args-transforms-keys-test
  (testing "coerce-args transforms camelCase keys to kebab-case"
    (let [arg-schema [:map [:user-name :string] [:user-id :uuid]]
          resolver   (fn [_ctx args _value] args)
          wrapped    (impl/coerce-args arg-schema resolver)
          result     (wrapped nil {:userName "Alice"
                                   :userId "550e8400-e29b-41d4-a716-446655440000"} nil)]
      (is (= "Alice" (:user-name result)))
      (is (= #uuid "550e8400-e29b-41d4-a716-446655440000" (:user-id result)))
      (is (not (contains? result :userName)))
      (is (not (contains? result :userId))))))

(deftest coerce-args-transforms-enums-test
  (testing "coerce-args transforms string enum values to namespaced keywords"
    (let [arg-schema [:map [:status Status]]
          resolver   (fn [_ctx args _value] args)
          wrapped    (impl/coerce-args arg-schema resolver)
          result     (wrapped nil {:status "ACTIVE"} nil)]
      (is (= :test.models.status/ACTIVE (:status result))))))

(deftest coerce-args-nested-maps-test
  (testing "coerce-args transforms nested map keys"
    (let [arg-schema [:map
                      [:filter [:map
                                [:min-age :int]
                                [:max-age {:optional true} :int]]]]
          resolver   (fn [_ctx args _value] args)
          wrapped    (impl/coerce-args arg-schema resolver)
          result     (wrapped nil {:filter {:minAge 18 :maxAge 65}} nil)]
      (is (= 18 (get-in result [:filter :min-age])))
      (is (= 65 (get-in result [:filter :max-age]))))))

(deftest coerce-args-validation-error-test
  (testing "coerce-args returns errors on validation failure"
    (let [arg-schema [:map [:user-name :string]]
          resolver   (fn [_ctx args _value] args)
          wrapped    (impl/coerce-args arg-schema resolver)
          result     (wrapped nil {:userName 123} nil)]
      (is (contains? result :errors)))))

(deftest nested-maybe-with-multi-tagging-test
  (testing "tags nested :multi inside :maybe field"
    (let [Container [:map {:graphql/type :Container}
                     [:item [:maybe Person]]]
          resolver  (fn [_ctx _args _value]
                      {:item {:type :user
                              :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                              :user-name "Alice"
                              :status :test.models.status/ACTIVE}})
          wrapped   (impl/wrap-resolver-with-encoding resolver Container)
          result    (wrapped nil nil nil)]
      (is (= :Container (:com.walmartlabs.lacinia.schema/type-name (meta result))))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta (:item result)))))))

  (testing "tags deeply nested :multi inside :maybe -> :map -> :multi"
    (let [Inner     [:map {:graphql/type :Inner}
                     [:person Person]]
          Outer     [:map {:graphql/type :Outer}
                     [:inner [:maybe Inner]]]
          resolver  (fn [_ctx _args _value]
                      {:inner {:person {:type :user
                                        :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                                        :user-name "Alice"
                                        :status :test.models.status/ACTIVE}}})
          wrapped   (impl/wrap-resolver-with-encoding resolver Outer)
          result    (wrapped nil nil nil)]
      (is (= :Outer (:com.walmartlabs.lacinia.schema/type-name (meta result))))
      (is (= :Inner (:com.walmartlabs.lacinia.schema/type-name (meta (:inner result)))))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta (:person (:inner result))))))))

  (testing "tags with :maybe at top level AND nested field containing :multi"
    (let [Inner     [:map {:graphql/type :Inner}
                     [:person Person]]
          Outer     [:map {:graphql/type :Outer}
                     [:inner [:maybe Inner]]]
          resolver  (fn [_ctx _args _value]
                      {:inner {:person {:type :user
                                        :user-id #uuid "550e8400-e29b-41d4-a716-446655440000"
                                        :user-name "Alice"
                                        :status :test.models.status/ACTIVE}}})
          wrapped   (impl/wrap-resolver-with-encoding resolver [:maybe Outer])
          result    (wrapped nil nil nil)]
      (is (= :Outer (:com.walmartlabs.lacinia.schema/type-name (meta result))))
      (is (= :Inner (:com.walmartlabs.lacinia.schema/type-name (meta (:inner result)))))
      (is (= :User (:com.walmartlabs.lacinia.schema/type-name (meta (:person (:inner result)))))))))

(deftest ball-like-multi-tagging-test
  (testing "tags :multi with keyword dispatch (like Ball schema)"
    (let [BallPossessed [:map {:graphql/type :BallPossessed}
                         [:status [:= :possessed]]
                         [:holder-id :string]]
          BallLoose     [:map {:graphql/type :BallLoose}
                         [:status [:= :loose]]
                         [:position [:vector :int]]]
          Ball          [:multi {:dispatch :status :graphql/type :Ball}
                         [:possessed BallPossessed]
                         [:loose BallLoose]]
          GameState     [:map {:graphql/type :GameState}
                         [:ball Ball]]
          GameResponse  [:map {:graphql/type :Game}
                         [:game-state [:maybe GameState]]]
          resolver      (fn [_ctx _args _value]
                          {:game-state {:ball {:status :possessed
                                               :holder-id "player-1"}}})
          wrapped       (impl/wrap-resolver-with-encoding resolver [:maybe GameResponse])
          result        (wrapped nil nil nil)]
      (is (= :Game (:com.walmartlabs.lacinia.schema/type-name (meta result))))
      (is (= :GameState (:com.walmartlabs.lacinia.schema/type-name (meta (:gameState result)))))
      (is (= :BallPossessed (:com.walmartlabs.lacinia.schema/type-name (meta (:ball (:gameState result))))))))

  (testing "tags loose ball variant"
    (let [BallPossessed [:map {:graphql/type :BallPossessed}
                         [:status [:= :possessed]]
                         [:holder-id :string]]
          BallLoose     [:map {:graphql/type :BallLoose}
                         [:status [:= :loose]]
                         [:position [:vector :int]]]
          Ball          [:multi {:dispatch :status :graphql/type :Ball}
                         [:possessed BallPossessed]
                         [:loose BallLoose]]
          GameState     [:map {:graphql/type :GameState}
                         [:ball Ball]]
          GameResponse  [:map {:graphql/type :Game}
                         [:game-state [:maybe GameState]]]
          resolver      (fn [_ctx _args _value]
                          {:game-state {:ball {:status :loose
                                               :position [1 2]}}})
          wrapped       (impl/wrap-resolver-with-encoding resolver [:maybe GameResponse])
          result        (wrapped nil nil nil)]
      (is (= :BallLoose (:com.walmartlabs.lacinia.schema/type-name (meta (:ball (:gameState result))))))))

  (testing "fails to tag when dispatch value is string instead of keyword"
    (let [BallPossessed [:map {:graphql/type :BallPossessed}
                         [:status [:= :possessed]]
                         [:holder-id :string]]
          BallLoose     [:map {:graphql/type :BallLoose}
                         [:status [:= :loose]]
                         [:position [:vector :int]]]
          Ball          [:multi {:dispatch :status :graphql/type :Ball}
                         [:possessed BallPossessed]
                         [:loose BallLoose]]
          GameState     [:map {:graphql/type :GameState}
                         [:ball Ball]]
          GameResponse  [:map {:graphql/type :Game}
                         [:game-state [:maybe GameState]]]
          resolver      (fn [_ctx _args _value]
                          ;; Simulating JSON parse where keywords become strings
                          {:game-state {:ball {:status "possessed"
                                               :holder-id "player-1"}}})
          wrapped       (impl/wrap-resolver-with-encoding resolver [:maybe GameResponse])
          result        (wrapped nil nil nil)]
      ;; This will likely fail - dispatch returns "possessed" but tag-map has :possessed
      (is (nil? (:com.walmartlabs.lacinia.schema/type-name (meta (:ball (:gameState result)))))
          "String dispatch value doesn't match keyword dispatch keys"))))
