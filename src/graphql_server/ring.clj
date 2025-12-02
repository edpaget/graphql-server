(ns graphql-server.ring
  "Ring middleware for GraphQL endpoint handling.

  Provides Ring middleware that intercepts GraphQL requests and executes queries
  via Lacinia. Expects the request body to be pre-parsed JSON (a Clojure map).
  Use standard Ring JSON middleware like `ring.middleware.json/wrap-json-body`
  before this middleware.

  Supports GraphQL subscriptions via Server-Sent Events (SSE) when enabled.
  See [[graphql-middleware]] for subscription configuration options."
  (:require
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.walmartlabs.lacinia :as lacinia]
   [com.walmartlabs.lacinia.constants :as lacinia.constants]
   [com.walmartlabs.lacinia.executor :as lacinia.executor]
   [com.walmartlabs.lacinia.parser :as lacinia.parser]
   [com.walmartlabs.lacinia.schema :as lacinia.schema]
   [com.walmartlabs.lacinia.util :as lacinia.util]
   [graphql-server.schema :as schema]
   [graphql-server.sse :as sse]
   [java-time.api :as t]
   [ring.util.response :as response]))

(defn- date-scalar
  "Adds Date scalar type handlers to the schema.

  Parses ISO-8601 strings to java.time.Instant, serializes Instant to string."
  [schema]
  (update-in schema [:scalars :Date] assoc
             :parse t/instant
             :serialize str))

(defn- uuid-scalar
  "Adds UUID scalar type handlers to the schema.

  Parses UUID strings, serializes UUIDs to string."
  [schema]
  (update-in schema [:scalars :Uuid] assoc
             :parse parse-uuid
             :serialize str))

(defn- json-scalar
  "Adds JSON scalar type handlers to the schema.

  Used for complex nested data like maps with arbitrary keys.
  Values pass through unchanged as they're already JSON-compatible."
  [schema]
  (update-in schema [:scalars :Json] assoc
             :parse identity
             :serialize identity))

(defn- tuple-keys->qualified-keywords
  "Converts resolver map keys from tuples to qualified keywords.

  Transforms `{[:Query :hello] [schema fn]}` to `{:Query/hello fn}`.
  Optionally filters by object type when `filter-objects` is provided.
  The `namespace-override` can be used to change the namespace (e.g., for
  subscriptions which use `:subscriptions/field` format in Lacinia)."
  ([resolver-map]
   (tuple-keys->qualified-keywords resolver-map nil nil))
  ([resolver-map filter-objects]
   (tuple-keys->qualified-keywords resolver-map filter-objects nil))
  ([resolver-map filter-objects namespace-override]
   (reduce-kv (fn [m [object action] [_schema resolver-fn]]
                (if (or (nil? filter-objects)
                        (contains? filter-objects object))
                  (let [ns-name (or namespace-override (name object))]
                    (assoc m (keyword ns-name (csk/->camelCase (name action))) resolver-fn))
                  m))
              {}
              resolver-map)))

(defn- apply-custom-scalars
  "Applies custom scalar definitions to the schema.

  Takes a schema and a map of scalar keyword names to definitions, where each
  definition contains `:parse` and `:serialize` functions."
  [schema scalars]
  (reduce-kv (fn [s scalar-name {:keys [parse serialize]}]
               (update-in s [:scalars scalar-name] assoc
                          :parse parse
                          :serialize serialize))
             schema
             scalars))

(defn build-lacinia-schema
  "Builds a compiled Lacinia schema from a resolver map.

  Takes a map of `[object action]` -> `[schema resolver-var]` tuples (as produced
  by [[graphql-server.core/collect-resolvers]] or [[graphql-server.core/def-resolver-map]])
  and returns a compiled Lacinia schema ready for execution.

  The resolver map may include both Query/Mutation resolvers and Subscription
  streamers. Resolvers are injected via `inject-resolvers`, streamers via
  `inject-streamers`.

  The schema includes built-in scalar types for Date, UUID, and Json.

  Optionally accepts a map of custom scalars where keys are scalar names (keywords)
  and values are maps with `:parse` and `:serialize` functions:

      {:HexPosition {:parse vec :serialize identity}}"
  ([resolver-map]
   (build-lacinia-schema resolver-map {}))
  ([resolver-map custom-scalars]
   (let [;; Include Query, Mutation, and field resolvers (non-Subscription)
         resolvers (tuple-keys->qualified-keywords
                    resolver-map
                    (into #{} (comp (map first) (remove #{:Subscription}))
                          (keys resolver-map)))
         streamers (tuple-keys->qualified-keywords resolver-map #{:Subscription} "subscriptions")]
     (-> (schema/->graphql-schema resolver-map)
         date-scalar
         uuid-scalar
         json-scalar
         (apply-custom-scalars custom-scalars)
         (lacinia.util/inject-resolvers resolvers)
         (cond-> (seq streamers)
           (lacinia.util/inject-streamers streamers))
         lacinia.schema/compile))))

(defn- json-response
  "Creates a Ring JSON response with the given data and status code."
  [data status]
  (-> (response/response (json/generate-string data))
      (response/status status)
      (response/content-type "application/json")))

(defn- graphiql-response
  "Creates a Ring response serving the GraphiQL IDE.

  GraphiQL is an interactive GraphQL query explorer served on GET requests
  to the GraphQL endpoint. Uses ES modules with React 19 and includes the
  GraphiQL Explorer plugin."
  [path]
  (-> (response/response
       (str "<!doctype html>
<html lang=\"en\">
  <head>
    <meta charset=\"UTF-8\" />
    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />
    <title>GraphiQL</title>
    <style>
      body {
        margin: 0;
      }
      #graphiql {
        height: 100dvh;
      }
      .loading {
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 4rem;
      }
    </style>
    <link rel=\"stylesheet\" href=\"https://esm.sh/graphiql/dist/style.css\" />
    <link rel=\"stylesheet\" href=\"https://esm.sh/@graphiql/plugin-explorer/dist/style.css\" />
    <script type=\"importmap\">
      {
        \"imports\": {
          \"react\": \"https://esm.sh/react@19.1.0\",
          \"react/\": \"https://esm.sh/react@19.1.0/\",
          \"react-dom\": \"https://esm.sh/react-dom@19.1.0\",
          \"react-dom/\": \"https://esm.sh/react-dom@19.1.0/\",
          \"graphiql\": \"https://esm.sh/graphiql?standalone&external=react,react-dom,@graphiql/react,graphql\",
          \"graphiql/\": \"https://esm.sh/graphiql/\",
          \"@graphiql/plugin-explorer\": \"https://esm.sh/@graphiql/plugin-explorer?standalone&external=react,@graphiql/react,graphql\",
          \"@graphiql/react\": \"https://esm.sh/@graphiql/react?standalone&external=react,react-dom,graphql,@graphiql/toolkit,@emotion/is-prop-valid\",
          \"@graphiql/toolkit\": \"https://esm.sh/@graphiql/toolkit?standalone&external=graphql\",
          \"graphql\": \"https://esm.sh/graphql@16.11.0\",
          \"@emotion/is-prop-valid\": \"data:text/javascript,\"
        }
      }
    </script>
    <script type=\"module\">
      import React from 'react';
      import ReactDOM from 'react-dom/client';
      import { GraphiQL, HISTORY_PLUGIN } from 'graphiql';
      import { createGraphiQLFetcher } from '@graphiql/toolkit';
      import { explorerPlugin } from '@graphiql/plugin-explorer';
      import 'graphiql/setup-workers/esm.sh';

      const fetcher = createGraphiQLFetcher({
        url: '" path "'
      });
      const plugins = [HISTORY_PLUGIN, explorerPlugin()];

      function App() {
        return React.createElement(GraphiQL, {
          fetcher,
          plugins,
          defaultEditorToolsVisibility: true,
        });
      }

      const container = document.getElementById('graphiql');
      const root = ReactDOM.createRoot(container);
      root.render(React.createElement(App));
    </script>
  </head>
  <body>
    <div id=\"graphiql\">
      <div class=\"loading\">Loading…</div>
    </div>
  </body>
</html>"))
      (response/status 200)
      (response/content-type "text/html")))

(defn- execute-graphql
  "Executes a GraphQL query using the compiled schema.

  Returns a Ring response with the query results or errors."
  [compiled-schema query variables context]
  (try
    (let [result (lacinia/execute compiled-schema query variables context)]
      (json-response result 200))
    (catch Exception e
      (log/error e "Internal Error")
      (json-response
       {:errors [{:message (.getMessage e)
                  :type (or (some-> e ex-data :type name) "internal-error")}]}
       500))))

(defn- execute-subscription
  "Executes a GraphQL subscription query via SSE.

  Parses the subscription query, invokes the streamer, and returns an SSE
  streaming response. The streamer pushes values to a channel which are
  then streamed to the client as SSE events."
  [compiled-schema query variables context cors-origin]
  (try
    (let [parsed-query   (-> compiled-schema
                             (lacinia.parser/parse-query query)
                             (lacinia.parser/prepare-with-query-variables variables))
          result-channel (async/chan 10)
          source-stream  (fn [value]
                           (if (nil? value)
                             (async/close! result-channel)
                             (async/put! result-channel
                                         {:type :data
                                          :data {:data value}}))
                           nil)
          _cleanup-fn    (lacinia.executor/invoke-streamer
                          (assoc context
                                 lacinia.constants/parsed-query-key parsed-query)
                          source-stream)]
      (async/put! result-channel {:type :connected :data {:subscription "active"}})
      (sse/sse-response result-channel :cors-origin cors-origin))
    (catch Exception e
      (log/error e "Subscription error")
      (json-response
       {:errors [{:message (.getMessage e)
                  :type (or (some-> e ex-data :type name) "subscription-error")}]}
       400))))

(defn- parse-query-params
  "Parses query and variables from request query string."
  [request]
  (let [query-string (:query-string request)
        params       (when query-string
                       (into {}
                             (for [pair  (str/split query-string #"&")
                                   :let  [[k v] (str/split pair #"=" 2)]
                                   :when (and k v)]
                               [(keyword k) (java.net.URLDecoder/decode v "UTF-8")])))]
    {:query     (:query params)
     :variables (when-let [vars (:variables params)]
                  (try
                    (json/parse-string vars true)
                    (catch Exception _ nil)))}))

(defn graphql-middleware
  "Ring middleware that handles GraphQL requests at a specified endpoint.

  Intercepts requests to the GraphQL endpoint:
  - POST requests: Executes GraphQL query from parsed body, returns JSON response
  - GET requests: Serves GraphiQL interactive query explorer (unless disabled)

  The request `:body` must be a parsed map containing `:query` (string) and optionally
  `:variables` (map). Use standard Ring JSON middleware like `ring.middleware.json/wrap-json-body`
  before this middleware to parse incoming JSON requests.

  Options:
  - `:path` - The URL path to intercept (default: `/graphql`)
  - `:resolver-map` - Map of resolvers from [[graphql-server.core/def-resolver-map]]
  - `:context-fn` - Optional function `(fn [request] context-map)` to build GraphQL
                    context from the Ring request. Defaults to including the full request
                    under `:request` key.
  - `:enable-graphiql?` - Whether to serve GraphiQL on GET requests (default: `true`)
  - `:scalars` - Optional map of custom scalar definitions. Keys are scalar names (keywords),
                 values are maps with `:parse` and `:serialize` functions.

  Subscription options (for real-time updates via SSE):
  - `:enable-subscriptions?` - Whether to enable subscription endpoint (default: `false`)
  - `:subscription-path` - Path for subscription endpoint (default: `/graphql/subscriptions`)
  - `:cors-origin` - CORS origin for SSE responses (default: `\"*\"`)

  Example:

      (require '[ring.middleware.json :refer [wrap-json-body]]
               '[graphql-server.subscriptions :as subs])

      (def subscription-manager (subs/create-subscription-manager))

      (def app
        (-> handler
            (graphql-middleware {:resolver-map my-resolvers
                                 :path \"/api/graphql\"
                                 :context-fn (fn [req]
                                               {:user (:user req)
                                                :subscription-manager subscription-manager})
                                 :scalars {:HexPosition {:parse vec :serialize identity}}
                                 :enable-graphiql? true
                                 :enable-subscriptions? true
                                 :subscription-path \"/api/graphql/subscriptions\"})
            (wrap-json-body {:keywords? true})))"
  [handler {:keys [path resolver-map context-fn enable-graphiql? scalars
                   enable-subscriptions? subscription-path cors-origin]
            :or {path "/graphql"
                 context-fn (fn [req] {:request req})
                 enable-graphiql? true
                 scalars {}
                 enable-subscriptions? false
                 subscription-path "/graphql/subscriptions"
                 cors-origin "*"}}]
  (let [compiled-schema (build-lacinia-schema resolver-map scalars)]
    (fn [request]
      (let [uri    (:uri request)
            method (:request-method request)]
        (cond
          ;; Subscription OPTIONS preflight
          (and enable-subscriptions?
               (= method :options)
               (str/starts-with? uri subscription-path))
          {:status  204
           :headers {"Access-Control-Allow-Origin"  cors-origin
                     "Access-Control-Allow-Methods" "GET, OPTIONS"
                     "Access-Control-Allow-Headers" "Content-Type, Authorization"
                     "Access-Control-Max-Age"       "86400"}}

          ;; Subscription GET requests - execute subscription via SSE
          (and enable-subscriptions?
               (= method :get)
               (str/starts-with? uri subscription-path))
          (let [{:keys [query variables]} (parse-query-params request)
                context                   (context-fn request)]
            (if query
              (execute-subscription compiled-schema query variables context cors-origin)
              (json-response {:errors [{:message "Missing query parameter"}]} 400)))

          ;; POST requests - execute GraphQL query/mutation
          (and (= method :post)
               (str/starts-with? uri path))
          (let [{:keys [query variables]} (:body request)
                context                   (context-fn request)]
            (execute-graphql compiled-schema query variables context))

          ;; GET requests to main path - serve GraphiQL
          (and enable-graphiql?
               (= method :get)
               (str/starts-with? uri path)
               (not (str/starts-with? uri subscription-path)))
          (graphiql-response path)

          ;; Other requests - pass through
          :else
          (handler request))))))
