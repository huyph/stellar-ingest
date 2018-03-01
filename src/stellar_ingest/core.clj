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

(ns stellar-ingest.core
  (:require
   ;; I/O.
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   ;; Category theory types.
   [cats.core :as cats]
   [cats.monad.either :as either])
  (:import
   ;; Kafka streams: use full URI to ensure clients are found.
   (org.apache.kafka.clients.producer KafkaProducer ProducerConfig ProducerRecord))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: ingest uses  exceptions to signal all errors, they're  not thrown out
;; of  functions, but  captured  in a  either monad.  To  consider: have  base
;; functions that throw and wrapper functions that catch and return eiter.
;;
;; Java built-in exceptions:
;; https://www.tutorialspoint.com/java/java_builtin_exceptions.htm

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read/parse CSV.

(defn read-csv-data
  "Reads a CSV file and parse it  into a lazy sequence. Return the sequence or
  any processing exception wrapped inside an either monad."
  [input]
  (either/try-either
   (with-open [reader (io/reader input)]
     ;; ERROR: with doall here, the lazy sequence gets loaded in memory.
     (doall
      (csv/read-csv reader)))))


(def f "/home/amm00b/CSIRO/DATA/Spammer/Ingestor_Test/cusersdata.csv.full")
(def s "/home/amm00b/CSIRO/DATA/Spammer/Ingestor_Test/small.csv")

(def f (with-open [reader (io/reader "/home/amm00b/CSIRO/DATA/Spammer/Ingestor_Test/cusersdata.csv.full")]
         (csv/read-csv reader)))

(def ls (csv/read-csv (io/reader f)))
(def lss (csv/read-csv (io/reader s)))

(take 3 lss) (["userId" "sex" "timePassedValidation" "ageGroup" "isSpammer"] ["1" "M" "0.9" "30" "0"] ["2" "F" "1" "20" "0"])

(take 3 ls) (["userId" "sex" "timePassedValidation" "ageGroup" "isSpammer"] ["1" "M" "0.9" "30" "0"] ["2" "F" "1" "20" "0"])
(take 10 ls) 

(count ls) 5607448 ;; This brought me to 5GB, but I do have the seq head...

;; This seems to be the only way to avoid memory usage...
;; (- 2735 2667) 68 --> Footprint 60MB, must be the chunck it load in memory to work with...
;; This small footprint may become significant when the ingestor runs as service!
;; And of course, we need it to close the file...
;; It would seem that the huge memory consumption is from Clojure data structures bookkeepign,
;; which sounds weird... Maybe the REPL keeps track of stuff....

(count (csv/read-csv (io/reader f)))
(count (csv/read-csv (io/reader s)))
;; So it's about being functional. I must compose...

;; Slurping is actually a good solution, since we know max file size...
(def fc (slurp f))
;; This closes the reader... but then how is it lazy???
(def fcs (line-seq (io/reader f)))
(take 10 fcs)

;; Try and convert the slurped string into what read-csv would do.
;; Where does the memory foot print come from?
(split-with #(re-matches #"\n" %) fc)

(def csvs (with-open [reader (io/reader s)]
            (doall
             (csv/read-csv reader))))

(def csvf (with-open [reader (io/reader f)]
            (doall
             (csv/read-csv reader))))

;; 1.4->5.8: with doall read-csv... Madness!!!

(take 3 csvf)
(["userId" "sex" "timePassedValidation" "ageGroup" "isSpammer"]
 ["1" "M" "0.9" "30" "0"]
 ["2" "F" "1" "20" "0"])

(def mylist (doall (repeat 5000000 ["1" "M" "0.9" "30" "0"])))

(type mylist)

;; 1500

;; (line-seq (BufferedReader. (StringReader. "1\n2\n\n3")))
(def scsv (csv/read-csv fc)) ;; With slurp and this, I get the lazy seq.
(def scsv (doall (csv/read-csv fc))) ;; So, doall read-csv is the deadly combo.

;; doseq is like doall, but doens't keep the sequence head (which doall returns)

;; Final test: it is the Clojure data structures that add overhead!
;; 1476
(def f "/home/amm00b/CSIRO/DATA/Spammer/Ingestor_Test/cusersdata.csv.full")
;; 1477
(def fc (slurp f))
;; 1622
(def myvec
  (into []
        (map #(into [] (clojure.string/split % #","))
             (clojure.string/split-lines fc))))
;; (- 5537 1476) 4061 --> 4GB to read a 120MB CSV file!!!

;; Using just 'repeat' on a sample datum doesn't show any change,
;; bacause it's reusing stuff.
(defn bvec [x] (vector (str x)
                       (str (reduce str (take 1 (repeatedly #(rand-nth "MF")))))
                       (str "0." (reduce str (take 4 (repeatedly #(rand-nth "0123456789")))))
                       (reduce str (take 2 (repeatedly #(rand-nth "0123456789"))))
                       (str (reduce str (take 1 (repeatedly #(rand-nth "01")))))))
;; 1592
(def mylist (into [] (map bvec (range 1 5607448))))
;; (- 4520 1592) 2928 --> 3GB just to build the data structure.


;; This doesn't seem to change memory consumption! It reuses stuff!



;; TODO: change this function so that it only opens the reader and returns the
;; lazy seq with the open reader. Problem is, closing when it's done.
;; This would be clear in OO...


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Trivial sampler returning the first elements in a collection.

;; TODO: source and sampling policy will be abstracted.
;; TODO: Consider making errors maps, reporting code (symbol) and problematic value.

(defn sample-data
  "Given  a collection  (data) and  an  optional number  of samples  (samples,
  default value  100), return the  first samples elements from  the collection
  wrapped in an either monad. If data is not a collection or samples is is not
  a positive integer, return the appropriate error code as either/left."
  ([data] (sample-data 100))
  ([data samples]
   (either/try-either
    (cond
      (not (coll? data))
        (throw (IllegalArgumentException. "Input data must be a collection."))
      (not (and (integer? samples) (> samples 0)))
        (throw (IllegalArgumentException. "Number of samples must be a positive integer."))
      :else
        (take samples data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Get a sample from a file. Works by doing monad binding of read-csv-data and
;; sample-data. Note: bind type signature (>>=)  :: m a -> (a -> m b) -> m b

;; Swap order of sample-data parameters to allow partial application.
(defn- data-sample [n d] (sample-data d n))

(defn sample-csv-file
  "Given  a CSV file path (file) and  an  optional number  of samples (samples,
  default value 100), return the first  samples lines from the file, as parsed
  by read-csv-data,  wrapped in an  either monad. It  is assumed that  the CSV
  file has  a header line,  which is  not counted in  the samples. In  case of
  error the  returned either/left  will wrap  I/O errors  or errors  caught by
  function sample-data."
  ([file] (sample-csv-file 100))
  ([file samples]
   ;; Consider using cats/mlet to make more readable.
   (let [data (cats/bind (read-csv-data file) (fn [x] (cats/return (rest x))))]
     (cats/bind data (partial data-sample samples)))))

;; TODO: find a better naming pattern to distinguish getting data vs. metadata.
(defn read-csv-file-header
  ""
  [file]
  (cats/bind (read-csv-data file) (fn [x] (cats/return (first x)))))
  
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn write-json-lines
  ""
  [dataseq output]
  (with-open [writer (clojure.java.io/writer output)]
    (doseq [line dataseq]
      (.write writer (str line "\n")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn csv-data->maps
  "Turn CSV data into a sequence of maps."
  [csv-data & columns]
  (let [
        keys (if (nil? columns)
               (->> (first csv-data)
                    (map keyword)
                    repeat)
               (repeat columns))
        csv-data (if (nil? columns)
                   (rest csv-data)
                   csv-data)]
    (map zipmap keys csv-data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

;; EPGM-JSON vertices:
;;
;; {"id":"000000000000000000000000",
;;  "data":
;;  {"name":"Dave",
;;   "gender":"m",
;;   "city":"Dresden",
;;   "age":40
;;   },
;;  "meta":{"label":"Person",
;;          "graphs":["000000000000000002000000",
;;                    "000000000000000002000001",
;;                    "000000000000000002000002",
;;                    "000000000000000002000004"]}}

(defn vertex-json
  ""
  [vid]
  (str "{\"id\":\"" vid "\","
       "\"data\":{},"
       "\"meta\":{}}"))

(defn maps->vertex-sequence
  ""
  [csv-maps]
  ;; We flatten the sequence of vertex pairs.
  (apply concat
         (for [{src :src dst :dst} csv-maps]
           [(vertex-json src)
            (vertex-json dst)])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

;; Gradoop  VertexFactory  creates the  edge  id  automatically, if  it's  not
;; provided during construction.  But looks like the JSON  reader requires the
;; ID to be there. We use java.util.UUID/randomUUID.

;; EPGM-JSON edges:
;;
;; {"id":"000000000000000001000000",
;;  "source":"000000000000000000000005",
;;  "target":"000000000000000000000003",
;;  "data":{},
;;  "meta":{"label":"hasTag",
;;          "graphs":["000000000000000002000004"]}}


;; For now no attributes, just src and dst...
(defn edge-json
  ""
  [eid sid did & attrs]
  (str "{\"id\":\"" eid "\","
       "\"source\":\"" sid "\","
       "\"target\":\"" did "\","
       "\"data\":{},"
       "\"meta\":{}}"))

(defn maps->edge-sequence
  ""
  [csv-maps]
  ;; We flatten the sequence of vertex pairs.
  (for [edge csv-maps]
    (let [;;
          eid (java.util.UUID/randomUUID)
          ;;
          sid (:src edge)
          did (:dst edge)
          ;;
          attrs (dissoc edge :src :dst)]
      (edge-json eid sid did attrs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn create-message-producer
  "Creates  a producer  that can  be used  to send  a message  to Kafka.   The
  argument is a string containing a list of brokers (server:port) separated by
  commas, e.g. \"localhost:9092\"."
  [brokers]
  ;; Note, props could also be initialized with the notation:
  ;; {"bootstrap.servers" "localhost:9092", etc.}
  (let [props (java.util.Properties.)]
    (doto props
      (.setProperty ProducerConfig/BOOTSTRAP_SERVERS_CONFIG brokers)
      (.setProperty ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG
                    "org.apache.kafka.common.serialization.StringSerializer",)
      (.setProperty ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG
                    "org.apache.kafka.common.serialization.StringSerializer"))
    (KafkaProducer. props)))

(defn send-message
  "Send a string message to Kafka"
  [producer topic content]
  (let [message (ProducerRecord. topic content)]
    (.send producer message)))

