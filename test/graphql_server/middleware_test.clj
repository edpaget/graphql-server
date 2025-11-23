(ns graphql-server.middleware-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [graphql-server.core :as core]))

(defn auth-middleware
  "Example middleware that checks authentication."
  [resolver]
  (fn [ctx args value]
    (if (:authenticated? ctx)
      (resolver ctx args value)
      {:errors {:message "Unauthorized"}})))

(defn logging-middleware
  "Example middleware that logs resolver calls."
  [resolver]
  (fn [ctx args value]
    (let [result (resolver ctx args value)]
      (assoc result :logged true))))

(deftest apply-middleware-test
  (testing "apply-middleware with no middleware returns original map"
    (let [resolver-fn  (fn [_ctx _args _value] {:result "test"})
          resolver-map {[:Query :test] [:schema resolver-fn]}
          result       (core/apply-middleware [] resolver-map)]
      (is (= resolver-map result))))

  (testing "apply-middleware wraps resolver with single middleware"
    (let [resolver-fn          (fn [_ctx _args _value] {:result "test"})
          resolver-map         {[:Query :test] [:schema resolver-fn]}
          wrapped              (core/apply-middleware [logging-middleware] resolver-map)
          [_schema wrapped-fn] (get wrapped [:Query :test])
          result               (wrapped-fn nil nil nil)]
      (is (= {:result "test" :logged true} result))))

  (testing "apply-middleware applies multiple middleware left-to-right"
    (let [resolver-fn               (fn [_ctx _args _value] {:result "test"})
          resolver-map              {[:Query :test] [:schema resolver-fn]}
          ;; Auth middleware is first (outermost)
          wrapped-auth-fail         (core/apply-middleware [auth-middleware logging-middleware] resolver-map)
          [_schema wrapped-fn-fail] (get wrapped-auth-fail [:Query :test])
          result-fail               (wrapped-fn-fail {:authenticated? false} nil nil)
          ;; When authenticated, both middleware are applied
          wrapped-auth-ok           (core/apply-middleware [auth-middleware logging-middleware] resolver-map)
          [_schema wrapped-fn-ok]   (get wrapped-auth-ok [:Query :test])
          result-ok                 (wrapped-fn-ok {:authenticated? true} nil nil)]
      ;; Auth middleware blocks call, so logging never happens
      (is (= {:errors {:message "Unauthorized"}} result-fail))
      ;; Auth passes, so logging middleware adds :logged
      (is (= {:result "test" :logged true} result-ok))))

  (testing "apply-middleware wraps multiple resolvers"
    (let [resolver1              (fn [_ctx _args _value] {:result "test1"})
          resolver2              (fn [_ctx _args _value] {:result "test2"})
          resolver-map           {[:Query :test1] [:schema1 resolver1]
                                  [:Mutation :test2] [:schema2 resolver2]}
          wrapped                (core/apply-middleware [logging-middleware] resolver-map)
          [_schema1 wrapped-fn1] (get wrapped [:Query :test1])
          [_schema2 wrapped-fn2] (get wrapped [:Mutation :test2])
          result1                (wrapped-fn1 nil nil nil)
          result2                (wrapped-fn2 nil nil nil)]
      (is (= {:result "test1" :logged true} result1))
      (is (= {:result "test2" :logged true} result2)))))
