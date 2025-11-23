(ns graphql-server.ring
  "Ring middleware for GraphQL endpoint handling.

  Provides Ring middleware that intercepts GraphQL requests, decodes JSON,
  executes queries via Lacinia, and encodes responses."
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
  to the GraphQL endpoint."
  [path]
  (-> (response/response
       (str "<!DOCTYPE html>
<html>
<head>
  <title>GraphiQL</title>
  <style>
    body {
      height: 100%;
      margin: 0;
      width: 100%;
      overflow: hidden;
    }
    #graphiql {
      height: 100vh;
    }
  </style>
  <script crossorigin src=\"https://unpkg.com/react@18/umd/react.production.min.js\"></script>
  <script crossorigin src=\"https://unpkg.com/react-dom@18/umd/react-dom.production.min.js\"></script>
  <link rel=\"stylesheet\" href=\"https://unpkg.com/graphiql/graphiql.min.css\" />
</head>
<body>
  <div id=\"graphiql\">Loading...</div>
  <script src=\"https://unpkg.com/graphiql/graphiql.min.js\" type=\"application/javascript\"></script>
  <script>
    const fetcher = GraphiQL.createFetcher({
      url: '" path "',
    });
    const root = ReactDOM.createRoot(document.getElementById('graphiql'));
    root.render(React.createElement(GraphiQL, { fetcher: fetcher }));
  </script>
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
  - POST requests: Decodes JSON body, executes GraphQL query, returns JSON response
  - GET requests: Serves GraphiQL interactive query explorer (unless disabled)

  Options:
  - `:path` - The URL path to intercept (default: `/graphql`)
  - `:resolver-map` - Map of resolvers from [[graphql-server.core/def-resolver-map]]
  - `:context-fn` - Optional function `(fn [request] context-map)` to build GraphQL
                    context from the Ring request. Defaults to including the full request
                    under `:request` key.
  - `:enable-graphiql?` - Whether to serve GraphiQL on GET requests (default: `true`)

  Example:

      (def app
        (-> handler
            (graphql-middleware {:resolver-map my-resolvers
                                 :path \"/api/graphql\"
                                 :context-fn (fn [req] {:user (:user req)})
                                 :enable-graphiql? true})))"
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
        (try
          (let [body                      (slurp (:body request))
                {:keys [query variables]} (json/parse-string body true)
                context                   (context-fn request)]
            (execute-graphql compiled-schema query variables context))
          (catch Exception e
            (json-response
             {:errors [{:message (str "Invalid request: " (.getMessage e))
                        :type "bad-request"}]}
             400)))

        ;; GET requests - serve GraphiQL
        (and enable-graphiql?
             (= :get (:request-method request))
             (str/starts-with? (:uri request) path))
        (graphiql-response path)

        ;; Other requests - pass through
        :else
        (handler request)))))
