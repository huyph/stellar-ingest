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
            ;; Cheshire JSON library
            [cheshire.core :as json])
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

(defn load-csv
  ""
  [file]
  (deref (cats/fmap core/csv-data->maps (core/read-csv-data file))))

(defn add-path-to-sources
  [scm-file]
  (let [scm (load-schema scm-file)
        dir (utils/path-basename scm-file)]
    (into [] (map (partial utils/make-path dir) (:sources scm)))))

(defn get-subschema-by-source
  [scm src]
  (let [ns (filter #(= (-> % :stellar-id :source) src)
                   (-> scm :mapping :nodes))
        ls (filter #(= (-> % :stellar-src :source) src)
                   (-> scm :mapping :links))]
    (-> scm
     (assoc-in [:mapping :nodes] (into [] ns))
     (assoc-in [:mapping :links] (into [] ls)))))

(defn load-project
  [scm-file]
  (let [dir (utils/path-basename scm-file)
        scm (load-schema scm-file)]
    (into [] (zipmap 
              (map (partial utils/make-path dir) (:sources scm))
              (map (partial get-subschema-by-source scm) (:sources scm))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Preprocess  the  schema  mapping,   making  node  type  information  redily
;; available to the functions that instantiate links.

;; This functionality is already present in preprocess-all-link-mappings.

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

;; StellarBackEndFactory bef = new LocalBackEndFactory();
;; StellarGraphBuffer graph = bef.createGraph("label", props=null);
;;
;; try-catch: for duplicated names
;; for (v : vertices)
;; 	graph.addVertex("name", "label", props);
;;
;; for (e : edges)
;; 	graph.addEdge("name", "src name", "dst name", "label", props);
;;
;; graph.toGraph().toCollection().write().json("out.epgm");


(defn populate-graph
  "This function  marks the  limit between the  the ingestor's  native clojure
  code and the  stellar.util java library.  Maps representing  nodes and links
  are  converted  to  the  corresponding   util  object  and  collected  in  a
  GraphCollectionBuilder object, from which the distributed graph is created."
  [vs  ;; Maps of vertices
   es  ;; Maps of edges
   gl] ;; Graph label
  (let [es (zipmap (range 0 (count es)) es)
        ;;
        grbef (new LocalBackEndFactory)
        ;; 
        graph (.createGraph grbef gl (.getMap (Properties/create)))

        ;; A  lookup table  of node  IDs is  created, to  track correspondence
        ;; between  stellar.util  node  identifiers and  the  original  source
        ;; identifiers.
        ;; Each  node map  is  used to  build  a  node and  append  it to  the
        ;; GraphCollectionBuilder.  Appending  has side  effects and  doall is
        ;; required.
        nid-lut (doall (map
                        (fn [v] (let [label (:label v)
                                      props (clj-map-to-properties (:props v))
                                      oid (str label (:__id (:props v)))]
                                  {:orig (str label oid)
                                   :graph (try
                                            (.addVertex graph oid label props)
                                            (catch Exception e (str "caught exception: " (.getMessage e)))
                                            (finally nil))}))
                        vs))]
    ;; After all nodes are created and appended, do the same with links.
    (doall (map
            (fn [ed] (let [e (val ed)
                           label (:label e)
                           src-orig (str (:src-label e) (:src-val e))
                           dst-orig (str (:dst-label e) (:dst-val e))
                           edgeid (str (key ed))]
                      {:label label :src src-orig :dst dst-orig :status
                       (try 
                         (.addEdge graph edgeid src-orig dst-orig label (.getMap (Properties/create)))
                         (catch Exception e)
                         (finally nil)
                         )}))
            es))
    ;; Return the  Graph. If this is  commented out, the edge  lookup table is
    ;; returned, useful for debugging edge construction.
    graph
    ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn write-graph-to-gdf
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in GDF format."
  [graph  ;; A StellarGraphBuffer object
   path] ;; Output path
  ;; (throw (new Exception "- Not yet implemented!"))
  (-> graph
      .toGraph
      .toCollection
      .write
      (.gdf path)))

(defn write-graph-to-json
  "Extract a graph  collection for the corresponding builder  object and write
  it to file in JSON (EPGM) format."
  [graph  ;; A StellarGraphBuffer object
   path]  ;; Output path
  (-> graph
      .toGraph
      .toCollection
      .write
      (.json path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; With introduction of multi-source input the  schema is treated as a project
;; file, from which  a project object is constructed. This  contains a list of
;; the source files (full path) each with the corresponding subschema.

(defn create-maps-from-project
  [pro]
  (let [;; Build node maps from all sources.
        ns (map #(create-node-maps (second %) (load-csv (first %))) pro)
        ;; Build link maps from all sources.
        ls (map #(create-link-maps (second %) (load-csv (first %))) pro)]
    {:nodes (into [] (flatten ns)) :links (into [] (flatten ls))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; To run it from a terminal use:
;; java -cp /path/to/stellar-ingest-*-standalone.jar stellar_ingest.schema schema_file graph_label
;;
;; Requested file path can be either absolute or relative to the directory the
;; above command is  issued in. The sources in the  schema should specified as
;; files (without  directory) and are assumed  to be in the  same directory as
;; the schema.

(defn -main [& args]
  (let [glabel (second args)
        scm-file (first args)
        pro (load-project scm-file)
        maps (create-maps-from-project pro)
        graph (populate-graph (:nodes maps) (:links maps) glabel)]
    (write-graph-to-gdf graph (str "/tmp/" glabel ".gdf"))
    (write-graph-to-json graph (str "/tmp/" glabel ".json"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scratch

(comment

  (def scm-file (io/resource "examples/imdb_norm/imdb_norm_schema.json"))
  ;; (def scm (load-schema scm-file))
  ;; scm
  (def pro (load-project scm-file))
  pro

  (def maps (create-maps-from-project pro))
  maps
  (def graph (populate-graph (:nodes maps) (:links maps) "testg"))

  ;; graph.toGraph().toCollection().write().json("out.epgm");
  (-> graph
      .toGraph
      .toCollection
      .write
      (.json "/tmp/testg.epgm"))

  (-> graph
      .toGraph
      .toCollection
      .write
      (.gdf "/tmp/testg.gdf"))

  ;; TODO:  for testing  if would  be nice  to have  fully reproducible  graph
  ;; construction.  Must introduce debug functions  that sort the maps to make
  ;; comparing them easier.  Also must ask Kevin to modify id creation, making
  ;; it swappable.  We  can introduce a mock progressive-number  id that stays
  ;; the same between runs (if things  are constructed in the same order). And
  ;; a hash-based id, that is the same if  the object is the same and, in case
  ;; of conflicts, has  defined ordering (e.g. hash order is  same as original
  ;; lexicographic order). Also useful would be to allow passing ids, but that
  ;; may  break  the interface...  (would  require  special versions  of  each
  ;; function that take id  or a special property tag that  contains the id to
  ;; use).
  
  ) ;; End comment

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; TODO:
  ;;
  ;; Current solutionu lets us build any graph easily. There's still the issue
  ;; of using IDs of objects that don't exist yet... But can still be solved
  ;; with doing first all vertex mappings the all edges.

  ;; ;; Normalized example
  ;; Person.csv: id name address
  ;; Dog.csv: reg name breed
  ;; Owner.csv: owner(=Person.id) dog(=Dog.reg)
  ;;
  ;; schema: owns: Owner.owner Owner.dog

  ;; ;; Unnormalized example
  ;; Person.csv: id name address dog1 dog2 dog3
  ;; Dog.csv: reg name breed
  ;;
  ;; schema: owns: Person.id Person.dog1
  ;; schema: owns: Person.id Person.dog2
  ;; schema: owns: Person.id Person.dog3

  ;; Later: we could could have a special marker for "subelement"
  ;; that splits a list argument as edge destination and builds multiple.
  
  ;; YES:
  ;; 1) 1 record of 1 file is always enough to build a vertex/edge!
  ;; --> We go through all files twice. First run apply (to all files) vertex mappings
  ;;     and second edge mappings.
  ;;     This can be the first form of schema check we do... later schema should not allow.
  ;;
  ;; We can also accommodate NILs is CSVs by not building the corresponding edge.
  ;;
  ;; 2) Both forms speak for qualifying original IDs with vertex class
  ;;    and not with source name.
  ;;    2a) When normalized the source name has nothing to do with both IDs
  ;;        which belong to different tables.
  ;;    2b) When unnormalized the source ID is in the corresponding source, but
  ;;        destination ID is somewhere else.

  ;; Implementation works by preprocessing the schema. Nodes are loaded as
  ;; expected, but the ids are fully qualified with the node type.
  ;;
  ;; Afterwards, for each link mapping, the schema definition (no the mapping)
  ;; is looked up to the source and destination type are found, so the src and
  ;; dest id can be fully qualified.

  ;; TODO: Schema validation:
  ;; - there's at least a mapping for each object to be created.
  ;; - all fields are there
  ;; - no nodes/link built from multiple sources
  ;; - no duplicates in mappings (there may be more mapping for one type
  ;;     but they shouldn't use all the same fields).
  ;; - no duplicate names in the properties.
  ;; - check that src and dst of links are ids of the 
  ;; - every source-field pair used only once (i.e. in one mapping,
  ;;     but same field could be id and a property).
