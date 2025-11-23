(ns graphql-server.impl
  "Internal implementation details for GraphQL resolver handling."
  (:require
   [malli.core :as mc]
   [malli.error :as merr]))

(defn ->argument-type
  "Extracts the argument schema from a resolver function schema.

  Walks a Malli `:=>` schema to extract the second argument (args) from the `:cat`.
  Used to get the schema for coercing GraphQL arguments."
  [schema _ children _]
  (case (mc/type schema)
    :=> (first children)
    :cat (second children)
    schema))

(defn coerce-args
  "Wraps a resolver function with Malli argument coercion.

  Takes an argument schema and a 3-arity resolver function, returning a wrapped
  function that coerces the second argument (GraphQL args) according to the schema.

  On coercion failure, returns a map with `:errors` key containing humanized validation
  errors. This allows the caller to handle errors appropriately (e.g., convert to
  GraphQL error format)."
  [arg-schema f]
  (let [coerce (mc/coercer arg-schema)]
    (fn [ctx args value]
      (try
        (let [coerced (coerce (or args {}))]
          (if (mc/validate arg-schema coerced)
            (f ctx coerced value)
            {:errors (-> arg-schema
                         (mc/explain coerced)
                         merr/humanize)}))
        (catch Exception e
          {:errors {:message (ex-message e)
                    :type (or (:type (ex-data e)) :unknown)}})))))
