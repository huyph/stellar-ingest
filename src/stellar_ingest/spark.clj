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

(ns stellar-ingest.spark
  ;; TODO: check if any is unnecessary.
  (:require [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            [stellar-ingest.schema-validator :as vtor]
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
            [clj-memory-meter.core :as mm]
            ;;
            [flambo.conf :as conf]
            [flambo.api :as f]
            [flambo.kryo :as k]

            )
  ;; (:import
  ;;  (sh.serene.stellarutils.entities Properties)
  ;;  (sh.serene.stellarutils.graph.api StellarBackEndFactory StellarGraph StellarGraphBuffer)
  ;;  (sh.serene.stellarutils.graph.impl.local LocalBackEndFactory))
  (:gen-class))

(comment

(def c (-> (conf/spark-conf)
           (conf/master "local")
           (conf/app-name "flame_princess")))

;; (def sc (f/spark-context c))

(def data (f/parallelize sc [["a" 1] ["b" 2] ["c" 3] ["d" 4] ["e" 5]]))

(f/foreach data println)

(f/first data)

;; This actually works! Would be nice to see how it come out in scala...
(def data (f/parallelize sc [[1 {:a 1 :b "a"}] [2 {:a 2 :b "b"}]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(f/save-as-text-file data "/tmp/data.rdd")

(def srdd (k/serialize data))

(def data2 (k/deserialize srdd))

;; data2 lacks a spark context...
(f/foreach data2 println)

;; spark.read.json("test.json", multiLine=True)







) ;; End comment

