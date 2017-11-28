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
  (:require [clojure.tools.logging :as log]
            [compojure.core :as cmpj :refer [GET POST]]
            [compojure.route :as route]
            [compojure.handler :as handle]
            [ring.util.response :refer [response status]]
            [ring.middleware.json :as jsonmw]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :as parsmw]
            [ring.middleware.multipart-params]
            ;; Category theory types.
            [cats.core :as cats]
            [cats.monad.either :as either]
            ;; Cheshire JSON library
            [cheshire.core :as json])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;

(defn bad-request
  ""
  [body]
  (status (response body) 400))

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
      (bad-request (str (deref error))))))

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
;; REST routes

(cmpj/defroutes rest-routes
  (GET "/" [] "Stellar Ingest v.0.0.2-SNAPSHOT")
  (GET "/do-show" {parms :params} (do-show parms))
  (cmpj/context "/sampler" []
                (cmpj/defroutes sampler-routes
                  ;; Original do-sample route using post and json for request.
                  ;; (POST "/do-sample" {body :body} (do-sample-csv-file body))
                  ;; New do-sample using get and query parameters.
                  (GET "/do-sample" {parms :params} (do-sample-csv-file-parms parms))
                  )) ;; End sampler-routes
  (route/not-found "Not Found"))

;; Test POST routes like so:
;; curl -v -H "Content-Type: application/json" -d '{"file":"/some/file.csv","samples":"5"}' http://localhost:3000/sampler/do-sample; echo; echo

;; Example routes from old project:
;; (context "/documents" [] (defroutes documents-routes
;;                            (GET  "/" [] (get-all-documents))
;;                            (POST "/" {body :body} (create-new-document body))
;;                            (context "/:id" [id] (defroutes document-routes
;;                                                   (GET    "/" [] (get-document id))
;;                                                   ;; (GET    "/" [] "get-document-2")
;;                                                   (PUT    "/" {body :body} (update-document id body))
;;                                                   (DELETE "/" [] (delete-document id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST interface, i.e.  the ring composite middleware that  gets launched and
;; handles requests.

;; (def rest-if
;;   (-> (handle/api rest-routes)
;;       (jsonmw/wrap-json-body)
;;       (jsonmw/wrap-json-response)))


      ;; {:status 200
      ;;  ;; The MIME media type for  JSON text is application/json. The default
      ;;  ;; encoding is UTF-8. (Source: RFC 4627).
      ;;  :headers {"Content-Type" "application/json"}
      ;;  :body (into [] (deref sample))}

;; #object[org.eclipse.jetty.server.HttpInputOverHTTP 0x35f16daf HttpInputOverHTTP@35f16daf]
(def rest-if
  (as-> (handle/api rest-routes) h
      ;; (jsonmw/wrap-json-body h {:keywords? true})
      (parsmw/wrap-keyword-params h)
      (jsonmw/wrap-json-response h)))

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
  ;; Missing control on command line parms validity.
  (let [port (if (nil? args) 3000 (first args))]
    (jetty/run-jetty #'rest-if {:port port :join? false})))
