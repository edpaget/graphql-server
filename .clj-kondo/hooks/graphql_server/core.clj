(ns hooks.graphql-server.core
  (:require [clj-kondo.hooks-api :as api]))

(defn defresolver
  "Hook for graphql-server.core/defresolver macro.

  Transforms (defresolver :Query :users \"doc\" schema body...)
  into (def Query-users \"doc\" (fn body...))
  so that clj-kondo recognizes the var definition and analyzes the function body."
  [{:keys [node]}]
  (let [[_defresolver object-node action-node & args] (:children node)
        ;; Extract object and action keywords
        object-kw                                     (when (api/keyword-node? object-node)
                                                        (api/sexpr object-node))
        action-kw                                     (when (api/keyword-node? action-node)
                                                        (api/sexpr action-node))
        ;; Generate var name: object-action (e.g., Query-users)
        var-name                                      (when (and object-kw action-kw)
                                                        (symbol (str (name object-kw) "-" (name action-kw))))
        ;; Check if there's a docstring
        docstring?                                    (and (seq args)
                                                           (api/string-node? (first args)))
        docstring-node                                (when docstring? (first args))
        ;; Get schema and body - skip schema, just get the fn args and body
        [_schema & body]                              (if docstring?
                                                        (rest args)
                                                        args)
        ;; Build def form: (def name "doc" (fn ...))
        def-children                                  (if docstring?
                                                        [(api/token-node 'def)
                                                         (api/token-node var-name)
                                                         docstring-node
                                                         (api/list-node
                                                          (list*
                                                           (api/token-node 'fn)
                                                           body))]
                                                        [(api/token-node 'def)
                                                         (api/token-node var-name)
                                                         (api/list-node
                                                          (list*
                                                           (api/token-node 'fn)
                                                           body))])
        new-node                                      (api/list-node def-children)]
    {:node new-node}))

(defn def-resolver-map
  "Hook for graphql-server.core/def-resolver-map macro.

  Transforms (def-resolver-map ...) into (def resolvers \"doc\" {})
  so that clj-kondo recognizes the resolvers var and docstring."
  [{:keys [node]}]
  (let [[_def-resolver-map & args] (:children node)
        ;; Check if first arg is a docstring
        docstring?                 (and (seq args)
                                        (api/string-node? (first args)))
        docstring-node             (when docstring? (first args))
        ;; Build def form: (def resolvers "doc" {})
        def-children               (if docstring?
                                     [(api/token-node 'def)
                                      (api/token-node 'resolvers)
                                      docstring-node
                                      (api/map-node [])]
                                     [(api/token-node 'def)
                                      (api/token-node 'resolvers)
                                      (api/map-node [])])
        new-node                   (api/list-node def-children)]
    {:node new-node}))
