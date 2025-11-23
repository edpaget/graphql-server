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

  Intercepts POST requests to the GraphQL endpoint (default `/graphql`), decodes
  the JSON body, executes the GraphQL query via Lacinia, and returns a JSON response.

  Options:
  - `:path` - The URL path to intercept (default: `/graphql`)
  - `:resolver-map` - Map of resolvers from [[graphql-server.core/def-resolver-map]]
  - `:context-fn` - Optional function `(fn [request] context-map)` to build GraphQL
                    context from the Ring request. Defaults to including the full request
                    under `:request` key.

  Example:

      (def app
        (-> handler
            (graphql-middleware {:resolver-map my-resolvers
                                 :path \"/api/graphql\"
                                 :context-fn (fn [req] {:user (:user req)})})))"
  [handler {:keys [path resolver-map context-fn]
            :or {path "/graphql"
                 context-fn (fn [req] {:request req})}}]
  (let [compiled-schema (build-lacinia-schema resolver-map)]
    (fn [request]
      (if (and (= :post (:request-method request))
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
        (handler request)))))
