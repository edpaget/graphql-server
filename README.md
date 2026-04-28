# graphql-server

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A Clojure library for defining GraphQL schemas using Malli, wrapping Lacinia with Ring support, designed to be REPL friendly. 

## What it is

`graphql-server` lets you define a GraphQL server in Clojure where Malli
schemas are the source of truth. Resolvers are defined as plain functions
with Malli schemas; the library extracts type metadata from those
schemas, generates a Lacinia schema, and exposes the whole thing as Ring
middleware (with built-in GraphiQL and SSE subscriptions).

## Installation

### Stable release (Clojars)

Once published to Clojars, add to your `deps.edn`:

```clojure
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.1"}}}
```

This project follows [semantic versioning](https://semver.org/). 

### Snapshot stream (GitHub Packages)

If you want to track `main` between releases, consume the `-SNAPSHOT`
artifact published to GitHub Packages on every CI-green push:

```clojure
;; deps.edn
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.2-SNAPSHOT"}}
 :mvn/repos
 {"github-graphql-server"
  {:url "https://maven.pkg.github.com/edpaget/graphql-server"}}}
```

GitHub Packages requires authentication even for public reads. Configure
`~/.m2/settings.xml` with a personal access token (scope `read:packages`)
following GitHub's [docs](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages).

## Usage

The example below defines a `Node` interface, a `User` object that implements it, 
a query, a validated mutation, and a subscription — everything the public API exposes.

```clojure
(ns example.users
  (:require
   [clojure.core.async :as async]
   [graphql-server.core :refer [defresolver defstreamer def-resolver-map]]
   [graphql-server.subscriptions :as subs]))

;; Interface — any type with a globally unique :id.
(def Node
  [:map {:graphql/interface :Node}
   [:id :uuid]])

;; Object type implementing Node. Note: :graphql/implements takes the
;; schema var, not the :Node keyword.
(def User
  [:map {:graphql/type       :User
         :graphql/implements [Node]}
   [:id    :uuid]
   [:name  :string]
   [:email :string]])

(defonce !users (atom {}))
(defonce sub-mgr (subs/create-subscription-manager))

(defresolver :Query :user
  "Fetch a user by id."
  [:=> [:cat :any [:map [:id :uuid]] :any] [:maybe User]]
  [_ctx {:keys [id]} _value]
  (get @!users id))

(defresolver :Mutation :createUser
  "Create a new user. Args are coerced and validated against the schema."
  [:=> [:cat :any
        [:map [:input [:map {:graphql/type :CreateUserInput}
                       [:name :string]
                       [:email :string]]]]
        :any]
   User]
  [_ctx {:keys [input]} _value]
  (let [user (assoc input :id (random-uuid))]
    (swap! !users assoc (:id user) user)
    (subs/publish! sub-mgr [:user (:id user)] {:type :update :data user})
    user))

(defstreamer :Subscription :userUpdated
  "Stream updates for a single user."
  [:=> [:cat :any [:map [:id :uuid]] :any] User]
  [_ctx {:keys [id]}]
  (async/pipe (subs/subscribe! sub-mgr [:user id])
              (async/chan 10 (map :data))))

;; Scans this namespace for the vars above and binds `resolvers`.
(def-resolver-map)
```

### Mounting on Ring

`graphql-middleware` is a Ring middleware. The middleware provides
GraphiQL on `GET /graphql`, accepts queries on `POST /graphql`, and
(when enabled) streams subscriptions over SSE on `GET /graphql/subscriptions`.

```clojure
(ns example.server
  (:require
   [example.users :as users]
   [graphql-server.ring :as gql]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.middleware.json :refer [wrap-json-body]]))

(defn- not-found [_req] {:status 404 :body "not found"})

(def app
  (-> not-found
      (gql/graphql-middleware
       {:resolver-map         users/resolvers
        :enable-graphiql?     true
        :enable-subscriptions? true
        :context-fn           (fn [req]
                                {:request              req
                                 :subscription-manager users/sub-mgr})})
      (wrap-json-body {:keywords? true})))

(defn -main [& _]
  (run-jetty app {:port 3000 :join? false}))
```

Visit <http://localhost:3000/graphql> for GraphiQL.

## The def forms

Three macros and a handful of Malli properties cover the surface area
shown in the example.

### `defresolver`

```clojure
(defresolver :Object :field
  "Optional docstring."
  [:=> [:cat ctx-schema args-schema value-schema] return-schema]
  [ctx args value]
  body)
```

- `:Object` is the GraphQL parent type — `:Query`, `:Mutation`, or any
  custom object whose fields you're resolving (e.g. `:User`).
- `:field` is the GraphQL field name; it is camelCased automatically.
- The Malli `:=>` schema describes a 3-arity function. `args-schema`
  is usually a `:map`; arguments are coerced (kebab-case keywords,
  string→typed) and validated before the body runs. A validation
  failure short-circuits to `{:errors ...}` without invoking the body.
- `value-schema` describes the parent value passed by Lacinia for
  field resolvers; use `:any` for `:Query` and `:Mutation`.
- The macro defines a var named `Object-field` (e.g. `Query-user`)
  carrying `:graphql/resolver [:Object :field]` metadata. That metadata
  is what `def-resolver-map` scans for.

### `defstreamer`

```clojure
(defstreamer :Subscription :field
  "Optional docstring."
  [:=> [:cat ctx-schema args-schema :any] value-schema]
  [ctx args]
  ch)
```

- The first argument must be `:Subscription`.
- The body returns a `core.async` channel. The macro wires up the
  go-loop that pumps values from the channel to the GraphQL source
  stream and encodes each value against `value-schema`.
- Closing the channel ends the subscription; the macro returns a
  cleanup function that closes it on client disconnect.

### `def-resolver-map`

`(def-resolver-map)` scans the current namespace for vars carrying
`:graphql/resolver` or `:graphql/streamer` metadata and binds a
`resolvers` var holding a single map keyed by `[:Object :field]`.
That map is what you pass as `:resolver-map` to `graphql-middleware`.
Optional arguments are a docstring and/or a vector of resolver
middleware functions; middleware wraps resolvers only, not streamers.

### Schema properties

Malli map properties drive type generation:

- `{:graphql/type :Name}` — emits a GraphQL object type.
- `{:graphql/interface :Name}` — emits an interface.
- `{:graphql/implements [SchemaVar ...]}` — declares interface
  implementation. Pass the schema vars themselves, not keywords.
- `{:graphql/scalar :Name}` — maps an `:or`/`:tuple` to a custom
  scalar; register `:parse`/`:serialize` via the middleware's
  `:scalars` option.
- `{:graphql/hidden true}` — omits a field from the generated schema.
- `{:graphql/name :OverrideName}` — overrides the camelCased default.

Built-in scalars cover `:string`, `:int`, `:double`/`:float`,
`:boolean`, `:uuid` (`Uuid`), `:time/instant` (`Date`), and bare maps
with no fields (`Json`). `:maybe` makes a field nullable; `:vector`
becomes a non-null list of non-null elements.

## Dev aliases

The repo's `deps.edn` exposes four aliases for working on the library:

- `clojure -X:test` — run the test suite.
- `clojure -M:lint` — run clj-kondo.
- `clojure -X:format` — apply cljfmt formatting.
- `clojure -M:repl` — start an nREPL with CIDER middleware.

## Releasing

The release workflow, prerequisites, and bump semantics are documented
in [`doc/releasing.md`](doc/releasing.md). 

## Acknowledgements

This was inspired by the REPL-driven Compojure handlers in [Metabase](https://github.com/metabase/metabase).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
