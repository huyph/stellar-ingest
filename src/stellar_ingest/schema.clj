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

(ns stellar-ingest.schema
  ;; TODO: check if any is unnecessary.
  ;; TODO:  this  namespace  has  grown  to  include  too  many  things  (schema
  ;; management,   creation   of   intermediate  representations,   writing   of
  ;; output). Divide functions by topic and assign to new namespaces.
  (:require [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            [stellar-ingest.schema-validator :as vtor]
            [stellar-ingest.datasrc :as dsrc]
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
            ;; Used to test functions as parameters
            [clojure.test :refer [function?]])
  (:import
   (sh.serene.stellarutils.entities Properties)
   (sh.serene.stellarutils.graph.api StellarBackEndFactory StellarGraph StellarGraphBuffer)
   (sh.serene.stellarutils.graph.impl.local LocalBackEndFactory))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to deal with the schema and to convert input data using it.

(defn load-schema
  ""
  [file] ;; The schema JSON file
  (json/parse-stream
   (clojure.java.io/reader file)
   (fn [k] (keyword (clojure.string/replace k #"^@" "stellar-")))))

(defn write-schema [scm file]
  (json/generate-stream
   scm
   (clojure.java.io/writer file)
   {:key-fn (fn [k] (clojure.string/replace (name k) #"^stellar-" "@"))
    :pretty true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Given a schema map  and a source return a copy of  the schema containing only
;; node and link  mappings that use that particular source. Include a mapping if
;; its :source string is a shorthand for the source passed as parameter.
(defn- get-subschema-by-source
  [scm src]
  ;; Extract relevant mappings for nodes (ns) and links (ls).
  (let [ns (filter
            #(>= (utils/check-path-shorthand (-> % :stellar-id :source) src) 0)
            (-> scm :mapping :nodes))
        ls (filter
            #(>= (utils/check-path-shorthand (-> % :stellar-src :source) src) 0)
            (-> scm :mapping :links))]
    ;; The filtered  mappings replace  the full  ones, so that  the rest  of the
    ;; original schema is still available in the submappings.
    ;; TODO: think if it makes sense to filter also classes/links.
    (-> scm
     (assoc-in [:mapping :nodes] (into [] ns))
     (assoc-in [:mapping :links] (into [] ls)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Take a schema map and transform it into an ingestor project, which associates
;; each  source with  the portion  of  schema (mappings)  which references  that
;; particular source:  [ [path1 schema1]  [path2 schema2] [path3  schema3] ...].
;; Return  such vector  wrapped in  either/right.   If the  input schema  didn't
;; undergo validation or an exception is thrown, wrap the error in either/left.
(defn- schema-to-project [scm]
  (if (:validated scm)
    (either/try-either
     (into [] (zipmap
               ;; Resolve sources against themselves: if :schema-file is present
               ;; its directory is used to expand relative paths.
               (map #(vtor/resolve-source % scm) (:sources scm))
               (map (partial get-subschema-by-source scm) (:sources scm)))))
    (either/left "Attempting to use schema before it was validated.")))

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
    (for [m ms] (apply-node-mapping m l))
    ;; (into {} l)
    ))

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
    (dorun
     (map (fn [x] (.add jmap (first x) (second x))) smap))
    (.getMap jmap)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn create-node-maps
  "Applies  the  schema  to  the  whole  CVS  file  content,  generating  maps
  representing all nodes. Duplicate nodes are removed."
  [scm  ;; Schema
   dat] ;; CSV data
  (apply concat
         ;; If  empty mappings, skip processing input file.
         (if (empty? (-> scm :mapping :nodes))
           (lazy-seq)
           ;; DB: here the data source must be hooked. Ideally, to maintain good
           ;; encapsulation there should be a source-to sequence function called
           ;; that  receives the  apply-... function  and runs  it. This  way we
           ;; don't have to put with-open etc. in this namespace.
           ;;
           (map (partial apply-all-node-mappings scm) dat)

           )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn create-link-maps
  "Applies  the  schema  to  the  whole  CVS  file  content,  generating  maps
  representing all links."
  [scm  ;; Schema
   dat] ;; CSV data
  (apply concat
         ;; If  empty mappings, skip processing input file.
         (if (empty? (-> scm :mapping :links))
           (lazy-seq)
           (map (partial apply-all-link-mappings scm) dat)
           )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph  construction backends  convert Ingestor's  intermediate representation
;; into a graph object suitable for encoding into EPGM. The '-utils' one creates
;; a StellarGraphBuffer  object and is about  to be deprecated in  favour of the
;; native one.  A  dispatch function 'populate-graph', defaulting  to native, is
;; provided  to  hide   the  implementation  and  make   source  code  backwards
;; compatible.

;; Helper function to populate nodes of the stellar-utils graph backend.
(defn- populate-graph-nodes-fn [graph]
  (fn [v]
    (let [label (:label v)
          propm (:props v)
          propj (clj-map-to-properties propm)
          oldid (:__id propm)
          newid (str label oldid)
          n (:stellar-count v)]
      (try (.addVertex graph newid label propj)
           (catch Exception e
             (log/error (str "Error adding node: " (.getMessage e))) nil)))))

;; Helper function to populate links of the stellar-utils graph backend.
(defn- populate-graph-links-fn [graph]
  (fn [e]
    (let [label (:label e)
          propm (:props e)
          propj (clj-map-to-properties propm)
          oldid (:__id propm)
          newid (str label oldid)
          src-orig (str (:src-label e) (:src-val e))
          dst-orig (str (:dst-label e) (:dst-val e))
          n (:stellar-count e)]
      (try
        (.addEdge graph newid src-orig dst-orig label (.getMap (Properties/create)))
        (catch Exception ex
          (log/error (str "Error adding link: " (.getMessage ex))) nil)))))

;; Graph backend  using stellar-utils:  deprecated, because  the utils  load the
;; entire graph in memory.
(defn populate-graph-utils
  "This function  marks the  limit between the  the ingestor's  native clojure
  code and the  stellar.util java library.  Maps representing  nodes and links
  are  converted  to  the  corresponding   util  object  and  collected  in  a
  GraphCollectionBuilder object, from which the distributed graph is created."
  ;; TODO: this  function expects  lazy sequences as  input and  stopped working
  ;; when  stellar-ingest  switched to  using  transducers  internally. Must  be
  ;; converted to keep on working for backward compatibility.
  [vs  ;; Maps of vertices
   es  ;; Maps of edges
   gl] ;; Graph label
  (log/info "Entering function populate-graph-utils.")
  (let [;; Select graph memory backend: local memory.
        grbef (new LocalBackEndFactory)
        ;; Create and empty graph, that will be populated element-wise.
        graph (.createGraph grbef gl (.getMap (Properties/create)))]
    
    ;; Process all nodes.
    (log/info (str (new java.util.Date) " - Starting node ingestion."))
    (dorun (sequence (comp (map (populate-graph-nodes-fn graph))
                           (utils/element-log-tran #(log/info %) 250000 "nodes"))
                     vs))
    (log/info (str (new java.util.Date) " - Completed node ingestion."))
    
    ;; Process all links.
    (log/info (str (new java.util.Date) " - Starting link ingestion."))
    (dorun (sequence (comp (map (populate-graph-links-fn graph))
                           (utils/element-log-tran #(log/info %) 250000 "links"))
                     es))
    (log/info (str (new java.util.Date) " - Completed link ingestion."))

    ;; Return the graph.
    (log/info "Leaving function populate-graph-utils.")
    graph))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph  construction backends  convert Ingestor's  intermediate representation
;; into  a graph  object  suitable for  encoding into  EPGM.   The native  graph
;; construction backend returns a map with sequences of graphs, nodes and links.
;; The three sequences  are actually eductions that will  perform data ingestion
;; upon reduction.
;;
;; Utility: add graph ID to a node/link, that a :graphs key pointing to a vector
;; of graph IDs.
(defn- add-graph-id
  [id x]
  (assoc x :graphs [id]))

;; Utility: convert internal node representation to EPGM-compatible.
;; Assumes :graphs key points to a vector of graph IDs.
(defn- node-map-to-epgm [n]
  (let [label  (:label n)
        graphs (:graphs n)
        propm  (:props n)
        oldid  (:__id propm)
        newid  (str label oldid)]
    {:id newid
     :meta {:label label :graphs graphs}
     :data propm}))

;; Utility: convert internal link representation to EPGM-compatible.
;; Assumes :graphs key points to a vector of graph IDs.
(defn- link-map-to-epgm [e]
  (let [label (:label e)
        graphs (:graphs e)
        propm (:props e)
        oldid (:__id propm)
        newid (str label oldid)
        newsrc (str (:src-label e) (:src-val e))
        newdst (str (:dst-label e) (:dst-val e))]
    {:id newid
     :source newsrc
     :target newdst
     :meta {:label label :graphs graphs}
     :data propm}))

;; Actual native graph builder function.
(defn populate-graph-native
  "
  Build  stellar-ingest  internal  graph  object,  a  hashmap  containing  three
  sequences (graphs, nodes and links) suitable for representation as EPGM.
  "
  [vs  ;; Eduction generating vertices
   es  ;; Eduction generating edges
   gl] ;; Graph label
  (log/info "Entering function populate-graph-native.")
  (let [;; Create an identifier for the graph.
        gid (str (java.util.UUID/randomUUID))]
    (hash-map
     ;; Create a new, single EPGM graph, to which all nodes and edges belong.
     :graphs (vector (hash-map :data (hash-map)
                               :meta (hash-map :label gl)
                               :id gid))
     ;; Add the graph ID to all nodes and convert hash-maps to valid EPGM.
     :nodes (eduction
             (comp (map (partial add-graph-id gid))
                   (map node-map-to-epgm))
             vs)
     ;; Add the graph ID to all edges and convert hash-maps to valid EPGM.
     :edges (eduction
             (comp (map (partial add-graph-id gid))
                   (map node-map-to-epgm))
             es))))

;; Dispatch function, selects the graph backend, defaulting to Utils.
(defn populate-graph
  ""
  ([vs es gl] (populate-graph-utils vs es gl))
  ([vs es gl be]
   ;; The backend can be either a keyword selecting one of the ingestor built-in
   ;; backends (:native, :utils) or a function that does the job.
   (if (clojure.test/function? be)
     (apply be vs es gl)
     (if (= be :native)
       (populate-graph-native vs es gl)
       (if (= be :utils)
         (populate-graph-utils vs es gl)
         (throw (new IllegalArgumentException
                     (str "Unknown graph backend " be "."))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph  output functions.  Those with  '-utils' suffix  employ the  deprecated
;; stellar-utils methods to write to JSON and GDF.

(defn write-graph-to-gdf-utils
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in GDF format."
  [graph  ;; A StellarGraphBuffer object
   path] ;; Output path
  ;; (clojure.pprint/pprint graph (clojure.java.io/writer path))
  (throw (new Exception "- Not yet implemented!"))
  (-> graph
      .toGraph
      .toCollection
      .write
      (.gdf path)))

(defn write-graph-to-json-utils
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in JSON (EPGM) format."
  [graph  ;; A StellarGraphBuffer object
   path]  ;; Output path
  ;; (clojure.pprint/pprint graph (clojure.java.io/writer path))
  (-> graph
      .toGraph
      .toCollection
      .write
      (.json path)))

(defn write-graph-to-json-native
  "
  Write  to  JSON  the  EPGM  graph, built  using  stellar-ingest  native  graph
  backend (see function populate-graph).
  "
  [graph ;; Graph object.
   path] ;; Directory for EPGM output.
  ;; TODO: factor out common code (see  commented code below function), wrap I/O
  ;; exceptions with eithers and fmap over the individual output files.
  (and
   ;; Check is output dir exists or create it.
   (let [wd (clojure.java.io/file path)]
     (if (or (.isDirectory wd)
             (.mkdirs (clojure.java.io/file path)))
       true
       (do (log/error (str "Error creating EPGM output directory " path))
           false)))
   ;; Write graphs.
   (try
     (with-open [file (clojure.java.io/writer (str path "/graphs.json"))]
       (let [wf #(do (.write file (json/generate-string %)) (.newLine file))]
         (log/info (str (new java.util.Date) " - Starting graph ID writing."))
         (transduce (comp (utils/writing-tran wf)
                          (utils/element-log-tran #(log/info %) 250000 "graph IDs"))
                    (constantly nil) nil
                    (:graphs graph))
         (log/info (str (new java.util.Date) " - Completed graph ID writing."))
         true))
     (catch Exception e (log/error (str "Error writing EPGM graphs: "
                                        (.getMessage e))) false))
   ;; Write nodes.
   (try
     (with-open [file (clojure.java.io/writer (str path "/nodes.json"))]
       (let [wf #(do (.write file (json/generate-string %)) (.newLine file))]
         (log/info (str (new java.util.Date) " - Starting node ingestion."))
         (transduce (comp (utils/writing-tran wf)
                          (utils/element-log-tran #(log/info %) 250000 "nodes"))
                    (constantly nil) nil
                    (:nodes graph))
         (log/info (str (new java.util.Date) " - Completed node ingestion."))
         true))
     (catch Exception e (log/error (str "Error writing EPGM nodes: "
                                        (.getMessage e))) false))
   ;; Write links.
   (try
     (with-open [file (clojure.java.io/writer (str path "/links.json"))]
       (let [wf #(do (.write file (json/generate-string %)) (.newLine file))]
         (log/info (str (new java.util.Date) " - Starting link ingestion."))
         (transduce (comp (utils/writing-tran wf)
                          (utils/element-log-tran #(log/info %) 250000 "links"))
                    (constantly nil) nil
                    (:edges graph))
         (log/info (str (new java.util.Date) " - Completed link ingestion."))
         true))
     (catch Exception e (log/error (str "Error writing EPGM links: "
                                        (.getMessage e))) false))))

;; Dispatch function, that selects the  EPGM JSON output backend, defaulting the
;; the Utils.
;; TODO: the different graph construction backends should return different types
;; and the output backend should be selected automatically based on them.
(defn write-graph-to-json
  ""
  ([graph path] (write-graph-to-json-utils graph path))
  ([graph path be]
   ;; The backend can be either a keyword selecting one of the ingestor built-in
   ;; backends (:native, :utils) or a function that does the job.
   (if (clojure.test/function? be)
     (apply be graph path)
     (if (= be :native)
       (write-graph-to-json-native graph path)
       (if (= be :utils)
         (write-graph-to-json-utils graph path)
         (throw (new IllegalArgumentException
                     (str "Unknown JSON output backend " be "."))))))))

;; TODO: possible way of rewriting to avoid code duplication.
;;
;; (defn write-graph-to-json-native
;;   [graph
;;    path] ;; Directory for EPGM
;;   (let [epgms [{:f "graphs.json" :l "graph IDs" :k :graphs}
;;                {:f "nodes.json" :l "nodes" :k :nodes}
;;                {:f "links.json" :l "links" :k :edges}]
;;         ;; Common function to  write the EPGM JSON files,  using parameters from
;;         ;; the 'epgms' map defined above.
;;         ff (fn [f l k] ;; filename, name in logs, key in graph object
;;              (try
;;                (with-open [file (clojure.java.io/writer (str path "/" f))]
;;                  (let [wf #(do (.write file (json/generate-string %)) (.newLine file))]
;;                    (transduce (comp (utils/writing-tran wf)
;;                                     (element-log-tran #(log/info %) 250000 l))
;;                               (constantly nil) nil
;;                               (k graph))
;;                    true))
;;                (catch Exception e (log/error (str "Error writing EPGM nodes: "
;;                                                   (.getMessage e))))
;;                (finally false)))]
;;     ;; Create directory, map ff on epgms...
;;     )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Using the ingestor  project object, generate sequences  of maps, representing
;; nodes and  links.  These are  the ingestor intermediate  representation.  The
;; results are eductions, that encapsulate the actual sequence creation.

;; Add an index to a node/link map (as value of :stellar-count). If the original
;; ID is not saved as property (value of :__id) then add the index as ID too.
(defn- add-idx [idx el]
  (let [x (assoc-in el [:stellar-count] (inc idx))]
    (if (nil? (get-in x [:props :__id]))
      (assoc-in x [:props :__id] idx)
      x)))

;; Parse a CSV file and attach to each record the corresponding subschema.
(defn- attach-schema-to-records [file scm]
  (eduction (comp (dsrc/csv-data->maps-trans)
                  (map #(hash-map :schema scm :record %)))
            (dsrc/parse-csv-file-reducible file)))

;; Create the intermediate node/link sequences as eductions.
(defn- create-maps-from-project
  [pro]   ;; Input ingestor project.
  {:nodes ;; Eduction generating the ingestor's internal node maps.
   (eduction (comp
              ;; Filter out sources not contributing to node creation.
              (filter #(not (empty? (-> (second %) :mapping :nodes))))
              ;; Create a sequence  of records from all node  sources and attach
              ;; to each the relevant subschema.
              (mapcat #(attach-schema-to-records (first %) (second %)))
              ;; Convert  the  records into internal ingestor map.
              (mapcat #(apply-all-node-mappings (:schema %) (:record %)))
              ;; Add to each node a global index, useful e.g. to track progress.
              (map-indexed add-idx))
             pro)
   :links ;; Eduction generating the ingestor's internal link maps.
   (eduction (comp
              (filter #(not (empty? (-> (second %) :mapping :links))))
              (mapcat #(attach-schema-to-records (first %) (second %)))
              (mapcat #(apply-all-link-mappings (:schema %) (:record %)))
              (map-indexed add-idx))
             pro)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingest data and  create a graph from a project  definition. The input project
;; must be wrapped in an either.  The  result is an either wrapping the ingested
;; EPGM graph (a Stellar-Utils Graph object) or an error message.
;;
;; TODO: improve implementation to full  exploit either. Currently the functions
;; called here will always return a  right, unless an exception occurs, which is
;; captured by the outer try-catch block.
(defn- ingest-project [pro]
  (try
    (let [maps (cats/fmap create-maps-from-project pro)
          ns (cats/fmap :nodes maps)
          ls (cats/fmap :links maps)
          lab (cats/fmap (comp :label second first) pro)]
      ((cats/lift-m 3 populate-graph) ns ls lab)
      ;; maps
      )
    (catch Exception e (either/left (.getMessage e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingest a graph starting  from a schema. The schema is  passed as Clojure map,
;; wrapped in an either. If passed as second argument, add :label to the schema.
;; The schema is validated before proceeding.
(defn- ingest-schema
  ([s]
   (let [scm (cats/bind s vtor/validate-schema)
         pro (cats/bind scm schema-to-project)]
     (ingest-project pro)))
  ([s l]
   (let [scm (cats/fmap #(if (map? %) (assoc % :label (str l)) identity) s)]
     (ingest-schema scm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingest a  graph starting from  a schema file. The  file is passed  as String,
;; containing the path.  Add :schema-file to the schema and, if passed as second
;; argument, also :label.
(defn- ingest-schema-file
  ([f]
   (let [scm (either/try-either (load-schema f))
         scm (cats/fmap #(if (map? %) (assoc % :schema-file f) identity) scm)]
     (ingest-schema scm)))
  ([f l]
   (let [scm (either/try-either (load-schema f))
         scm (cats/fmap #(if (map? %) (assoc % :schema-file f) identity) scm)
         scm (cats/fmap #(if (map? %) (assoc % :label (str l)) identity) scm)]
     (ingest-schema scm))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingestion protocol: causing weird arity exception: investigate.
;;
;; Protocol defining  the ingestion workflow based  on the input: a  path to the
;; schema file, a schema map or an ingestor project.
;;
;; TODO: the protocol is just an initial attempt, it dispatches on generic types
;; (string, map)  without checking their  content.  Special types are  needed to
;; represent these elements.
;;
;; (defprotocol StellarIngestor
;;   "Define the graph ingestion workflow for different inputs."
;;   ;; Take necessary steps to create an ingestor project from the inputs and pass
;;   ;; it (as either monad) to the function ingest-project. Get back and return an
;;   ;; EPGM graph wrapped in an either monad.
;;   (ingest [x] [x opts]
;;     "Ingest data into a  graph. Parameter this can be the path  to a schema file
;;     or a Clojure  map representing the schema. Parameter ops,  if provided, is a
;;     map   containing  keys   that  should   be  added   or  overridden   in  the
;;     schema (currently  only :label  is supported, to  specify the  graph label).
;;     Return an EPGM graph wrapped in an either monad."))
;;
;; (extend-protocol StellarIngestor
;;   ;; If a file path is passed, it is assumed to be the schema file path.
;;   java.lang.String
;;   (ingest [this]
;;     (ingest-schema-file this))
;;   (ingest [this opts]
;;     (if (not (map? opts))
;;       (either/left "Invalid argument: ingestion options must be a map.")
;;       (let [l (str (:label opts))]
;;         (if (empty? l)
;;           (either/left "Invalid graph label.")
;;           (ingest-schema-file this l)))))
;;   ;; If a map is passed, it is assumed to be the non-validated schema map.
;;   clojure.lang.PersistentArrayMap
;;   (ingest [this] (ingest-schema this))
;;   (ingest [this opts]
;;     (if (not (map? opts))
;;       (either/left "Invalid argument: ingestion options must be a map.")
;;       (let [l (str (:label opts))]
;;         (if (empty? l)
;;           (either/left "Invalid graph label.")
;;           (ingest-schema this l))))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Ingest function, created as quick replacement for the protocol.
(defn ingest ""
  ;; Just schema passed: file path or map.
  ([s]
   (if (string? s)
     (ingest-schema-file s)
     (if (map? s)
       (ingest-schema (either/right s))
       (either/left "Invalid input schema: file or map expected."))))
   ;; Schema passed (file path or map) with options (map).
   ([s opts]
    (if (not (map? opts))
      (either/left "Invalid argument: ingestion options must be a map.")
      (let [l (str (:label opts))]
        (if (empty? l)
          (either/left "Invalid graph label.")
          (if (string? s)
            (ingest-schema-file s l)
            (if (map? s)
              (ingest-schema (either/right s) l)
              (either/left "Invalid input schema: file or map expected."))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; To run it from a terminal use:
;; java -cp /path/to/stellar-ingest-*-standalone.jar \
;;                             stellar_ingest.schema \
;;                                       schema_file \
;;                                        output_dir \
;;                                        graph_label
;;
;; Requested file path  can be either absolute or relative  to the directory the
;; above command  is issued in.  The sources in the  schema can be  specified as
;; absolute paths  or relative  to the  directory with the  schema file.  In the
;; schema mappings, sources can be addressed with valid shorthand (i.e. trailing
;; portion of path sufficient to uniquely identify).

(defn -main [& args]
  (if (not= (count args) 3)
    (do (println "Required parameters: schema_file output_dir graph_label")
        (utils/exit 1))
    (let [scm-file (first args)
          out-file (second args)
          glabel (nth args 2)
          graph (ingest scm-file {:label glabel})]
      (if (either/right? graph)
        ;; Function write-graph-to-json creates intermediate  dirs if needed and
        ;; consumes all I/O exceptions. It returns status as boolean.
        (if (write-graph-to-json (deref graph) out-file)
          (println (str "Saved EPGM graph to " out-file))
          (do (println (str "I/O error saving graph to " out-file))
              (utils/exit 1)))
        (do (println (str "Error: " (deref graph))) (utils/exit 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scratch
;;
;; Commands to watch Ingestor's memory consumption:
;; java -Xmx16g -cp ./target/uberjar/stellar-ingest-0.1.1-SNAPSHOT-standalone.jar stellar_ingest.schema /home/ubuntu/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal_short.json /tmp/lj lj
;; java -Xmx16g -cp ./target/uberjar/stellar-ingest-0.1.1-SNAPSHOT-standalone.jar stellar_ingest.schema /home/amm00b/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal.json /tmp/lj lj
;; top -bH -p $(ps aux|grep "java -Xmx16g -cp"|grep -v grep|sed 's/\t/ /g;s/  */ /g'|cut -d" " -f2) 2>&1|tee mem.log
;; 
(comment

  ;; Load and validate schema. Turn it into a project object.
  (do
    ;; (def scm-file "/home/amm00b/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal.json")
    ;; (def scm-file "/home/amm00b/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal_head.json")
    (def scm-file "/home/amm00b/CSIRO/WORK/Dev/Stellar/stellar-ingest-dev/resources/examples/imdb_norm/imdb_norm_schema.json")
    (def scm (load-schema scm-file))
    (def scm (assoc scm :label "gtest"))
    (def scm (assoc scm :schema-file scm-file))
    (def scm (vtor/validate-schema scm))
    (def pro (cats/bind scm schema-to-project)))

  ;; Create maps with intermediate node/link representation.
  (do
    (def maps (cats/fmap create-maps-from-project pro))
    (def nodes (cats/fmap :nodes maps))
    (def links (cats/fmap :links maps))
    (def lab (cats/fmap (comp :label second first) pro)))
  
  ;; Build a graph with the default native backend, i.e.  create EPGM maps.
  (def graph ((cats/lift-m 3 populate-graph) nodes links lab))

  ;; Write EPGM graph to directory, creating it if needed.
  (time (write-graph-to-json (deref graph) "/tmp/mygraph"))

  ;; Deprecated: using the Utils backend.
  (def graph ((cats/lift-m 4 populate-graph) nodes links lab (either/right :utils)))
  
  (-> (deref graph)
      .toGraph
      .toCollection
      .write
      (.json "/tmp/testg.epgm"))

  (-> (deref graph)
      .toGraph
      .toCollection
      .write
      (.gdf "/tmp/testg.gdf"))
  
  ) ;; End comment

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Preprocess  the  schema  mapping,   making  node  type  information  redily
;; available to the functions that instantiate links.

;; This functionality  is already present in  preprocess-all-link-mappings. Compare
;; the two implementations and take the more readable/robust.

;; (defn add-node-types-to-link-mapping
;;   "Give a link mapping map, lookup the node types in the schema and add them
;;   to the mapping information."
;;   [m]
;;   (let [;; Get the link type t from the mapping.
;;         t (:name (:stellar-type m))
;;         ;; Using  the  schema  definition,  extract  source  (:source)  and
;;         ;; destination (:target) node types for the current link type.
;;         [s d] ((juxt :source :target)
;;                ;; Find  the link  schema definition  corresponding to  name
;;                ;; t. There  must be  only one  and we  extract it  from the
;;                ;; vector returned by filter.
;;                (first
;;                 (filter
;;                  (comp (partial = t) :name)
;;                  (:classLinks (:graphSchema scm)))))]
;;     ;; Add type fields to the mapping m (inside the stellar-scr/dest maps).
;;     (-> m
;;         (assoc-in [:stellar-src :type] s)
;;         (assoc-in [:stellar-dest :type] d))))

;; (defn add-node-types-to-all-link-mappings
;;   "Given the original  schema, return the preprocessed version,  in which node
;;   type information is added for each link mapping."
;;   [scm]
;;   (assoc-in scm [:mapping :links]
;;             (into []
;;                   (map
;;                    add-node-types-to-link-mapping
;;                    (-> scm :mapping :links)))))

;; (comment
;;   ;; Quick test: (diff a  b) returns (only in a, only in b,  in both).  For an
;;   ;; addition to the schema we get: nil,  the newly added node type entries in
;;   ;; the link  mappings and the original  schema. We check that  the schema is
;;   ;; preserved.
;;   ;;
;;   ;; TODO: move this to the tests.
;;   (require '[clojure.data])
;;   (let [[old new both]
;;         (clojure.data/diff scm
;;                            (add-node-types-to-all-link-mappings scm))]
;;     (= both scm))
;;   ) ;; End comment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
