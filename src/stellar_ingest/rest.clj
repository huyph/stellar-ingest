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

(ns stellar-ingest.rest
  ;; TODO: check if any is unnecessary.
  (:require [stellar-ingest.core :as core]
            [stellar-ingest.utils :as utils]
            [stellar-ingest.schema :as schema]
            ;; Logging
            [clojure.tools.logging :as log]
            ;; Replace compojure.core adding swagger docs.
            [compojure.api.sweet :as cmpj :refer [GET POST]]
            [compojure.route :as route]
            [compojure.handler :as handle]
            [ring.util.response :refer [response status]]
            ;; Some response sugar.
            [ring.util.http-response :as resp]
            [ring.middleware.json :as jsonmw]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :as parsmw]
            [ring.middleware.multipart-params]
            ;; HTTP client.
            [clj-http.client :as http]
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            ;; Cheshire JSON library
            [cheshire.core :as json]
            ;; Define schemas for swagger documentation.
            [schema.core :as s])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File sampling endpoint.

(defn- do-sample-csv-file
  [{file :file samples :samples :as body}]
  (let [header (stellar-ingest.core/read-csv-file-header file)
        sample (stellar-ingest.core/sample-csv-file file samples)
        error (either/first-left [header sample])]
    (if (nil? error)
      ;; Sampling was successful.
      (response {:headers (into [] (deref header))
                 :rows (into [] (deref sample))})
      ;; Sampling encountered an error.
      (resp/bad-request (str (deref error))))))

(defn- do-sample-csv-file-parms
  [{file :file samples :samples :as parms}]
  (log/info parms)
  ;; If samples is a number pass it as number, else pass original.
  (let [n (read-string (str samples))]
    (if (number? n)
      (do-sample-csv-file {:file file :samples n})
      (do-sample-csv-file {:file file :samples samples}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test endpoint: request echo

(defn- do-show [req]
  (log/info req)
  (response (str req)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingestion endpoint.
;;
;; Ingestion  via  REST  interface   is  asynchronous.   The  ingestor  responds
;; immediately with  OK (200)  to signal  that it has  received the  request. It
;; responds with  bad request  (400) if any  of session-specific  top-level JSON
;; parameters is  empty (session  ID, graph  label, output  directory and  the 2
;; notification URLs). See function: do-ingest.
;;
;; Afterwards  function do-ingest-impl  (the full  ingestion sequence  including
;; schema  validation) is  started  in  a new  thread.  When  it completes,  the
;; coordinator is notified  via one of two URLs (see  functions ingestion-ok and
;; ingestion-abort).
;;
;; The ingestion heartbeat, part of the communication protocol, is not currently
;; implemented.
;;
;; Report on  ingestion final status.  On  success pass only the  session ID, on
;; failure also pass an error message string. Failure to notify is logged and no
;; further action is taken (coordinator will time out).

;; TODO: implement heartbeat.
;; TODO: retry failed notifications.
(defn- ingestion-ok [url sid]
  (log/info (str "Sending OK to " url))
  (try (http/post url
                  {:body (str "{\"sessionId\":\"" sid "\"}")
                   :content-type "application/json"})
    (catch Exception e (log/info (.getMessage e)))))

(defn- ingestion-abort [url sid reason]
  (log/info (str "Sending Error to " url))
  (try (http/post url
                  {:body (str "{\"sessionId\":\"" sid "\", "
                              "\"reason\":\"" reason "\"}")
                   :content-type "application/json"})
  (catch Exception e (log/info (.getMessage e)))))

;; Ingestion procedure.
(defn- do-ingest-impl
  [{ses :sessionId
    lab :label
    out :output
    curl :completeUrl
    aurl :abortUrl
   :as scm}]
  (log/info "Entering do-ingest.")
  (if (or (nil? ses) (nil? lab) (nil? out) (nil? curl) (nil? aurl))
    (resp/bad-request
     "All fields (session, label, output and response URLs) are required.")
    ;; Problem:  since json  gets parsed  by  Compojure, it  doesn't replace  @.
    ;; TODO: Fixed here  as hack with rename keys, it  should be fitted properly
    ;; in the data processing pipeline.
    (do
      (log/info "-- Starting ingestion")
      (log/info (str "   session: " ses))
      (log/info (str "   label:   " lab))
      (log/info (str "   outdir:  " out))
      (log/info (str "   finish:  " curl))
      (log/info (str "   abort:   " aurl))
      (let [scm (dissoc scm :sessionId :label :output :completeUrl :abortUrl)
            scm (clojure.walk/postwalk-replace
                 {(keyword "@type") :stellar-type
                  (keyword "@id") :stellar-id
                  (keyword "@src") :stellar-src
                  (keyword "@dest") :stellar-dest} scm)
            graph (schema/ingest scm {:label lab})]
        (log/info (str "Done with graph " (deref graph)))
        ;; (response (str "Ingestion completed." (first (:sources scm))))
        (if (either/left? graph)
          (ingestion-abort aurl ses (deref graph))
          ;; (clojure.pprint/pprint scm)
          (if (schema/write-graph-to-json (deref graph) out)
            (ingestion-ok curl ses)
            (ingestion-abort aurl ses (str "I/O error on " out))))))))

;; Function directly called by the REST endpoint.
(defn- do-ingest
  [{ses :sessionId
    lab :label
    out :output
    curl :completeUrl
    aurl :abortUrl
    :as scm}]
  (if (or (nil? ses) (nil? lab) (nil? out) (nil? curl) (nil? aurl))
    (resp/bad-request
     "All fields (session, label, output and response URLs) are required.")
    ;; Ingestion is run asynchronously in a new thread.
    (let [i (future (do-ingest-impl scm))]
      (log/info "Sending OK to UI.")
      (resp/ok))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General REST API description text.

(def ^{:private true} description
"
  This is the  data ingestion module of the Stellar project.

If you see this  page the _Ingestor_ is up and running and  you can access its
REST API.
")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API routes

(def rest-routes
  (cmpj/api
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; General API documentation.
   {:middleware [jsonmw/wrap-json-body]
    :swagger
    {:ui "/"
     :spec "/swagger.json"
     :data{:basePath "/"
           :info {;; Title of documentation page.
                  :title (utils/get-application-name)
                  ;; Version reported in page footer.
                  :version (utils/get-application-version)
                  ;; Summary unused/ignored in general API description.
                  ;; :summary "Just a global summary."
                  ;; General markdown-based API description.
                  :description description
                  :contact {:name "CSIRO Data61 - Stellar project"
                            ;; :email "foo@example.com"
                            :url "https://www.data61.csiro.au/"}
                  :license {:name "Apache License, Version 2.0"
                            :url "http://www.apache.org/licenses/LICENSE-2.0"
                            }}}}}
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; Service test - Return the application banner.
   (cmpj/context "/util" []
     :tags ["Utility functions"]

     (GET "/" []
       :query-params []
       :return String
       :summary "Return the application banner."
       :description "Return  a simple string containing  the application banner,
                   in the form `Stellar Ingest v.X.Y.Z[-SNAPSHOT]`"
       (utils/get-application-banner))
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Debugging - Echo quesry parameters.
     (GET "/do-show" {parms :params}
       :query-params [x :- String,
                      y :- String,
                      {z :- String ""}]
       :summary "Echo the query parameter map."
       :description  (str
                      "Called with  any number  of parameters,  echoes the  query"
                      "parameters map in the response."
                      "\n\n"
                      "**Note:** x, y  and z are just  example parameters that
                      were chosen  for this  documentation page.   When called
                      from  an external  client  (e.g. `curl`)  any number  of
                      query parameters, with any names, can be used.")
       (do-show parms))
     ) ;; End util routes
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; Sampler API interface.
   (cmpj/context "/sampler" []
     :tags ["Sampler functions"]
     ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Original do-sample route using post and json for request.
     ;; (POST "/do-sample" {body :body} (do-sample-csv-file body))
     ;;
     ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; New do-sample using get and query parameters.
     (GET "/do-sample" {parms :params}
       ;; TODO: why do I get error with samples as Integer???
       ;; :query-params [file :- String, samples :- Integer]
       :query-params [file :- String,
                      samples :- String]
       ;; :return String
       ;; Summary is just a few  words (preformatted, right-justified, on path
       ;; collapsable   title  bar.)    Description  adds   a  long   markdown
       ;; section "Implementation notes", after title and before responses.
       :summary "Return a CSV file sample."
       :description "Given  the path  to a  CSV file and  a number  of samples
                     return that number of samples from the file."
       (do-sample-csv-file-parms parms))
     ) ;; End sampler routes
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; Ingestor API interface.
   (cmpj/context "/ingestor" []
     :tags ["Ingestor functions"]
     ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; Ingest data
     (POST "/ingest" {body :body}
       ;; :body [body {s/Any}]
       ;; :body [body {:schema String :output String :label String}]
       ;; :body-params [schema :- String, output :- String, label :- String]
       ;; :body-params [schema, output :- String, label :- String]

       :body-params [sessionId, label, output, completeUrl, abortUrl,
                     sources, graphSchema, mapping]

       ;; :return String
       :summary "Ingest data in a graph."
       :description "Given a graph schema  (containing CSV sources) and a label,
                    ingest a graph to EPGM"
       (do-ingest {:sessionId sessionId, :label label, :output output,
                   :completeUrl completeUrl, :abortUrl abortUrl,
                   :sources sources, :graphSchema graphSchema,
                   :mapping mapping}))
     ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
     ;; List files in working directory.
     (GET "/list-files" []
       :summary "List files in the working directory."
       ;; :description "Given a graph schema  (containing CSV sources) and a label,
       ;;              ingest a graph to EPGM"
       (response {:files (into [] (utils/filter-files-rec #".*\.csv"))}))
     ) ;; End ingestor routes
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; Default: route not found!
   (route/not-found "Not Found")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main method. This  is NOT Ingests's main (which resides  in namespace app),
;; but a utility main  method which is useful to start  the webserver with the
;; REST  interface. It  take 1  optional parameter,  the server  port (default
;; 3000).
;;
;; To run it from a terminal use:
;; java -cp /path/to/stellar-ingest-*-standalone.jar stellar_ingest.rest [port]
;;
;; Requested file path can be either absolute or relative to the directory the
;; above command is issued in.

(defn -main [& args]
  (let [def-port 3000
        ;; Get port from command line or use default (3000).
        ;; TODO: catching in let-binding is not so great (e.g. for 'finally').
        ;; Consider using try-let library or refactor the code.
        port (if (nil? args)
               def-port
               (try (Integer/parseInt (first args))
                    (catch java.lang.NumberFormatException e
                      (println (str "Invalid port: " (first args)
                                    ". Using default: " def-port))
                      def-port)))]
    (try
      (jetty/run-jetty #'rest-routes {:port port :join? false})
      (catch java.net.BindException e
        (println (str "Network error: " e ".")))
      (catch java.lang.Exception e
        (println (str "Unexpected error: " e "."))))))

;; To run the server during development use:
;; $ lein ring server
;; or in the REPL:
;; > (defonce server (jetty/run-jetty #'rest-routes {:port 3000 :join? false}))
;; > (defn stop-server [] (.stop server))
;; > (defn start-server [] (.start server))
;;
;; Example POST request with curl:
;; $ curl -v -X POST -H "Content-Type: application/json" -d '{"file":"/some/file.csv","samples":"5"}' http://localhost:3000/sampler/do-sample; echo
;;
;; Note: The  MIME media  type for  JSON text  is application/json,  the default
;; encoding is UTF-8 (See RFC 4627).
