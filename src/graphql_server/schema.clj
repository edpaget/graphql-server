(ns graphql-server.schema
  "GraphQL schema generation from Malli schemas.

  This namespace transforms Malli schemas describing resolver functions into Lacinia-compatible
  GraphQL schemas. The main entry point is [[->graphql-schema]], which takes a map of resolver
  definitions and produces a complete GraphQL schema including objects, interfaces, unions,
  enums, and input objects.

  ## Resolver Schema Format

  Resolvers are defined using Malli `:=>` (function) schemas that describe 3-arity functions:

  ```clojure
  [:=> [:cat context-schema args-schema value-schema] return-schema]
  ```

  Where:
  - `context-schema` - Usually `:any`, represents the GraphQL context
  - `args-schema` - A `:map` schema defining GraphQL field arguments (or `:any` for no args)
  - `value-schema` - Usually `:any`, represents the parent value in nested resolvers
  - `return-schema` - The return type schema

  ## Type Annotations

  Malli schemas use special properties to control GraphQL type generation:

  - `{:graphql/type :TypeName}` - Marks a `:map` as a GraphQL object type
  - `{:graphql/interface :InterfaceName}` - Marks a `:map` as a GraphQL interface
  - `{:graphql/implements [Schema1 Schema2]}` - Object implements interfaces (must reference schemas, not keywords)
  - `{:graphql/hidden true}` - Excludes a field from the GraphQL schema

  ## Examples

  ### Simple Query

  ```clojure
  (->graphql-schema
    {[:Query :hello]
     [[:=> [:cat :any :any :any] :string]
      (fn [context args value] \"world\")]})
  ```

  ### Query with Arguments

  ```clojure
  (->graphql-schema
    {[:Query :greet]
     [[:=> [:cat :any [:map [:name :string]] :any] :string]
      (fn [context {:keys [name]} value]
        (str \"Hello, \" name))]})
  ```

  ### Custom Object Types

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
  ```

  ### Interfaces and Implementation

  Interface schemas must be defined and referenced as schema values in `:graphql/implements`:

  ```clojure
  (def Node
    [:map {:graphql/interface :Node}
     [:id :uuid]])

  (def User
    [:map {:graphql/type :User
           :graphql/implements [Node]}  ; Reference schema, not keyword
     [:id :uuid]
     [:name :string]])
  ```

  ### Union Types

  ```clojure
  (def SearchResult
    [:multi {:graphql/type :SearchResult
             :dispatch :type}
     [:user [:map {:graphql/type :User} ...]]
     [:org [:map {:graphql/type :Organization} ...]]])
  ```

  ### Mutations with Input Objects

  Input objects are automatically created for mutation arguments:

  ```clojure
  {[:Mutation :createUser]
   [[:=> [:cat :any
          [:map [:input [:map {:graphql/type :CreateUserInput}
                         [:name :string]
                         [:email :string]]]]
          :any]
     [:map {:graphql/type :User} ...]]
    (fn [context {:keys [input]} value]
      (create-user input))]}
  ```

  ## Output Schema Format

  The generated schema is a map containing:

  ```clojure
  {:objects {...}          ; GraphQL object types and Query/Mutation
   :interfaces {...}       ; GraphQL interface types
   :unions {...}           ; GraphQL union types
   :enums {...}            ; GraphQL enum types
   :input-objects {...}}   ; GraphQL input object types (for mutations)
  ```

  ## Naming Conventions

  - Field names are converted to camelCase
  - Type names use PascalCase
  - Only `:Query` and `:Mutation` top-level objects are processed"
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

(defn- strip-non-null
  "Strips the `non-null` wrapper from a GraphQL type if present.

  Converts `(non-null Type)` to `Type`. Returns the type unchanged if not wrapped."
  [graphql-type]
  (if (and (seq? graphql-type) (= 'non-null (first graphql-type)))
    (second graphql-type)
    graphql-type))

(defn- ->graphql-field
  "Process a walked map field entry.

  Input is `[field-name opts [field-type field-types]]` where the field schema has been walked.
  Returns `[[field-name field-def] field-types]` or nil if hidden.

  Optional keys (`:optional true` in Malli) become nullable types in GraphQL by stripping
  the `non-null` wrapper from the field type."
  [[field-name opts [field-type field-types]]]
  (when-not (or (:graphql/hidden opts) (nil? field-type))
    (let [final-type (if (:optional opts)
                       (strip-non-null field-type)
                       field-type)
          field-def  (cond-> {:type final-type}
                       (:graphql/description opts) (assoc :description (:graphql/description opts)))]
      [[(csk/->camelCaseKeyword field-name) field-def]
       field-types])))

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

(defmethod ->graphql-type :double
  [_ _ _ _]
  [(list 'non-null 'Float) {}])

(defmethod ->graphql-type :float
  [_ _ _ _]
  [(list 'non-null 'Float) {}])

(defmethod ->graphql-type :enum
  [schema _ children _]
  (if-let [enum-gql-name (->graphql-type-name schema)]
    (let [description (-> schema mc/properties :graphql/description)
          enum-def    (cond-> {:values (set (map name children))}
                        description (assoc :description description))]
      [(list 'non-null enum-gql-name)
       {:enums {enum-gql-name enum-def}}])
    (throw (ex-info "enum schemas must have :graphql/type property"
                    {:schema (mc/form schema)}))))

(defmethod ->graphql-type :time/instant
  [_ _ _ _]
  [(list 'non-null 'Date) {}])

(defmethod ->graphql-type :maybe
  [_ _ children _]
  ;; child-type arrives in a maybe is always '(non-null Type)
  ;; :maybe extracts and returns just Type
  (let [[[[_ child-type] child-types]] children]
    [child-type child-types]))

(defmethod ->graphql-type :vector
  [_ _ children _]
  (let [[[child-type child-types]] children]
    [(list 'list child-type) child-types]))

(defmethod ->graphql-type :map
  [schema _ field-entries _]
  (let [;; Process walked field entries - each is [fname opts [ftype ftypes]]
        processed-fields              (map ->graphql-field field-entries)
        implements-refs               (-> schema mc/properties :graphql/implements)
        ;; implements-refs are refs to other schemas
        [implements implements-types] (when (seq implements-refs)
                                        (->> (map mc/deref implements-refs)
                                             (map #(mc/walk %1 ->graphql-type))
                                             (reduce (fn [accum [[_ interface] new-types]]
                                                       (-> (update accum 0 conj interface)
                                                           (update 1 merge new-types)))
                                                     [[] {}])))
        ;; Separate field definitions from their type collections
        field-results                 (into {} (keep first) processed-fields)
        all-types                     (reduce (fn [acc entry]
                                                (if-let [[_ ftypes] entry]
                                                  (merge acc ftypes)
                                                  acc))
                                              implements-types
                                              processed-fields)

        graphql-type                  (->graphql-type-name schema)
        props                         (mc/properties schema)
        description                   (:graphql/description props)]
    (cond
      (:graphql/type props)
      (let [type-def (cond-> {:fields field-results}
                       (seq implements) (assoc :implements implements)
                       description (assoc :description description))]
        [(list 'non-null graphql-type)
         (collect-types all-types :objects graphql-type type-def)])

      (:graphql/interface props)
      (let [type-def (cond-> {:fields field-results}
                       description (assoc :description description))]
        [(list 'non-null graphql-type)
         (collect-types all-types :interfaces graphql-type type-def)])

      :else
      [(list 'non-null field-results) all-types])))

(def ^:private multi-merge (partial merge-with merge))

(defmethod ->graphql-type :multi
  [schema _ children _]
  (let [[member-names all-types]
        (reduce (fn [accum [_dispatch-key _dispatch-value [[_ child-type] child-types]]]
                  (-> (update accum 0 conj child-type)
                      (update 1 multi-merge child-types)))
                [[] {}]
                children)
        type-name                                                                        (->graphql-type-name schema)]
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
  [type-ref types-map]
  (if (keyword? type-ref)
    (get-in types-map [:objects type-ref :fields])
    type-ref))

(defn- compile-function
  "Extract the arguments from the malli schema and convert it into a graphql schema.
  Returns [field-def types-map] where field-def is the field definition
  and types-map contains discovered type definitions.

  This walker needs to handle `:=>` schemas by processing the args `:cat` and return type,
  but delegate actual type walking to `->graphql-type`."
  [object-type]
  (fn
    [schema _ children _]
    (case (mc/type schema)
      :=> (let [[args-schema return-schema] (mc/children schema)
                ;; Walk the :cat args schema to extract argument map
                [[_ args-type] args-types]  (mc/walk args-schema (compile-function object-type))
                ;; Walk the return schema with ->graphql-type
                [return-type return-types]  (mc/walk return-schema ->graphql-type)

                args-category               (if (= object-type :Mutation) :input-objects :objects)
                args-objects                (:objects args-types)
                combined-types              (-> (dissoc args-types :objects)
                                                (cond->
                                                 args-objects (assoc args-category args-objects))
                                                (merge return-types))]
            [(cond-> {:type return-type}
               args-type (assoc :args args-type))
             combined-types])
      :cat (do
             (when-not (= 3 (count children))
               (throw (ex-info "field resolvers must be 3-arity fns" {:arg-count (count children)})))
             (let [[_ctx-schema args-schema _val-schema] (mc/children schema)
                   [args-type args-types]                (mc/walk args-schema ->graphql-type)
                   extracted-args                        (extract-object-fields args-type args-types)]
               [extracted-args args-types]))
      ;; For other schemas, delegate to ->graphql-type
      (mc/walk schema ->graphql-type))))

(defn ->graphql-schema
  "Converts resolver definitions into a Lacinia-compatible GraphQL schema.

  Takes a `resolver-map` where keys are `[object-type field-name]` tuples (e.g., `[:Query :user]`)
  and values are `[malli-schema resolver-fn]` or `[malli-schema resolver-fn description]` tuples.
  The Malli schema must describe a 3-arity resolver function using `[:=> [:cat context args value] return-type]`.
  The optional description string will be added to the GraphQL field.

  Returns a map containing `:objects`, `:interfaces`, `:unions`, `:enums`, and `:input-objects`
  with the complete GraphQL schema. All nested types are automatically discovered and registered.

  See the namespace documentation for detailed examples and usage patterns."
  [resolver-map]
  (reduce (fn [acc [[object field] tuple]]
            (if (contains? #{:Query :Mutation} object)
              (let [[schema _resolver description] tuple
                    [field-def field-types]        (mc/walk schema (compile-function object))
                    field-def-with-desc            (cond-> field-def
                                                     description (assoc :description description))
                    merged-types                   (merge-with merge acc field-types)]
                (assoc-in merged-types [:objects object :fields (csk/->camelCaseKeyword field)] field-def-with-desc))
              acc))
          {}
          resolver-map))
