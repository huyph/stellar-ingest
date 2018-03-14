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

(ns stellar-ingest.app
  (:require [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            [clojure.tools.cli :as cli]
            [cats.core :as cats]
            [cats.monad.either :as either])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Definitions needed by the main function.

(def ^{:private true} cli-banner
  (str "--- " (utils/get-application-banner) "\n"
       "Copyright 2017, CSIRO Data61 - All rights reserved\n"))

(def ^{:private true} cli-options
  ;; An option with a required argument
  [["-h" "--help"]
   ["-i" "--input INPUT" "Input CSV file"
    :missing "Missing input CSV file (option '-i')."]
   ["-c" "--channel" "Output channel or topic"
    :default "epgm_topic"]
   ["-s" "--servers" "Message server or broker (e.g. \"server1:port,server2:port\")"
    :default "localhost:9092"]
   ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Process command line parameters and  decides whether to print an error/help
;; message.   The  three  parameters  are  the  values  of  :options,  :errors
;; and :summary in  the map returned by tools.cli/parse-opts.   Return true if
;; the program should exit.

(defn- check-help-message
  [opts errs summ]
  (cond
    (:help opts) (do
                   (println cli-banner)
                   (println "Command line options:")
                   (println (:summary opts))
                   true)
    (not (nil? errs)) (do
                        (println cli-banner)
                        (println "The following errors were encountered:")
                        (doseq [e errs] (println (str "  " e)))
                        (println)
                        (println "Command line options:")
                        (println summ)
                        true)
    :else false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main function.

;; Example: process a CSV file and write EPGM data to disk.
;; (let [indata (csv-data->maps (read-csv-data "/path/to/test/file"))]
;;   (write-json-lines (maps->vertex-sequence indata) "/tmp/vout.txt")
;;   (write-json-lines (maps->edge-sequence indata) "/tmp/eout.txt"))

(defn -main [& args]
  (let [;; Parse command line arguments
        {opts :options args :arguments summ :summary errs :errors}
        (cli/parse-opts args cli-options)
        ;; Set in-cider to true in running interactively with Cider
        in-cider (not (nil? (resolve 'cider.nrepl.version/version)))]
    (if (check-help-message opts errs summ)
      (if in-cider
        (println "\n*** EXITING PROGRAM WITH CODE 1")
        (System/exit 1))
      (let [input (:input opts)
            servers (:servers opts)
            channel (:channel opts)
            ;; Main was originally not written with  cats. This is a quick fix
            ;; to prevent it from crashing  and burning when running the Kafka
            ;; stream demo.
            indata (cats/fmap core/csv-data->maps (core/read-csv-data input))
            producer (core/create-message-producer servers)
            vseq (cats/fmap core/maps->vertex-sequence indata)
            eseq (cats/fmap core/maps->edge-sequence indata)]
        (if (either/right? vseq)
          (doseq [l (deref vseq)] (core/send-message producer channel l))
          (println (str "Error in vertex seq: " (deref vseq))))
        (if (either/right? eseq)
          (doseq [l (deref eseq)] (core/send-message producer channel l))
          (println (str "Error in edge seq: " (deref eseq))))))))








