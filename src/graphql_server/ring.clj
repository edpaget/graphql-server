(ns graphql-server.ring
  "Ring middleware for GraphQL endpoint handling.

  Provides Ring middleware that intercepts GraphQL requests and executes queries
  via Lacinia. Expects the request body to be pre-parsed JSON (a Clojure map).
  Use standard Ring JSON middleware like `ring.middleware.json/wrap-json-body`
  before this middleware."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.walmartlabs.lacinia :as lacinia]
   [com.walmartlabs.lacinia.schema :as lacinia.schema]
   [com.walmartlabs.lacinia.util :as lacinia.util]
   [graphql-server.schema :as schema]
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

(defn- tuple-keys->qualified-keywords
  "Converts resolver map keys from tuples to qualified keywords.

  Transforms `{[:Query :hello] [schema fn]}` to `{:Query/hello fn}`."
  [resolver-map]
  (reduce-kv (fn [m [object action] [_schema resolver-fn]]
               (assoc m (keyword (name object) (name action)) resolver-fn))
             {}
             resolver-map))

(defn build-lacinia-schema
  "Builds a compiled Lacinia schema from a resolver map.

  Takes a map of `[object action]` -> `[schema resolver-var]` tuples (as produced
  by [[graphql-server.core/collect-resolvers]] or [[graphql-server.core/def-resolver-map]])
  and returns a compiled Lacinia schema ready for execution.

  The schema includes built-in scalar types for Date and UUID."
  [resolver-map]
  (-> (schema/->graphql-schema resolver-map)
      date-scalar
      uuid-scalar
      (lacinia.util/inject-resolvers (tuple-keys->qualified-keywords resolver-map))
      lacinia.schema/compile))

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
      (json-response
       {:errors [{:message (.getMessage e)
                  :type (or (some-> e ex-data :type name) "internal-error")}]}
       500))))

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

  Example:

      (require '[ring.middleware.json :refer [wrap-json-body]])

      (def app
        (-> handler
            (graphql-middleware {:resolver-map my-resolvers
                                 :path \"/api/graphql\"
                                 :context-fn (fn [req] {:user (:user req)})
                                 :enable-graphiql? true})
            (wrap-json-body {:keywords? true})))"
  [handler {:keys [path resolver-map context-fn enable-graphiql?]
            :or {path "/graphql"
                 context-fn (fn [req] {:request req})
                 enable-graphiql? true}}]
  (let [compiled-schema (build-lacinia-schema resolver-map)]
    (fn [request]
      (cond
        ;; POST requests - execute GraphQL
        (and (= :post (:request-method request))
             (str/starts-with? (:uri request) path))
        (let [{:keys [query variables]} (:body request)
              context                   (context-fn request)]
          (execute-graphql compiled-schema query variables context))

        ;; GET requests - serve GraphiQL
        (and enable-graphiql?
             (= :get (:request-method request))
             (str/starts-with? (:uri request) path))
        (graphiql-response path)

        ;; Other requests - pass through
        :else
        (handler request)))))
