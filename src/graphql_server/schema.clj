(ns graphql-server.schema
  "Schema definition and validation using Malli."
  (:require
   [camel-snake-kebab.core :as csk]
   [malli.core :as mc]))

(defn- dispatch-mc-type
  [schema _ _ _]
  (mc/type schema))

(defn- ->graphql-type-name
  [schema]
  (case (mc/type schema)
    (:multi :map :enum) (when-let [type-name (or (get (mc/properties schema) :graphql/type)
                                                 (get (mc/properties schema) :graphql/interface))]
                          (csk/->PascalCaseKeyword type-name))
    ::mc/schema (-> schema mc/form name csk/->PascalCaseKeyword)))

(declare ->graphql-type)

(defn- ->graphql-field
  "Process a walked map field entry.
  Input is [field-name opts [field-type field-types]] where the field schema has been walked.
  Returns [[field-name field-def] field-types] or nil if hidden."
  [[field-name opts [field-type field-types]]]
  (when-not (or (:graphql/hidden opts) (nil? field-type))
    [[(csk/->camelCaseKeyword field-name) {:type field-type}]
     field-types]))

(defn- collect-types
  "Merge new types into accumulator map."
  [acc type-category type-name type-def]
  (assoc-in acc [type-category type-name] type-def))

(defmulti ^:private ->graphql-type
  "Convert Malli schema to GraphQL type representation.
  Returns [type-ref types-map] where type-ref is the GraphQL type reference
  and types-map contains any new type definitions discovered."
  dispatch-mc-type)

(defmethod ->graphql-type ::mc/schema
  [schema _ _ _]
  (let [[inner-type types] (mc/walk (mc/deref schema) ->graphql-type)]
    (if-not (map? inner-type)
      [inner-type types]
      (let [type-name (->graphql-type-name schema)]
        [(list 'non-null type-name)
         (collect-types types :objects type-name {:fields inner-type})]))))

(defmethod ->graphql-type :default
  [schema _ _ _]
  [(mc/form schema) {}])

(defmethod ->graphql-type :=
  [_ _ _ _]
  [nil {}])

(defmethod ->graphql-type :uuid
  [_ _ _ _]
  [(list 'non-null 'Uuid) {}])

(defmethod ->graphql-type :string
  [_ _ _ _]
  [(list 'non-null 'String) {}])

(defmethod ->graphql-type :int
  [_ _ _ _]
  [(list 'non-null 'Int) {}])

(defmethod ->graphql-type :boolean
  [_ _ _ _]
  [(list 'non-null 'Boolean) {}])

(defmethod ->graphql-type :enum
  [schema _ children _]
  (if-let [enum-gql-name (->graphql-type-name schema)]
    [(list 'non-null enum-gql-name)
     {:enums {enum-gql-name {:values (set (map name children))}}}]
    [(list 'non-null 'String) {}]))

(defmethod ->graphql-type :time/instant
  [_ _ _ _]
  [(list 'non-null 'Date) {}])

(defmethod ->graphql-type :maybe
  [_ _ children _]
  (let [[[child-type child-types]] children]
    [child-type child-types]))

(defmethod ->graphql-type :vector
  [_ _ children _]
  (let [[[child-type child-types]] children]
    [(list 'list child-type) child-types]))

(defmethod ->graphql-type :map
  [schema _ field-entries _]
  (let [;; Process walked field entries - each is [fname opts [ftype ftypes]]
        processed-fields (map ->graphql-field field-entries)
        ;; Separate field definitions from their type collections
        field-results (into {} (comp (remove nil?) (map first)) processed-fields)
        field-types (reduce (fn [acc entry]
                              (if-let [[_ ftypes] entry]
                                (merge acc ftypes)
                                acc))
                            {}
                            processed-fields)

        graphql-type (->graphql-type-name schema)
        implements-refs (-> schema mc/properties :graphql/implements)
        ;; implements-refs are just type name keywords like [:Node], not schemas to walk
        implements (when implements-refs (vec implements-refs))
        all-types field-types]
    (cond
      (-> schema mc/properties :graphql/type)
      (let [type-def (cond-> {:fields field-results}
                       (seq implements) (assoc :implements implements))]
        [(list 'non-null graphql-type)
         (collect-types all-types :objects graphql-type type-def)])

      (-> schema mc/properties :graphql/interface)
      [(list 'non-null graphql-type)
       (collect-types all-types :interfaces graphql-type {:fields field-results})]

      :else
      [(list 'non-null field-results) all-types])))

(defmethod ->graphql-type :multi
  [schema _ children _]
  (let [[member-names all-types]
        (reduce (fn [[names types] [_dispatch-key _dispatch-value [child-type child-types]]]
                  [(conj names child-type)
                   (merge types child-types)])
                [[] {}]
                children)
        type-name (->graphql-type-name schema)]
    [(list 'non-null type-name)
     (collect-types all-types :unions type-name {:members member-names})]))

(defmethod ->graphql-type :merge
  [schema _ _ _]
  (mc/walk (mc/deref schema) ->graphql-type))

(defmethod ->graphql-type :any
  [_ _ _ _]
  [nil {}])

(defn- extract-object-fields
  "Extract fields from an object type reference, returning just the fields map."
  [type-ref types-map object-type]
  (if (keyword? type-ref)
    (get-in types-map [object-type type-ref :fields])
    type-ref))

(defn- compile-function
  "Extract the arguments from the malli schema and convert it into a graphql schema.
  Returns [field-def types-map] where field-def is the field definition
  and types-map contains discovered type definitions.

  This walker needs to handle `:=>` schemas by processing the args `:cat` and return type,
  but delegate actual type walking to `->graphql-type`."
  [type object-type]
  (fn
    [schema _ children _]
    (case (mc/type schema)
      :=> (let [[args-schema return-schema] (mc/children schema)
                ;; Walk the :cat args schema to extract argument map
                [args-type args-types] (mc/walk args-schema (compile-function type object-type))
                ;; Walk the return schema with ->graphql-type
                [return-type return-types] (mc/walk return-schema ->graphql-type)
                combined-types (merge args-types return-types)]
            [(cond-> {:type return-type}
               args-type (assoc :args args-type))
             combined-types])
      :cat (do
             (when-not (= 3 (count children))
               (throw (ex-info "field resolvers must be 3-arity fns" {:arg-count (count children)})))
             (let [[_ctx-schema args-schema _val-schema] (mc/children schema)
                   object-category (if (= object-type :Mutation) :input-objects :objects)
                   [args-type args-types] (mc/walk args-schema ->graphql-type)
                   extracted-args (extract-object-fields args-type args-types object-category)]
               [extracted-args args-types]))
      ;; For other schemas, delegate to ->graphql-type
      (mc/walk schema ->graphql-type))))

(defn ->graphql-schema
  "Converts a map of GraphQL resolver definitions into a Lacinia-compatible GraphQL schema.

  This function transforms Malli schemas describing resolver functions into a complete
  GraphQL schema that includes objects, interfaces, unions, enums, and input objects.
  It walks through each resolver's type signature, extracting type information and
  building a cohesive schema structure.

  ## Input Format

  The `resolver-map` is a map where:
  - Keys are tuples of `[object-type field-name]`, e.g., `[:Query :user]` or `[:Mutation :createUser]`
  - Values are tuples of `[malli-schema resolver-fn]`

  The Malli schema must describe a 3-arity resolver function using the `:=>` (function) schema:

  ```clojure
  [:=> [:cat context-schema args-schema value-schema] return-schema]
  ```

  Where:
  - `context-schema` - Usually `:any`, represents the GraphQL context
  - `args-schema` - A `:map` schema defining GraphQL field arguments (or `:any` for no args)
  - `value-schema` - Usually `:any`, represents the parent value in nested resolvers
  - `return-schema` - The return type schema

  ## Output Format

  Returns a map with the following structure:

  ```clojure
  {:objects {...}          ;; GraphQL object types and Query/Mutation
   :interfaces {...}       ;; GraphQL interface types
   :unions {...}           ;; GraphQL union types
   :enums {...}            ;; GraphQL enum types
   :input-objects {...}}   ;; GraphQL input object types (for mutations)
  ```

  ## Examples

  ### Simple Query with No Arguments

  ```clojure
  (->graphql-schema
    {[:Query :hello]
     [[:=> [:cat :any :any :any] :string]
      (fn [context args value] \"world\")]})

  ;; Returns:
  {:objects
   {:Query
    {:fields
     {:hello {:type '(non-null String)}}}}}
  ```

  ### Query with Arguments

  ```clojure
  (->graphql-schema
    {[:Query :greet]
     [[:=> [:cat :any [:map [:name :string]] :any] :string]
      (fn [context {:keys [name]} value]
        (str \"Hello, \" name))]})

  ;; Returns:
  {:objects
   {:Query
    {:fields
     {:greet {:type '(non-null String)
              :args {:name {:type '(non-null String)}}}}}}}
  ```

  ### Query Returning Custom Object

  ```clojure
  (def User
    [:map {:graphql/type :User}
     [:id :uuid]
     [:name :string]
     [:email :string]])

  (->graphql-schema
    {[:Query :user]
     [[:=> [:cat :any [:map [:id :uuid]] :any] User]
      (fn [context {:keys [id]} value]
        (fetch-user id))]})

  ;; Returns:
  {:objects
   {:Query
    {:fields
     {:user {:type '(non-null :User)
             :args {:id {:type '(non-null Uuid)}}}}}
    :User
    {:fields
     {:id {:type '(non-null Uuid)}
      :name {:type '(non-null String)}
      :email {:type '(non-null String)}}}}}
  ```

  ### Mutation with Input Object

  ```clojure
  (->graphql-schema
    {[:Mutation :createUser]
     [[:=> [:cat :any
            [:map [:input [:map {:graphql/type :CreateUserInput}
                           [:name :string]
                           [:email :string]]]]
            :any]
       [:map {:graphql/type :User}
        [:id :uuid]
        [:name :string]
        [:email :string]]]
      (fn [context {:keys [input]} value]
        (create-user input))]})

  ;; Returns:
  {:objects
   {:Mutation
    {:fields
     {:createUser {:type '(non-null :User)
                   :args {:input {:type '(non-null :CreateUserInput)}}}}}
    :User
    {:fields
     {:id {:type '(non-null Uuid)}
      :name {:type '(non-null String)}
      :email {:type '(non-null String)}}}}
   :input-objects
   {:CreateUserInput
    {:fields
     {:name {:type '(non-null String)}
      :email {:type '(non-null String)}}}}}
  ```

  ### Union Types

  ```clojure
  (def SearchResult
    [:multi {:graphql/type :SearchResult
             :dispatch :type}
     [:user [:map {:graphql/type :User} [:id :uuid] [:name :string]]]
     [:org [:map {:graphql/type :Organization} [:id :uuid] [:orgName :string]]]])

  (->graphql-schema
    {[:Query :search]
     [[:=> [:cat :any [:map [:query :string]] :any] [:vector SearchResult]]
      (fn [context {:keys [query]} value]
        (search query))]})

  ;; Returns:
  {:objects
   {:Query
    {:fields
     {:search {:type '(list (non-null :SearchResult))
               :args {:query {:type '(non-null String)}}}}}
    :User {...}
    :Organization {...}}
   :unions
   {:SearchResult {:members [:User :Organization]}}}
  ```

  ## Notes

  - Only `:Query` and `:Mutation` top-level objects are processed
  - Field names are automatically converted to camelCase
  - Type names use PascalCase
  - All discovered types (nested objects, enums, etc.) are collected and included in the output
  - For mutations, object types in args become `:input-objects` instead of `:objects`
  - Fields marked with `{:graphql/hidden true}` are excluded from the schema"
  [resolver-map]
  (reduce (fn [acc [[object field] tuple]]
            (if (contains? #{:Query :Mutation} object)
              (let [[field-def field-types] (mc/walk (first tuple) (compile-function :field object))
                    merged-types (merge-with merge acc field-types)]
                (assoc-in merged-types [:objects object :fields (csk/->camelCaseKeyword field)] field-def))
              acc))
          {}
          resolver-map))

(defn- ->tag-map
  [schema _ children _]
  (case (mc/type schema)
    ::mc/schema (when-let [gql-type (mc/walk (mc/deref schema) ->tag-map)]
                  (if (= gql-type :map) (->graphql-type-name schema) gql-type))
    :multi (into {} (map (juxt first last)) children)
    :map (or (->graphql-type-name schema) :map)
    :merge (mc/walk (mc/deref schema) ->tag-map)
    nil))

(defn merge-tag-with-type
  "Takes a Malli schema (typically a :multi schema representing a GraphQL union or interface)
  and returns a function. This returned function, when given a data instance (model),
  uses the original schema's dispatch function to determine the model's concrete type
  and then returns the corresponding GraphQL type name (as a keyword).
  This is primarily used by Lacinia's :tag-with-type to resolve concrete types
  for unions and interfaces at query time."
  [schema]
  (let [derefed (-> schema mc/schema mc/deref)
        dispatch-fn (-> derefed mc/properties :dispatch)
        tag-map (mc/walk derefed ->tag-map)]
    (fn [model]
      (get tag-map (dispatch-fn model)))))
