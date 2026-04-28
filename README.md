# graphql-server

A Clojure library for defining GraphQL schemas using Malli, wrapping Lacinia with Ring support.

## What it is

`graphql-server` lets you define a GraphQL server in Clojure where Malli
schemas are the source of truth. You write resolvers as plain functions
annotated with their Malli function schema; the library extracts type
metadata from those schemas, generates a Lacinia schema, and exposes
the whole thing as Ring middleware (with built-in GraphiQL and SSE
subscriptions). No Pedestal, no separate `.graphql` SDL file, no
hand-maintained type-to-resolver mapping.

## Installation

### Stable release (Clojars)

Once published to Clojars, add to your `deps.edn`:

```clojure
{:deps {net.carcdr/graphql-server {:mvn/version "0.1.1"}}}
```

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

The example below is a complete, runnable namespace that defines a `Node`
interface, a `User` object that implements it, a query, a validated
mutation, and a subscription — everything the public API exposes.

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

`graphql-middleware` is a standard Ring middleware. It serves GraphiQL
on `GET /graphql`, accepts queries on `POST /graphql`, and (when enabled)
streams subscriptions over SSE on `GET /graphql/subscriptions`.

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

### Dev aliases

The repo's `deps.edn` exposes four aliases for working on the library:

- `clojure -X:test` — run the test suite.
- `clojure -M:lint` — run clj-kondo.
- `clojure -X:format` — apply cljfmt formatting.
- `clojure -M:repl` — start an nREPL with CIDER middleware.

### What this is *not*

There is no DataLoader-style request batching, no DB integration layer,
and no built-in authentication. Wire those into your application
yourself: pull request data and identity off the Ring request inside
`:context-fn`, then read them from `ctx` in your resolver bodies.

## Releasing

Releases are cut by manually dispatching the **Release** workflow
(`.github/workflows/release.yml`) and picking a bump level
(`patch`, `minor`, or `major`). The workflow updates `VERSION`,
`CHANGELOG.md`, and `README.md`, tags `vX.Y.Z`, and deploys the JAR
to Clojars. After a successful deploy it commits a follow-up
`X.Y.Z+1-SNAPSHOT` bump back to `main`.

### Prerequisites (one-time setup)

1. **Clojars deploy token** — create a deploy token (not your account
   password) at <https://clojars.org/tokens> scoped to
   `net.carcdr/graphql-server`. Add the following repo secrets at
   **Settings → Secrets and variables → Actions**:
   - `CLOJARS_USERNAME` — your Clojars username
   - `CLOJARS_PASSWORD` — the deploy token

2. **Release GitHub App** — register a GitHub App (Settings → Developer
   settings → GitHub Apps → New GitHub App) with `contents: write`
   permission, install it on this repo, and add:
   - `RELEASE_APP_ID` — the App ID
   - `RELEASE_APP_PRIVATE_KEY` — the App's private key (PEM contents)

   This is required so the post-release snapshot-bump commit pushed
   back to `main` re-triggers CI and the snapshot publish workflow.
   The default `GITHUB_TOKEN` cannot trigger downstream workflows.

3. **Branch protection on `main`** — at **Settings → Branches**,
   require these status checks before merge:
   - `Lint and Format`
   - `Test`

   The release workflow gates on these check-run names against the
   tip of `main` before deploying; without branch protection enforcing
   them, an uncheck-run commit can reach `main` and the gate will fail.

### Cutting a release

1. Go to **Actions → Release → Run workflow**.
2. Pick a bump level: `patch`, `minor`, or `major`.
3. Watch the run. On success:
   - `vX.Y.Z` tag is pushed.
   - JAR is deployed to Clojars (visible at
     <https://clojars.org/net.carcdr/graphql-server>).
   - `main` advances by two commits: the release and the next-snapshot bump.

### Bump semantics

The current `VERSION` is always `X.Y.Z-SNAPSHOT`, representing the
in-development next version.

- `patch` releases the current `X.Y.Z-SNAPSHOT` as `X.Y.Z` (drops the
  suffix), then bumps to `X.Y.(Z+1)-SNAPSHOT`.
- `minor` releases as `X.(Y+1).0`, then bumps to `X.(Y+1).1-SNAPSHOT`.
- `major` releases as `(X+1).0.0`, then bumps to `(X+1).0.1-SNAPSHOT`.

## License

See LICENSE file in repository root.
