(ns graphql-server.impl
  "Internal implementation details for GraphQL resolver handling."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.tools.logging :as log]
   [com.walmartlabs.lacinia.schema :as schema]
   [malli.core :as mc]
   [malli.error :as merr]
   [malli.transform :as mt]))

(defn ->argument-type
  "Extracts the argument schema from a resolver function schema.

  Walks a Malli `:=>` schema to extract the second argument (args) from the `:cat`.
  Used to get the schema for coercing GraphQL arguments."
  [schema _ children _]
  (case (mc/type schema)
    :=> (first children)
    :cat (second children)
    schema))

(def ^:private enum-transformer
  "Malli transformer for enum values.

  Encodes namespaced keywords to their string name (e.g., `:my-ns/FOO` -> `\"FOO\"`).
  Decodes strings back to namespaced keywords if they match an option in an `[:enum ...]`
  schema (e.g., `\"FOO\"` -> `:my-ns/FOO`, assuming `:my-ns/FOO` is a valid schema option)."
  (mt/transformer
   {:decoders
    {:enum
     {:compile
      (fn [schema _options]
        (let [enum-options   (mc/children schema)
              enum-namespace (when-let [first-option (first enum-options)]
                               (when (keyword? first-option)
                                 (namespace first-option)))]
          (fn [value]
            (if enum-namespace
              (let [kw-value (keyword enum-namespace (name value))]
                (if (some #(= kw-value %) enum-options)
                  kw-value
                  value))
              value))))}}
    :encoders
    {:enum (fn [value] (when value (name value)))}}))

(def ^:private lacinia-type-tag-transformer
  "Malli transformer that applies Lacinia type tags during encoding.

  For `:multi` schemas (union types), uses the dispatch function to determine the
  concrete type and tags the encoded value with `schema/tag-with-type`.
  For `:map` schemas with `:graphql/type` or `:graphql/interface` properties,
  tags with that type name."
  (mt/transformer
   {:encoders
    {:multi
     {:compile
      (fn [schema _options]
        (let [dispatch-fn (-> schema mc/properties :dispatch)
              ;; Build map from dispatch value to GraphQL type name
              tag-map     (->> (mc/children schema)
                               (map (fn [[dispatch-val _props child-schema]]
                                      (let [child     (mc/deref-all child-schema)
                                            type-name (or (get (mc/properties child) :graphql/type)
                                                          (get (mc/properties child) :graphql/interface))]
                                        [dispatch-val (when type-name
                                                        (csk/->PascalCaseKeyword type-name))])))
                               (into {}))]
          (fn [value]
            (when value
              (if-let [tag (get tag-map (dispatch-fn value))]
                (schema/tag-with-type value tag)
                value)))))}
     :map
     {:compile
      (fn [schema _options]
        (let [props     (mc/properties schema)
              type-name (or (:graphql/type props)
                            (:graphql/interface props))]
          (if type-name
            (let [tag (csk/->PascalCaseKeyword type-name)]
              (fn [value]
                (when value
                  (schema/tag-with-type value tag))))
            identity)))}}}))

(def ^:private decode-key-transformer
  "Malli transformer for decoding map keys.

  Decodes keys from camelCase to kebab-case keywords."
  (mt/key-transformer
   {:decode csk/->kebab-case-keyword}))

(def ^:private encode-key-transformer
  "Malli transformer for encoding map keys.

  Encodes keys from kebab-case keywords to camelCase keywords."
  (mt/key-transformer
   {:encode csk/->camelCaseKeyword}))

(def ^:private decoding-transformer
  "Composite transformer for decoding GraphQL inputs to application data.

  Order of operations:
  1. Transform map keys (camelCase keyword -> kebab-case keyword).
  2. Transform enum values (string -> namespaced keyword)."
  (mt/transformer
   decode-key-transformer
   enum-transformer
   mt/string-transformer))

(defn coerce-args
  "Wraps a resolver function with Malli argument coercion.

  Takes an argument schema and a 3-arity resolver function, returning a wrapped
  function that decodes and coerces the second argument (GraphQL args) according
  to the schema.

  Arguments are decoded using [[decoding-transformer]] which:
  - Converts camelCase keys to kebab-case keywords
  - Converts string enum values to namespaced keywords (if schema uses them)
  - Applies standard string->type coercions (e.g., string to UUID)

  On coercion failure, returns a map with `:errors` key containing humanized validation
  errors. This allows the caller to handle errors appropriately (e.g., convert to
  GraphQL error format)."
  [arg-schema f]
  (let [coerce (mc/coercer arg-schema decoding-transformer)]
    (fn [ctx args value]
      (try
        (let [coerced (coerce (or args {}))]
          (if (mc/validate arg-schema coerced)
            (f ctx coerced value)
            {:errors (-> arg-schema
                         (mc/explain coerced)
                         merr/humanize)}))
        (catch Exception e
          (log/error e "Error during coercion")
          {:errors {:message (ex-message e)
                    :type (or (:type (ex-data e)) :unknown)}})))))

(def ^:private encoding-transformer
  "Composite transformer for encoding data to GraphQL outputs.

  Order of operations:
  1. Transform enum values (namespaced keyword -> string).
  2. Transform map keys (kebab-case keyword -> camelCase keyword).
  3. Apply Lacinia type tags to maps and multi types."
  (mt/transformer
   enum-transformer
   encode-key-transformer
   lacinia-type-tag-transformer))

(defn encode
  "Encodes application data into GraphQL output format according to the given Malli schema.

  Applies enum and key transformations using [[encoding-transformer]]."
  [data schema]
  (mc/encode schema data encoding-transformer))

(defn ->return-type
  "Extracts the return type schema from a resolver function schema.

  Walks a Malli `:=>` schema to extract the return type.
  Used to get the schema for encoding resolver return values."
  [schema _ children _]
  (case (mc/type schema)
    :=> (second children)
    schema))

(defn- ->graphql-type-name
  "Extracts the GraphQL type name from a Malli schema.

  Looks for `:graphql/type` or `:graphql/interface` properties in the schema,
  or uses the schema name for registered schemas (e.g., `::models/GameCard`)."
  [schema]
  (case (mc/type schema)
    (:multi :map :enum) (when-let [type-name (or (get (mc/properties schema) :graphql/type)
                                                 (get (mc/properties schema) :graphql/interface))]
                          (csk/->PascalCaseKeyword type-name))
    ::mc/schema (-> schema mc/form name csk/->PascalCaseKeyword)))

(defn- ->tag-map
  "Creates a map from dispatch values to GraphQL type names.

  Used internally by [[merge-tag-with-type]] to build the mapping for union/interface types."
  [schema _ children _]
  (case (mc/type schema)
    ::mc/schema (when-let [gql-type (mc/walk (mc/deref schema) ->tag-map)]
                  (if (= gql-type :map) (->graphql-type-name schema) gql-type))
    :multi (into {} (map (juxt first last)) children)
    :map (or (->graphql-type-name schema) :map)
    :merge (mc/walk (mc/deref schema) ->tag-map)
    nil))

(defn merge-tag-with-type
  "Creates a function that tags data with its concrete GraphQL type.

  Takes a Malli schema (typically a `:multi` schema representing a GraphQL union or
  interface) and returns a function. This returned function, when given a data instance,
  uses the original schema's dispatch function to determine the instance's concrete type
  and returns the corresponding GraphQL type name (as a keyword).

  This is primarily used by Lacinia's `tag-with-type` to resolve concrete types for
  unions and interfaces at query time."
  [schema]
  (let [derefed     (-> schema mc/schema mc/deref)
        dispatch-fn (-> derefed mc/properties :dispatch)
        tag-map     (mc/walk derefed ->tag-map)]
    (fn [model]
      (get tag-map (dispatch-fn model)))))

(defn- unwrap-schema
  "Unwraps a schema, dereferencing and stripping `:maybe` and `:vector` wrappers.

  Returns the innermost schema type for encoding purposes."
  [schema]
  (let [derefed     (-> schema mc/schema mc/deref)
        schema-type (mc/type derefed)]
    (case schema-type
      :maybe (recur (first (mc/children derefed)))
      :vector (recur (first (mc/children derefed)))
      derefed)))

(defn wrap-resolver-with-encoding
  "Wraps a resolver function to automatically encode its return value.

  Takes a resolver function and a return type schema. Returns a new resolver function that:
  1. Calls the original resolver
  2. Encodes the result using the return type schema (including Lacinia type tags)
  3. Returns the encoded result

  The encoding transformer automatically applies Lacinia type tags to all nested
  `:map` and `:multi` types that have `:graphql/type` or `:graphql/interface` properties.
  If the result is `nil`, returns `nil` without encoding.
  If the result is a collection, encodes each element with the unwrapped item schema."
  [resolver return-type-schema]
  (let [item-schema (unwrap-schema return-type-schema)]
    (fn [ctx args value]
      (let [result (resolver ctx args value)]
        (cond
          (nil? result) nil
          (sequential? result) (mapv #(encode % item-schema) result)
          :else (encode result return-type-schema))))))
