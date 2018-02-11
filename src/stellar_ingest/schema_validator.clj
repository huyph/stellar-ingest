;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This file is part of stellar-ingest, Stellar data ingestion module.
;;
;; Copyright 2017 CSIRO Data61
;;
;; Licensed under the Apache License, Version 2.0 (the "License"); you may not
;; use this file except in compliance with the License.  You may obtain a copy
;; of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless  required  by applicable  law  or  agreed  to in  writing,  software
;; distributed under the  License is distributed on an "AS  IS" BASIS, WITHOUT
;; WARRANTIES OR CONDITIONS  OF ANY KIND, either express or  implied.  See the
;; License  for the  specific language  governing permissions  and limitations
;; under the License.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns stellar-ingest.schema-validator
  ;; TODO: check if any is unnecessary.
  (:require ;; [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            ;; Logging
            [clojure.tools.logging :as log]
            ;; I/O.
            ;; [clojure.data.csv :as csv]
            ;; [clojure.java.io :as io]
            ;; String manipulation
            ;; [clojure.string]
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            ;; Cheshire JSON library
            ;; [cheshire.core :as json]
            )
  ;; (:import
  ;;  (sh.serene.stellarutils.entities Properties)
  ;;  (sh.serene.stellarutils.graph.api StellarBackEndFactory StellarGraph StellarGraphBuffer)
  ;;  (sh.serene.stellarutils.graph.impl.local LocalBackEndFactory))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn resolve-source
  "
  Given a  source identifier (string, as  used in schema mappings)  and a schema
  map, return the path to the source (file) as string. The identifier is used to
  select a path specified in the :sources section of the schema. An exception is
  thrown in  case of  no match  or multiple  matches. If  the resulting  path is
  relative, append the path to :schema-file's directory, if such tag is present.
  "
  [src  ;; Source identifier string, as specified in schema mappings.
   scm] ;; The schema map.
  (let [lst (:sources scm)    ;; List of sources from schema.
        scf (:schema-file scm) ;; The schema file, if specified.
        ;; The schema-file directory, if available, or "".
        cwd (if (nil? scf) "" (utils/path-basename scf))
        ;; TODO: Should check src not empty string and lst vector.
        res (utils/resolve-path-shorthand src lst)]
    (if (empty? res)
      (throw (Exception. (str "Unknown data source: " src)))
      (if (> (count res) 1)
        (throw (Exception. (str "Ambiguous data source: " src)))
        ;; If res is  an absolute path, make-path will return  it unchanged.  If
        ;; it's relative it  will be combined with cwd, which  is either a valid
        ;; directory (ending with the path separator) or the empty string.
        (utils/make-path cwd (first res))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn validate-schema
  "
  Given a schema, validate it and return  it wrapped in an either/right monad or
  return the validation error as either/left. The return schema will contain the
  toplevel entry :validated set to true.
  "
  [scm]
  (if (map? scm)
    (either/right (assoc scm :validated true))
    (either/left "Wrong type for schema: map expected.")))
