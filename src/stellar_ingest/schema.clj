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
            [clj-memory-meter.core :as mm])
  (:import
   (sh.serene.stellarutils.entities Properties)
   (sh.serene.stellarutils.graph.api StellarBackEndFactory StellarGraph StellarGraphBuffer)
   (sh.serene.stellarutils.graph.impl.local LocalBackEndFactory))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

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

(defn load-csv "" [file]
  (core/csv-data->maps (core/file-line-parse-seq file (comp first csv/read-csv))))

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
           (map (partial apply-all-node-mappings scm) dat))))

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
           (map (partial apply-all-link-mappings scm) dat))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper  functions to  populate  the  graph. The  final  graph  consists of  a
;; StellarGraphBuffer object. The  ingestor transforms the input  data into lazy
;; sequences of intermediate objects (maps)  that represent the nodes and edges,
;; as defined in the schema.

(defn- populate-graph-nodes-fn [graph]
  (fn [v]
    (let [label (:label v)
          propm (:props v)
          propj (clj-map-to-properties propm)
          oldid (:__id propm)
          newid (str label oldid)
          n (:stellar-count v)]
      (try
        ;; A log line every 250k seems good...
        (if (= 0 (mod n 250000))
          (log/info (str (new java.util.Date) " - ingested " n " graph nodes.")))
        ;; (println (str "Node: " v))
        (.addVertex graph newid label propj)
        (catch Exception e
          (str "caught exception: " (.getMessage e)))
        (finally nil)))))

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
        ;; A log line every 250k seems good...
        (if (= 0 (mod n 250000))
          (log/info (str (new java.util.Date) " - ingested " n " graph links.")))
        ;; (println (str "Link: " e))
        (.addEdge graph newid src-orig dst-orig label (.getMap (Properties/create)))
        (catch Exception ex
          (println (str "caught exception: " (.getMessage ex))))
        (finally nil)
        ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn populate-graph
  "This function  marks the  limit between the  the ingestor's  native clojure
  code and the  stellar.util java library.  Maps representing  nodes and links
  are  converted  to  the  corresponding   util  object  and  collected  in  a
  GraphCollectionBuilder object, from which the distributed graph is created."
  [vs  ;; Maps of vertices
   es  ;; Maps of edges
   gl] ;; Graph label
  (let [;; Select graph memory backend: local memory.
        grbef (new LocalBackEndFactory)
        ;; Create and empty graph, that will be populated element-wise.
        graph (.createGraph grbef gl (.getMap (Properties/create)))]

    ;; Process all nodes.
    (log/info (str (new java.util.Date) " - Starting node ingestion."))
    (dorun (map (populate-graph-nodes-fn graph) (vs)))
    (log/info (str (new java.util.Date)
                   " - Finished ingesting "
                   (:stellar-count (last (vs)))
                   " graph nodes."))

    ;; Process all links.
    (log/info (str (new java.util.Date) " - Starting link ingestion."))
    (dorun (map (populate-graph-links-fn graph) (es)))
    (log/info (str (new java.util.Date)
                   " - Finished ingesting "
                   (:stellar-count (last (es)))
                   " graph links."))

    ;; Return the graph.
    graph))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn write-graph-to-gdf
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
      (.gdf path))
  )

(defn write-graph-to-json
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in JSON (EPGM) format."
  [graph  ;; A StellarGraphBuffer object
   path]  ;; Output path
  ;; (clojure.pprint/pprint graph (clojure.java.io/writer path))
  (-> graph
      .toGraph
      .toCollection
      .write
      (.json path))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; With introduction of multi-source input the  schema is treated as a project
;; file, from which  a project object is constructed. This  contains a list of
;; the source files (full path) each with the corresponding subschema.
(defn create-maps-from-project
  [pro]
  {:nodes
   (fn []
     (map #(assoc-in %1 [:stellar-count] (inc %2))
          (apply concat
                 (map #(create-node-maps (second %) (load-csv (first %))) pro))
          (range)))
   :links
   (fn []
     (map #(let [x (assoc-in %1 [:stellar-count] (inc %2))]
             (if (nil? (get-in x [:props :__id]))
               (assoc-in x [:props :__id] %2) x))
          (apply concat
                 (map #(create-link-maps (second %) (load-csv (first %))) pro))
          (range)))})

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
      ((cats/lift-m populate-graph) ns ls lab)
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
        ;; Function write-graph-to-json  creates intermediate dirs  and consumes
        ;; all I/O exceptions. It returns status as boolean.
        (if (write-graph-to-json (deref graph) out-file)
          (println (str "Saved EPGM graph to " out-file))
          (do (println (str "I/O error saving graph to " out-file))
              (utils/exit 1)))
        (do (println (str "Error: " (deref graph))) (utils/exit 1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scratch
;;
;;
;; java -Xmx16g -cp ./target/uberjar/stellar-ingest-0.1.1-SNAPSHOT-standalone.jar stellar_ingest.schema /home/ubuntu/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal_short.json /tmp/lj lj
;; top -bH -p $(ps aux|grep "java -Xmx16g -cp"|grep -v grep|sed 's/\t/ /g;s/  */ /g'|cut -d" " -f2) 2>&1|tee mem.log
;; 
(comment

  (def scm-file "/home/amm00b/CSIRO/DATA/Stellar/Ingestor/livejournal/livejournal.json")
  (def scm (load-schema scm-file))
  (def scm (assoc scm :label "gtest"))
  (def scm (assoc scm :schema-file scm-file))
  (def scm (vtor/validate-schema scm))
  ;; scm

  (def pro (cats/bind scm schema-to-project))
  ;; pro

  (def graph (ingest-project pro))
  ;; graph
  
  (def maps (create-maps-from-project (deref pro)))
  ;; maps
    
  ;; Lazily write out the maps and time the process.
  (time (with-open [w (clojure.java.io/writer  "/tmp/nodes.edn")]
          (doseq [n ((:nodes maps))]
            (.write w (str n "\n")))))
  
  (time (with-open [w (clojure.java.io/writer  "/tmp/links.edn")]
          (doseq [l ((:links maps))]
            (.write w (str l "\n")))))

  ;; Count nodes and links in maps and time the process.
  (time (count ((:nodes maps)))) 
  (time (count ((:links maps))))

  ;; Populate the utils graph and write it out.
  (def graph (populate-graph (:nodes maps) (:links maps) "testg"))

  ;; graph.toGraph().toCollection().write().json("out.epgm");
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
