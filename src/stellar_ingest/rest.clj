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
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            ;; Cheshire JSON library
            [cheshire.core :as json]
            ;; Define schemas for swagger documentation.
            [schema.core :as s])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn do-sample-csv-file
  ""
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

(defn do-sample-csv-file-parms
  ""
  [{file :file samples :samples :as parms}]
  (log/info parms)
  ;; If samples is a number pass it as number, else pass original.
  (let [n (read-string (str samples))]
    (if (number? n)
      (do-sample-csv-file {:file file :samples n})
      ;; {:file file :samples n}
      (do-sample-csv-file {:file file :samples samples})
      ;; {:file file :samples samples}
      )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn do-show [req]
  (log/info req)
  ;; (response (str "Body: " body " / Parms: " ))
  (response (str req)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General REST API description text.

(def ^{:private true} description
"
This is the  data ingestion module of the Investigative  Analytics project.
Its current version is **0.0.2-SNAPSHOT**.

If you see this  page the _Ingestor_ is up and running and  you can access its
REST API.

In the current API version only CSV file sampling facilities are available, under
the `/sampler/` address.

For a complete description of the _Ingestor_ and its usage please refer to the
[README file](https://github.com/data61/stellar-ingest/blob/master/README.md)
included with its [source code](https://github.com/data61/stellar-ingest).
")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API routes

(def rest-routes
  (cmpj/api
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; General API documentation.
   {:swagger
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
                  :contact {:name "CSIRO Data61 - Investigative Analytics project"}
                            ;; :email "foo@example.com"
                            ;; :url "https://www.data61.csiro.au/"}
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
       ;; E.g.: /home/amm00b/WORK/Dev/Stellar/stellar-ingest-pub/resources/test.csv
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
   ;; Default: route not found!
   (route/not-found "Not Found")))

;; Test POST routes like so:
;; curl -v -H "Content-Type: application/json" -d '{"file":"/some/file.csv","samples":"5"}' http://localhost:3000/sampler/do-sample; echo; echo
;;
;; Example routes from old project:
;; (context "/documents" [] (defroutes documents-routes
;;                            (GET  "/" [] (get-all-documents))
;;                            (POST "/" {body :body} (create-new-document body))
;;                            (context "/:id" [id] (defroutes document-routes
;;                                                   (GET    "/" [] (get-document id))
;;                                                   ;; (GET    "/" [] "get-document-2")
;;                                                   (PUT    "/" {body :body} (update-document id body))
;;                                                   (DELETE "/" [] (delete-document id))))))

;; {:status 200
;;  ;; The MIME media type for  JSON text is application/json. The default
;;  ;; encoding is UTF-8. (Source: RFC 4627).
;;  :headers {"Content-Type" "application/json"}
;;  :body (into [] (deref sample))}

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST interface.

(def rest-if
  (-> rest-routes
      parsmw/wrap-keyword-params
      jsonmw/wrap-json-response))

;; Utility functions to start/stop server.
;; Alternatively use: "lein ring server"
;; (defonce server (jetty/run-jetty #'rest-if {:port 3000 :join? false}))
;; (defn stop-server [] (.stop server))
;; (defn start-server [] (.start server))

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
      (jetty/run-jetty #'rest-if {:port port :join? false})
      (catch java.net.BindException e
        (println (str "Network error: " e ".")))
      (catch java.lang.Exception e
        (println (str "Unexpected error: " e "."))))))
