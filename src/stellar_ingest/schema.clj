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

(ns stellar-ingest.schema
  ;; TODO: check if any is unnecessary.
  (:require [stellar-ingest.core :as core]
            ;; Logging
            [clojure.tools.logging :as log]
            ;; I/O.
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            ;; Cheshire JSON library
            [cheshire.core :as json])
  (:import
   (;; Stellar graph data structures and utilities
    sh.serene.stellarutils.model.epgm
    ;; ElementId    ;; ID object for graphs, vertices and edges
    GraphHead    ;; Graph entry point
    ;; VertexCollection  ;; Vertex object (not a Java-collection)
    GraphCollectionBuilder
    Properties) ;; Property map wrapper with simple accessors
   )
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn load-schema
  ""
  [file] ;; The schema JSON file
  (json/parse-stream
   (clojure.java.io/reader file)
   (fn [k] (keyword (clojure.string/replace k #"^@" "stellar-")))))


(defn load-csv
  ""
  [file]
  (deref (cats/fmap core/csv-data->maps (core/read-csv-data file))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn extract-node-property
  ""
  [m  ;; A single node class mapping from the schema
   p  ;; The node property to extract
   l] ;; A line of data to use for extracting the property
  (let [;; Get the column identifier corresponding to property p.
        c (keyword (-> m p :column))]
    ;; If the property is  part of the schema (otherwise c in  nil) and if the
    ;; corresponding column is part of  the data line, return a property-value
    ;; map, else return nil.
    (if (contains? l c)
      {p (-> l c)}
      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; This returns a collection of single property maps ar of nils.
(defn build-property-map
  ""
  [m  ;; A single node class mapping from the schema
   ps ;; A collection of node properties to etract
   l] ;; A line of data to use for extracting the properties
  (for [p ps]
    (extract-node-property m p l)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn apply-node-mapping
  "Given a single node mapping definition and  a line of CSV data, build a map
  representing the node."
  [m  ;; The node mapping
   l] ;; The line of data

  (let [;; Extract the type name (node label)
        type (:stellar-type m)
        ;; Extract the ID
        id (:stellar-id (extract-node-property m :stellar-id l))
        ;; Build a list of all properties
        plist (remove #{:stellar-type}
                      (remove #{:stellar-id}
                              (for [p m] (key p))))
        ;; Create a map associating each property with its value.
        pmap (into {} (build-property-map m plist l))
        ;; Add the original id to the properties
        pmap (assoc pmap :__id id)]
    ;; TODO: check for nils and consider returning an either for missing data.
    ;;
    ;; Create  a  map collecting  all  the  information  needed for  the  node
    ;; constructor.
    {:label type :id id :props pmap}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn apply-all-node-mappings
  "Given the schema and a line of data apply all node mappings."
  [s  ;; The graph schema
   l] ;; A line of data
  (let [;; Extract node mappings from the schema
        ms (-> s :mapping :nodes)]
  (for [m ms] (apply-node-mapping m l))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn preprocess-link-mapping
  "Given the  schema and single link  mapping from it, create  an intermediate
  map that will be used by the data parser to construct links of this type."
  [s  ;; Graph schema
   l] ;; Link mapping to preprocess
  (let [;; From the mapping, extract name (label) and src/dest columns.
        name (-> l :stellar-type :name)
        src-col (-> l :stellar-src :column)
        dst-col (-> l :stellar-dest :column)
        ;; Find link definition by name and get src/dest types.
        defs (-> s :graphSchema :classLinks)
        def (first (filter #(= name (:name %)) defs))
        src-type (:source def)
        dst-type (:target def)]
    ;; Build a map to be used by the data parser to construct links.
    {:label name
     :src-label src-type :src-col src-col
     :dst-label dst-type :dst-col dst-col}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn preprocess-all-link-mappings
  "Given the schema, preprocess all  link mappings, creating intermediate maps
  used for link construction."
  [s] ;; Graph schema
  (let [maps (-> s :mapping :links)]
    (for [l maps] (preprocess-link-mapping s l))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn apply-link-mapping
  "Given a  single (preprocessed) link  mapping definition  and a line  of CSV
  data, build a map representing the link."
  [m  ;; The link mapping - i.e. the intermediate map from preprocessing
   l] ;; The line of data
  (-> m
      (assoc :src-val ((keyword (:src-col m)) l))
      (assoc :dst-val ((keyword (:dst-col m)) l))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn apply-all-link-mappings
  "Given the schema and a line of data apply all link mappings."
  [s  ;; The graph schema
   l] ;; A line of data
  (let [;; Preprocess link mappings
        ms (preprocess-all-link-mappings s)]
    (for [m ms] (apply-link-mapping m l))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn clj-map-to-properties
  "Convert a Clojure map to a stellar utils properties object."
  [m]
  (let [smap (into {} (map (fn [x] {(name (key x)) (val x)}) m))
        jmap (Properties/create)]
    (doall
     (map (fn [x] (.add jmap (first x) (second x))) smap))
    (.getMap jmap)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn create-node-maps
  "Applies  the  schema  to  the  whole  CVS  file  content,  generating  maps
  representing all nodes. Duplicate nodes are removed."
  [scm  ;; Schema
   dat] ;; CSV data
  (->>
   (for [l dat] (apply-all-node-mappings scm l))
   flatten
   (group-by :id)
   (map (fn [x] (first (val x))))
   (sort-by :id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn create-link-maps
  "Applies  the  schema  to  the  whole  CVS  file  content,  generating  maps
  representing all links."
  [scm  ;; Schema
   dat] ;; CSV data
  (->>
   (for [l dat] (apply-all-link-mappings scm l))
   flatten))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn populate-graph-collection-builder
  "This function  marks the  limit between the  the ingestor's  native clojure
  code and the  stellar.util java library.  Maps representing  nodes and links
  are  converted  to  the  corresponding   util  object  and  collected  in  a
  GraphCollectionBuilder object, from which the distributed graph is created."
  [vs  ;; Maps of vertices
   es  ;; Maps of edges
   gl] ;; Graph label
  (let [;; Create a GraphCollectionBuilder object.
        gcb (new GraphCollectionBuilder)
        ;; Add a labelled graph to it and store the graph id.
        gid (.addGraphHead gcb (.getMap (Properties/create)) gl)
        ;; A  lookup table  of node  IDs is  created, to  track correspondence
        ;; between  stellar.util  node  identifiers and  the  original  source
        ;; identifiers.
        ;; Each  node map  is  used to  build  a  node and  append  it to  the
        ;; GraphCollectionBuilder.  Appending  has side  effects and  doall is
        ;; required.
        nid-lut (doall (map
                        (fn [v] (let [label (:label v)
                                      props (clj-map-to-properties (:props v))
                                      oid (:__id (:props v))]
                                  {:orig (str label oid)
                                   :graph (.addVertex gcb props label (list gid))}))
                        vs))]
    ;; After all nodes are created and appended, do the same with links.
    (doall (map
            (fn [e] (let [label (:label e)
                          src-orig (str (:src-label e) (:src-val e))
                          dst-orig (str (:dst-label e) (:dst-val e))
                          src-id (:graph (first (filter #(= src-orig (:orig %)) nid-lut)))
                          dst-id (:graph (first (filter #(= dst-orig (:orig %)) nid-lut)))]
                      (.addEdge gcb src-id dst-id (.getMap (Properties/create)) label (list gid))))
            es))
    ;; Return the GraphCollectionBuilder
    gcb))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn write-graph-to-gdf
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in GDF format."
  [gcb   ;; A GraphCollectionBuilder object
   path] ;; Output path
  (-> gcb
      .toGraphCollection
      .write
      (.gdf path)))

(defn write-graph-to-json
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in JSON (EPGM) format."
  [gcb   ;; A GraphCollectionBuilder object
   path] ;; Output path
  (-> gcb
      .toGraphCollection
      .write
      (.json path)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; To run it from a terminal use:
;; java -cp /path/to/stellar-ingest-*-standalone.jar stellar_ingest.schema schema_file csv_file graph_label
;;
;; Requested file path can be either absolute or relative to the directory the
;; above command is issued in.

(defn -main [& args]
  (let [scm-file (first args)
        dat-file (second args)
        glabel (nth args 2)
        scm (load-schema scm-file)
        dat (load-csv dat-file)
        graph (populate-graph-collection-builder
               (create-node-maps scm dat)
               (create-link-maps scm dat)
               glabel)]
    (write-graph-to-gdf graph (str "/tmp/" glabel ".gdf"))
    (write-graph-to-json graph (str "/tmp/" glabel ".json"))))

















