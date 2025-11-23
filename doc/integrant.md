# Using GraphQL Server with Integrant

This guide shows how to integrate `graphql-server` with [Integrant](https://github.com/weavejester/integrant) for component-based application development while maintaining excellent REPL-driven development experience.

## Overview

The `graphql-server` module works seamlessly with Integrant by:

1. **Defining resolvers** that access components via context
2. **Managing schema compilation** as an Integrant component
3. **Injecting components** into the GraphQL execution context

This approach keeps resolvers testable while letting Integrant manage component lifecycles.

## Basic Setup

### 1. Define Resolvers in Namespaces

Create resolver namespaces that define GraphQL queries and mutations:

```clojure
(ns my-app.graphql.user-resolvers
  (:require
   [graphql-server.core :refer [defresolver def-resolver-map]]
   [my-app.db.users :as users]))

;; Define your Malli schemas for GraphQL types
(def User
  [:map {:graphql/type :User}
   [:id :uuid]
   [:name :string]
   [:email :string]])

;; Define resolvers - they access Integrant components via context
(defresolver :Query :users
  "Fetches all users from the database"
  [:=> [:cat :any :any :any] [:vector User]]
  [ctx args value]
  (let [db (:db ctx)]  ; Access db component from context
    (users/fetch-all db)))

(defresolver :Query :user
  "Fetches a single user by ID"
  [:=> [:cat :any [:map [:id :uuid]] :any] User]
  [ctx {:keys [id]} value]
  (let [db (:db ctx)]
    (users/fetch-by-id db id)))

(defresolver :Mutation :createUser
  "Creates a new user"
  [:=> [:cat :any [:map [:name :string] [:email :string]] :any] User]
  [ctx {:keys [name email]} value]
  (let [db (:db ctx)]
    (users/create! db {:name name :email email})))

;; Collect all resolvers from this namespace into a 'resolvers' var
(def-resolver-map)
```

### 2. Configure Integrant System

Set up your Integrant configuration with GraphQL components:

```clojure
(ns my-app.system
  (:require
   [integrant.core :as ig]
   [graphql-server.schema :as gql-schema]
   [com.walmartlabs.lacinia.schema :as lacinia-schema]
   [com.walmartlabs.lacinia :as lacinia]
   [my-app.graphql.user-resolvers :as user-resolvers]
   ;; ... other resolver namespaces
   ))

;; Integrant configuration map
(def config
  {;; Database component (example)
   :db/pool {:database-url "jdbc:postgresql://localhost/mydb"}

   ;; GraphQL schema component - no dependencies
   :graphql/schema {}

   ;; GraphQL executor - depends on schema and components resolvers need
   :graphql/executor {:schema (ig/ref :graphql/schema)
                      :db (ig/ref :db/pool)}})

;; Initialize the GraphQL schema
(defmethod ig/init-key :graphql/schema
  [_ _]
  (let [;; Merge resolver maps from all your resolver namespaces
        resolver-map (merge user-resolvers/resolvers
                           ;; product-resolvers/resolvers
                           ;; order-resolvers/resolvers
                           )
        ;; Generate the Lacinia schema from Malli schemas
        schema (gql-schema/->graphql-schema resolver-map)]
    ;; Compile the schema with Lacinia
    (lacinia-schema/compile schema)))

;; Initialize the executor with components injected into context
(defmethod ig/init-key :graphql/executor
  [_ {:keys [schema db]}]
  ;; Return a function that executes GraphQL queries
  (fn [query variables]
    ;; The context map is where you inject your Integrant components
    ;; Resolvers will access these via the ctx parameter
    (let [context {:db db}]  ; Add all components your resolvers need
      (lacinia/execute schema query variables context))))

;; Optional: halt method (usually not needed for schema)
(defmethod ig/halt-key! :graphql/schema
  [_ _schema]
  ;; Schema is immutable, nothing to clean up
  nil)
```

### 3. Use in Ring Handler

Create a Ring handler that uses the GraphQL executor:

```clojure
(ns my-app.handler
  (:require
   [integrant.core :as ig]
   [ring.util.response :as response]))

(defmethod ig/init-key :handler/graphql
  [_ {:keys [executor]}]
  (fn [request]
    (let [{:keys [query variables]} (:body request)
          result (executor query variables)]
      (-> (response/response result)
          (response/content-type "application/json")))))

;; Add to your config
(def config
  {;; ... other components
   :handler/graphql {:executor (ig/ref :graphql/executor)}})
```

## REPL-Driven Development

One of the key benefits of this approach is excellent REPL support.

### Updating Resolver Implementations

When you redefine a resolver, changes are **immediately available** without restarting:

```clojure
;; 1. Start your system in the REPL
(def system (ig/init config))

;; 2. Test a query
((:graphql/executor system) "{ users { id name } }" nil)
;; => {:data {:users [{:id #uuid "..." :name "Alice"} ...]}}

;; 3. Modify a resolver in your editor
(defresolver :Query :users
  [:=> [:cat :any :any :any] [:vector User]]
  [ctx args value]
  (println "Fetching users...")  ; <-- Added logging
  (let [db (:db ctx)]
    (users/fetch-all db)))

;; 4. Evaluate the resolver form (C-c C-c in CIDER/Calva)
;; The var is redefined immediately

;; 5. Query again - uses the NEW resolver!
((:graphql/executor system) "{ users { id name } }" nil)
;; Prints: "Fetching users..."
;; => {:data {:users [...]}}
```

**Why this works:** `def-resolver-map` captures **var references**, not function values. When you redefine a var, the schema already has a reference to it, so changes propagate automatically.

### What Needs Schema Recompilation

Different types of changes have different requirements:

| Change Type | Requires Restart? | Example |
|-------------|-------------------|---------|
| Resolver implementation | ❌ No - immediate | Change query logic, add logging |
| Resolver signature | ✅ Yes - schema only | Add/remove arguments, change return type |
| Type definitions | ✅ Yes - schema only | Add/remove fields from `User` |
| Component dependencies | ✅ Yes - full system | Add new database pool |

### Restarting Just the Schema

When you need to recompile the schema (after changing type definitions or resolver signatures), you don't need to restart your entire system:

```clojure
;; Helper function to restart just GraphQL components
(defn restart-graphql! [system]
  (-> system
      (ig/halt-key! [:graphql/executor])
      (ig/halt-key! [:graphql/schema])
      (ig/init [:graphql/schema :graphql/executor])))

;; After changing a type definition or resolver signature:
(alter-var-root #'system restart-graphql!)

;; Or with integrant.repl:
(require '[integrant.repl :as ig-repl])
(ig-repl/suspend-resume! [:graphql/schema :graphql/executor])
```

### Using Integrant REPL

For the best development experience, use `integrant.repl`:

```clojure
(ns user
  (:require
   [integrant.repl :as ig-repl]
   [integrant.repl.state :as state]
   [my-app.system :as system]))

;; Set up the configuration
(ig-repl/set-prep! (constantly system/config))

;; Start the system
(ig-repl/go)

;; Access the running system
((:graphql/executor state/system) "{ users { id } }" nil)

;; After changing resolver implementation - nothing needed, just re-eval!

;; After changing schema (types/signatures) - restart GraphQL only:
(ig-repl/suspend-resume! [:graphql/schema :graphql/executor])

;; Full system restart if needed:
(ig-repl/reset)
```

## Best Practices

### 1. Separate Business Logic from Resolvers

Keep your business logic in separate functions for maximum REPL flexibility:

```clojure
(ns my-app.graphql.user-resolvers
  (:require
   [my-app.db.users :as users]  ; <-- Business logic lives here
   [graphql-server.core :refer [defresolver def-resolver-map]]))

;; Business logic - can be redefined anytime
(defn fetch-users-logic [db filters]
  (users/query db (build-query filters)))

;; Resolver just delegates
(defresolver :Query :users
  [:=> [:cat :any [:map [:filter {:optional true} :string]] :any] [:vector User]]
  [ctx args value]
  (fetch-users-logic (:db ctx) (:filter args)))

(def-resolver-map)
```

Now you can iterate on `fetch-users-logic` without even touching the resolver!

### 2. Organize Resolvers by Domain

Group related resolvers into domain-specific namespaces:

```
src/
  my_app/
    graphql/
      user_resolvers.clj      ; User queries and mutations
      product_resolvers.clj   ; Product queries and mutations
      order_resolvers.clj     ; Order queries and mutations
```

### 3. Share Type Definitions

Define shared types in a separate namespace:

```clojure
(ns my-app.graphql.types
  "Shared GraphQL type definitions")

(def User
  [:map {:graphql/type :User}
   [:id :uuid]
   [:name :string]
   [:email :string]])

(def Product
  [:map {:graphql/type :Product}
   [:id :uuid]
   [:name :string]
   [:price :double]])
```

Then require them in your resolver namespaces:

```clojure
(ns my-app.graphql.user-resolvers
  (:require
   [my-app.graphql.types :as types]
   [graphql-server.core :refer [defresolver def-resolver-map]]))

(defresolver :Query :users
  [:=> [:cat :any :any :any] [:vector types/User]]
  [ctx args value]
  ...)
```

### 4. Add Component Validation

Validate that required components are present in the context:

```clojure
(defresolver :Query :users
  [:=> [:cat :any :any :any] [:vector User]]
  [ctx args value]
  (when-not (:db ctx)
    (throw (ex-info "Database component not found in context" {:context-keys (keys ctx)})))
  (let [db (:db ctx)]
    (users/fetch-all db)))
```

### 5. Use Context for Request-Scoped Data

The context is also a good place for request-scoped data:

```clojure
(defmethod ig/init-key :graphql/executor
  [_ {:keys [schema db]}]
  (fn [query variables request]  ; <-- Add request parameter
    (let [context {:db db
                   :current-user (get-in request [:session :user])
                   :request-id (java.util.UUID/randomUUID)}]
      (lacinia/execute schema query variables context))))

;; In your resolvers:
(defresolver :Query :currentUser
  [:=> [:cat :any :any :any] [:maybe User]]
  [ctx args value]
  (:current-user ctx))  ; Access request-scoped user
```

## Testing

Resolvers are easy to test because they're just functions:

```clojure
(ns my-app.graphql.user-resolvers-test
  (:require
   [clojure.test :refer [deftest is]]
   [my-app.graphql.user-resolvers :as resolvers]))

(deftest test-users-resolver
  (let [;; Create a mock context with test components
        mock-db {:users [{:id #uuid "..." :name "Alice"}]}
        ctx {:db mock-db}

        ;; Call the resolver var directly
        result (@#'resolvers/Query-users ctx {} nil)]

    (is (= 1 (count result)))
    (is (= "Alice" (:name (first result))))))
```

## Complete Example

Here's a complete working example:

```clojure
;; src/my_app/graphql/types.clj
(ns my-app.graphql.types)

(def User
  [:map {:graphql/type :User}
   [:id :uuid]
   [:name :string]
   [:email :string]])

;; src/my_app/graphql/user_resolvers.clj
(ns my-app.graphql.user-resolvers
  (:require
   [graphql-server.core :refer [defresolver def-resolver-map]]
   [my-app.graphql.types :as types]
   [my-app.db.users :as users]))

(defresolver :Query :users
  "Fetches all users"
  [:=> [:cat :any :any :any] [:vector types/User]]
  [ctx args value]
  (users/fetch-all (:db ctx)))

(defresolver :Query :user
  "Fetches a user by ID"
  [:=> [:cat :any [:map [:id :uuid]] :any] [:maybe types/User]]
  [ctx {:keys [id]} value]
  (users/fetch-by-id (:db ctx) id))

(defresolver :Mutation :createUser
  "Creates a new user"
  [:=> [:cat :any [:map [:name :string] [:email :string]] :any] types/User]
  [ctx {:keys [name email]} value]
  (users/create! (:db ctx) {:name name :email email}))

(def-resolver-map)

;; src/my_app/system.clj
(ns my-app.system
  (:require
   [integrant.core :as ig]
   [graphql-server.schema :as gql-schema]
   [com.walmartlabs.lacinia.schema :as lacinia-schema]
   [com.walmartlabs.lacinia :as lacinia]
   [my-app.graphql.user-resolvers :as user-resolvers]))

(def config
  {:db/pool {}
   :graphql/schema {}
   :graphql/executor {:schema (ig/ref :graphql/schema)
                      :db (ig/ref :db/pool)}})

(defmethod ig/init-key :graphql/schema [_ _]
  (-> (gql-schema/->graphql-schema user-resolvers/resolvers)
      lacinia-schema/compile))

(defmethod ig/init-key :graphql/executor [_ {:keys [schema db]}]
  (fn [query variables]
    (lacinia/execute schema query variables {:db db})))

;; dev/user.clj
(ns user
  (:require
   [integrant.repl :as ig-repl]
   [integrant.repl.state :as state]
   [my-app.system :as system]))

(ig-repl/set-prep! (constantly system/config))

(comment
  ;; Start the system
  (ig-repl/go)

  ;; Execute a query
  ((:graphql/executor state/system) "{ users { id name email } }" nil)

  ;; After changing resolver implementation - just re-eval!
  ;; After changing schema - restart GraphQL:
  (ig-repl/suspend-resume! [:graphql/schema :graphql/executor])
  )
```

## See Also

- [graphql-server README](../README.md) - Core concepts and basic usage
- [Integrant documentation](https://github.com/weavejester/integrant)
- [Lacinia documentation](https://lacinia.readthedocs.io/)
- [Malli documentation](https://github.com/metosin/malli)
