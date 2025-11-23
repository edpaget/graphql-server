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

(defmacro def-resolver-map
  "Defines a var named `resolvers` containing all GraphQL resolvers in the current namespace.

  Call this macro at the end of a namespace that defines resolvers with [[defresolver]].
  It scans the namespace and collects all resolver definitions into a map.

  Example:

      (ns my.app.resolvers
        (:require [graphql-server.core :refer [defresolver def-resolver-map]]))

      (defresolver :Query :users ...)
      (defresolver :Mutation :createUser ...)

      (def-resolver-map)  ; Creates 'resolvers var"
  []
  (let [ns-sym (ns-name *ns*)]
    `(def ~'resolvers
       (collect-resolvers '~ns-sym))))
