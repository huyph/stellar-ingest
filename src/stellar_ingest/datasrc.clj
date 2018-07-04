;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This file is part of stellar-ingest, Stellar data ingestion module.
;;
;; Copyright 2017-2018 CSIRO Data61
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

(ns stellar-ingest.datasrc
  ;; TODO: check if any is unnecessary.
  (:require [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            ;; Logging
            [clojure.tools.logging :as log]
            ;; I/O.
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            ;; String manipulation
            [clojure.string]
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            [cats.context :as ctx]
            ;; Cheshire JSON library
            [cheshire.core :as json]
            ;; Memory meter
            [clj-memory-meter.core :as mm])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

;; Unused.
;; (defn load-csv "" [file]
;;   (core/csv-data->maps (core/file-line-parse-seq file (comp first csv/read-csv))))

;; In the future  there should be a unique function  that accepts multiple types
;; of data source (CSV, DB, etc.) and creates node/links.
;;
;; (defn data-source-to-seq
;;   "Given a data source specification and  a conversion function, return a sequence
;;   of node/link maps, as internally used by the ingestor."
;;   ;; For backward compatibility if a string  is passed, it is interpreted as CSV
;;   ;; path with default parameters. For CSVs a source specification map will also
;;   ;; be included, that allows parameters (e.g. headers yes/no, separator, ...).
;;   [src  ;; The data source specification (map or string).
;;    fun] ;; The function converting from data elements to nodes/edges.
;;   (if (string? src)
;;     (map fun src)
;;     (throw (Exception. (str "Map datasources aren't yet supported.")))))

;; Line-by-line file reader implemented as a reducible sequence.
(defn- lines-reducible [^java.io.BufferedReader rdr]
  (reify clojure.lang.IReduceInit
    (reduce [this f init]
      (try
        (loop [state init]
          (if (reduced? state)
            state
            (if-let [line (.readLine rdr)]
              (recur (f state line))
              state)))
        (finally (.close rdr))))))

;; Eduction presenting a parsed CSV file, i.e. a sequence of records.
(defn parse-csv-file-reducible [file]
  (eduction (map (comp first csv/read-csv))
            (lines-reducible (io/reader file))))

;; Parse JSON lines.
;; (defn parse-json-file-reducible [file]
;;   (eduction (map #(json/decode % true))
;;             (lines-reducible (io/reader file))))

;; Stateful transducer that performs the CSV record to map transformation.
(defn csv-data->maps-trans []
  (fn [rf]
    (let [cols (atom nil)]
      (fn
        ([] (rf))
        ([res] (rf res))
        ([res inp] (if (nil? @cols)
                     (do (reset! cols (into [] (map keyword inp))) res)
                     (rf res (zipmap @cols inp))))))))
