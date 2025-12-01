(ns graphql-server.core
  "Core GraphQL server functionality wrapping Lacinia with Malli schemas.

  Provides [[defresolver]] macro for defining GraphQL resolvers with Malli schemas,
  [[defstreamer]] macro for defining GraphQL subscription streamers, and utilities
  for collecting and organizing resolvers by namespace.

  ## Defining Resolvers

  Use [[defresolver]] to define Query, Mutation, and field resolvers:

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

  ;; Field resolver on a custom object type
  (defresolver :User :fullName
    [:=> [:cat :any :any [:map [:first-name :string] [:last-name :string]]] :string]
    [ctx _args {:keys [first-name last-name]}]
    (str first-name \" \" last-name))
  ```

  ## Defining Subscription Streamers

  Use [[defstreamer]] to define GraphQL subscription streamers. The body must return
  a core.async channel - the macro handles the go-loop and encoding automatically:

  ```clojure
  (defstreamer :Subscription :gameUpdated
    \"Subscribe to game state changes\"
    [:=> [:cat :any [:map [:game-id :uuid]] :any] GameState]
    [ctx {:keys [game-id]}]
    (let [sub-mgr (:subscription-manager ctx)]
      (subscribe! sub-mgr [:game game-id])))
  ```

  ## Collecting Resolvers and Streamers

  Resolvers and streamers are defined as vars with metadata. Collect them into a map:

  ```clojure
  ;; Automatic collection at namespace end
  (def-resolver-map)  ; Creates 'resolvers var with both resolvers and streamers

  ;; Or collect programmatically
  (def my-resolvers (collect-resolvers 'my.namespace))
  (def my-streamers (collect-streamers 'my.namespace))

  ;; Merge resolvers from multiple namespaces
  (require '[graphql-server.schema :as schema])
  (schema/->graphql-schema (merge resolver-map-1 resolver-map-2))
  ```"
  (:require
   [clojure.core.async :as async]
   [graphql-server.impl :as impl]
   [malli.core :as mc]))

(defmacro defresolver
  "Defines a GraphQL resolver function with Malli schema validation.

  Creates a var named `object-action` (e.g., `Query-users`, `Deck-cards`)
  with metadata indicating it's a GraphQL resolver. The resolver function automatically
  coerces arguments using the provided Malli schema.

  The `object` is a keyword naming the GraphQL object type (`:Query`, `:Mutation`,
  or any custom object type like `:User`, `:Deck`). The `action` is a keyword naming
  the GraphQL field.

  The schema must be a Malli `:=>` function schema describing a 3-arity function:
  `[:=> [:cat context-schema args-schema value-schema] return-schema]`

  For field resolvers on custom object types, the `value-schema` describes the parent
  object passed by Lacinia, allowing you to extract data from it.

  Arguments are automatically coerced and validated against `args-schema`. If validation
  fails, the resolver returns `{:errors validation-errors}`.

  Examples:

      ;; Query resolver
      (defresolver :Query :users
        \"Fetches all users\"
        [:=> [:cat :any :any :any] [:vector User]]
        [ctx args value]
        (fetch-all-users))

      ;; Mutation resolver
      (defresolver :Mutation :updateUser
        [:=> [:cat :any [:map [:id :uuid] [:name :string]] :any] User]
        [ctx {:keys [id name]} value]
        (update-user id name))

      ;; Field resolver on custom object type
      (defresolver :Deck :cards
        \"Resolves cards for a deck\"
        [:=> [:cat :any :any [:map [:card-slugs [:vector :string]]]] [:vector Card]]
        [ctx _args {:keys [card-slugs]}]
        (fetch-cards card-slugs))"
  [object action doc-or-schema & args]
  (when-not (keyword? object)
    (throw (ex-info "object must be a keyword"
                    {:object object})))
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
       (impl/wrap-resolver-with-encoding
        (impl/coerce-args
         (mc/walk ~schema impl/->argument-type)
         (fn ~@body))
        (mc/walk ~schema impl/->return-type)))))

(defmacro defstreamer
  "Defines a GraphQL subscription streamer with Malli schema validation.

  Creates a var named `Subscription-action` (e.g., `Subscription-gameUpdated`)
  with metadata indicating it's a GraphQL streamer. The streamer function automatically
  coerces arguments and encodes streamed values using the provided Malli schema.

  The body must return a core.async channel. The macro handles:
  - Setting up the go-loop to pump values from the channel to the source-stream
  - Encoding each value using the return type schema (camelCase, enums, type tags)
  - Cleanup when the channel closes or subscription ends

  The schema must be a Malli `:=>` function schema:
  `[:=> [:cat context-schema args-schema :any] return-schema]`

  Note: The third element of the `:cat` is ignored for streamers (there's no parent value).
  The return-schema describes the type of each streamed value.

  Arguments are automatically coerced and validated against `args-schema`. If validation
  fails, the streamer returns `{:errors validation-errors}` before subscribing.

  Examples:

      ;; Simple subscription
      (defstreamer :Subscription :messageAdded
        \"Subscribe to new messages\"
        [:=> [:cat :any :any :any] Message]
        [ctx _args]
        (subscribe! (:sub-mgr ctx) [:messages]))

      ;; Subscription with arguments
      (defstreamer :Subscription :gameUpdated
        [:=> [:cat :any [:map [:game-id :uuid]] :any] GameState]
        [ctx {:keys [game-id]}]
        (subscribe! (:sub-mgr ctx) [:game game-id]))

      ;; With transform using core.async primitives
      (defstreamer :Subscription :lobbyUpdated
        [:=> [:cat :any :any :any] Game]
        [ctx _args]
        (let [raw-ch (subscribe! (:sub-mgr ctx) [:lobby])]
          (async/pipe raw-ch (async/chan 10 (map :data)))))"
  [object action doc-or-schema & args]
  (when-not (= :Subscription object)
    (throw (ex-info "defstreamer object must be :Subscription"
                    {:object object})))
  (when-not (keyword? action)
    (throw (ex-info "action must be a keyword"
                    {:action action})))
  (let [[doc schema body]          (if (string? doc-or-schema)
                                     [doc-or-schema (first args) (rest args)]
                                     [nil doc-or-schema args])
        streamer-sym               (with-meta
                                     (symbol (str (name object) "-" (name action)))
                                     {:graphql/streamer [object action]
                                      :graphql/schema schema
                                      :doc doc})
        ;; Extract just the args binding from the 2-element body args vector
        [ctx-binding args-binding] (first body)
        user-body                  (rest body)]
    `(def ~streamer-sym
       (let [arg-schema#    (mc/walk ~schema impl/->argument-type)
             return-schema# (mc/walk ~schema impl/->return-type)
             encode-fn#     (impl/make-stream-encoder return-schema#)
             coerce#        (mc/coercer arg-schema#
                                        (malli.transform/transformer
                                         (malli.transform/key-transformer
                                          {:decode camel-snake-kebab.core/->kebab-case-keyword})
                                         malli.transform/string-transformer))]
         (fn [~ctx-binding args# source-stream#]
           (let [coerced# (coerce# (or args# {}))]
             (if-not (mc/validate arg-schema# coerced#)
               {:errors (malli.error/humanize (mc/explain arg-schema# coerced#))}
               (let [~args-binding coerced#
                     ch#           (do ~@user-body)]
                 (async/go-loop []
                   (if-let [value# (async/<! ch#)]
                     (do (source-stream# (encode-fn# value#))
                         (recur))
                     (source-stream# nil)))
                 #(async/close! ch#)))))))))

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

(defn collect-streamers
  "Collects all GraphQL subscription streamers defined in a namespace.

  Scans the namespace for vars with `:graphql/streamer` metadata and returns
  a map suitable for passing to [[graphql-server.schema/->graphql-schema]].

  The map keys are `[object action]` tuples (e.g., `[:Subscription :gameUpdated]`)
  and values are `[schema streamer-var]` tuples.

  Example:

      (collect-streamers 'my.app.subscriptions)
      ;=> {[:Subscription :gameUpdated] [schema #'my.app.subscriptions/Subscription-gameUpdated]}"
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (keep (fn [v]
               (when-let [[object action] (:graphql/streamer (meta v))]
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
  "Defines a var named `resolvers` containing all GraphQL resolvers and streamers
  in the current namespace.

  Call this macro at the end of a namespace that defines resolvers with [[defresolver]]
  and/or streamers with [[defstreamer]]. It scans the namespace and collects all
  resolver and streamer definitions into a single map suitable for schema generation.

  Optionally accepts a docstring and/or a vector of middleware functions. Middleware
  functions wrap each resolver and are applied left-to-right (first middleware is outermost).
  Note: Middleware is only applied to resolvers, not streamers.

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
                            (merge
                             (apply-middleware ~(or middleware [])
                                               (collect-resolvers '~ns-sym))
                             (collect-streamers '~ns-sym)))]
    def-form))
