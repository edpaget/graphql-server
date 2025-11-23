(ns graphql-server.core
  "Core GraphQL server functionality wrapping Lacinia with Malli schemas.

  Provides [[defresolver]] macro for defining GraphQL resolvers with Malli schemas,
  and utilities for collecting and organizing resolvers by namespace.

  ## Defining Resolvers

  Use [[defresolver]] to define Query and Mutation resolvers:

  ```clojure
  (defresolver :Query :users
    \"Fetches all users\"
    [:=> [:cat :any :any :any] [:vector User]]
    [ctx args value]
    (fetch-users))

  (defresolver :Mutation :createUser
    [:=> [:cat :any [:map [:name :string]] :any] User]
    [ctx {:keys [name]} value]
    (create-user name))
  ```

  ## Collecting Resolvers

  Resolvers are defined as vars with metadata. Collect them into a map:

  ```clojure
  ;; Automatic collection at namespace end
  (def-resolver-map)  ; Creates 'resolvers var

  ;; Or collect programmatically
  (def my-resolvers (collect-resolvers 'my.namespace))

  ;; Merge resolvers from multiple namespaces
  (require '[graphql-server.schema :as schema])
  (schema/->graphql-schema (merge resolver-map-1 resolver-map-2))
  ```"
  (:require
   [graphql-server.impl :as impl]
   [malli.core :as mc]))

(defmacro defresolver
  "Defines a GraphQL resolver function with Malli schema validation.

  Creates a var named `object-action` (e.g., `Query-users`, `Mutation-createUser`)
  with metadata indicating it's a GraphQL resolver. The resolver function automatically
  coerces arguments using the provided Malli schema.

  The `object` must be `:Query` or `:Mutation`. The `action` is a keyword naming
  the GraphQL field.

  The schema must be a Malli `:=>` function schema describing a 3-arity function:
  `[:=> [:cat context-schema args-schema value-schema] return-schema]`

  Arguments are automatically coerced and validated against `args-schema`. If validation
  fails, the resolver returns `{:errors validation-errors}`.

  Examples:

      (defresolver :Query :users
        \"Fetches all users\"
        [:=> [:cat :any :any :any] [:vector User]]
        [ctx args value]
        (fetch-all-users))

      (defresolver :Mutation :updateUser
        [:=> [:cat :any [:map [:id :uuid] [:name :string]] :any] User]
        [ctx {:keys [id name]} value]
        (update-user id name))"
  [object action doc-or-schema & args]
  (when-not (contains? #{:Query :Mutation} object)
    (throw (ex-info "object must be :Query or :Mutation"
                    {:object object :valid #{:Query :Mutation}})))
  (when-not (keyword? action)
    (throw (ex-info "action must be a keyword"
                    {:action action})))
  (let [[doc schema body] (if (string? doc-or-schema)
                            [doc-or-schema (first args) (rest args)]
                            [nil doc-or-schema args])
        resolver-sym      (with-meta
                            (symbol (str (name object) "-" (name action)))
                            {:graphql/resolver [object action]
                             :graphql/schema schema
                             :doc doc})]
    `(def ~resolver-sym
       (impl/coerce-args
        (mc/walk ~schema impl/->argument-type)
        (fn ~@body)))))

(defn collect-resolvers
  "Collects all GraphQL resolvers defined in a namespace.

  Scans the namespace for vars with `:graphql/resolver` metadata and returns
  a map suitable for passing to [[graphql-server.schema/->graphql-schema]].

  The map keys are `[object action]` tuples (e.g., `[:Query :users]`) and
  values are `[schema resolver-var]` tuples.

  Example:

      (collect-resolvers 'my.app.resolvers)
      ;=> {[:Query :users] [schema #'my.app.resolvers/Query-users]
      ;    [:Mutation :createUser] [schema #'my.app.resolvers/Mutation-createUser]}"
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (keep (fn [v]
               (when-let [[object action] (:graphql/resolver (meta v))]
                 [[object action]
                  [(:graphql/schema (meta v)) v]])))
       (into {})))

(defn apply-middleware
  "Applies a sequence of middleware functions to wrap resolver functions.

  Middleware functions receive a resolver function and return a wrapped version.
  Each middleware should have the signature: `(fn [resolver] (fn [ctx args value] ...))`.

  Middleware is applied left-to-right, so the first middleware in the vector is
  the outermost wrapper. For example, with `[auth-mw log-mw]`, the call stack is:
  `auth-mw -> log-mw -> resolver`.

  Example:

      (defn auth-middleware [resolver]
        (fn [ctx args value]
          (if (:authenticated? ctx)
            (resolver ctx args value)
            {:errors {:message \"Unauthorized\"}})))

      (def-resolver-map [auth-middleware])"
  [middleware-fns resolver-map]
  (if (seq middleware-fns)
    (let [wrap-resolver (apply comp middleware-fns)]
      (reduce-kv (fn [m k [schema resolver-fn-or-var]]
                   (let [resolver-fn (if (var? resolver-fn-or-var)
                                       @resolver-fn-or-var
                                       resolver-fn-or-var)]
                     (assoc m k [schema (wrap-resolver resolver-fn)])))
                 {}
                 resolver-map))
    resolver-map))

(defmacro def-resolver-map
  "Defines a var named `resolvers` containing all GraphQL resolvers in the current namespace.

  Call this macro at the end of a namespace that defines resolvers with [[defresolver]].
  It scans the namespace and collects all resolver definitions into a map.

  Optionally accepts a docstring and/or a vector of middleware functions. Middleware
  functions wrap each resolver and are applied left-to-right (first middleware is outermost).

  Examples:

      ;; Basic usage
      (def-resolver-map)

      ;; With docstring
      (def-resolver-map \"Resolvers for user operations\")

      ;; With middleware
      (def-resolver-map [auth-middleware logging-middleware])

      ;; With both docstring and middleware
      (def-resolver-map \"Resolvers for user operations\"
        [auth-middleware logging-middleware])"
  [& args]
  (let [[doc middleware] (cond
                           ;; No args
                           (empty? args)
                           [nil nil]

                           ;; One arg - could be docstring or middleware
                           (= 1 (count args))
                           (if (string? (first args))
                             [(first args) nil]
                             [nil (first args)])

                           ;; Two args - docstring and middleware
                           :else
                           [(first args) (second args)])
        ns-sym           (ns-name *ns*)
        def-form         `(def ~'resolvers
                            ~@(when doc [doc])
                            (apply-middleware ~(or middleware [])
                                              (collect-resolvers '~ns-sym)))]
    def-form))
