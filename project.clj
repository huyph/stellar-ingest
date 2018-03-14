(defproject au.csiro.data61.stellar/stellar-ingest "0.0.2-SNAPSHOT"
    
  :description "Stellar data ingestion module."
  :url "http://serene.sh/"

  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;; Command line args parsing.
                 [org.clojure/tools.cli "0.3.5"]
                 ;; Logging (interface to log4j)
                 [org.clojure/tools.logging "0.4.0"]
                 ;; Category theory types
                 [funcool/cats "2.1.0"]
                 ;; Input file parsing.
                 [org.clojure/data.csv "0.1.4"]
                 [io.forward/yaml "1.0.6"]
                 ;; Kafka streams support.
                 [org.apache.kafka/kafka_2.11 "0.11.0.0"]
                 [org.apache.kafka/kafka-clients "0.11.0.0"]
                 ;; REST
                 [compojure "1.6.0"]
                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-json "0.4.0"
                  ;; Exclusion solves version conflict with Stellar-Utils.
                  :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [ring/ring-jetty-adapter "1.6.2"]
                 ;; Compojure routes with swagger docs.
                 [metosin/compojure-api "2.0.0-alpha17"]
                 ;; HTTP client.
                 [clj-http "3.7.0"]
                 ;; New utils: local memory backend.
                 [sh.serene/stellar-utils "0.2.1"]
                 ;; Get version string from lein project.
                 [trptcolin/versioneer "0.2.0"]
                 ;; File system library
                 [me.raynes/fs "1.4.6"]]
  :plugins [;; Launch webserver with ring application from lein.
            [lein-ring "0.12.1"]
            ;; Print version taken from project.clj
            [lein-project-version "0.1.0"]
            ;; Deploy to/retrieve from private artifact repository on S3.
            [lein-maven-s3-wagon "0.2.5"]
            ;; Deploy uberjar to S3 repository.
            [org.ammazza/lein-deploy-uberjar "2.1.1-SNAPSHOT"]
            ;; Collect test results for coveralls.
            [lein-cloverage "1.0.10"]
            ;; Generate program API documentation.
            [lein-codox "0.10.3"]]
  
  :repositories [["snapshots" {:url "s3://serene-maven-repository/snapshots"
                               :no-auth true :sign-releases false}]
                 ["releases" {:url "s3://serene-maven-repository/releases"
                              :no-auth true :snapshots false :sign-releases false}]]
  
  :ring {:handler stellar-ingest.rest/rest-if}

  :main stellar-ingest.app
  :target-path "target/%s"
  ;; Mac OSX seems to require explicit class list. Investigate.
  :profiles {:uberjar {:aot :all}})

;; Consider adding these in a testing profile (check versions):
;;   :profiles
;;   {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
;;                         [ring/ring-mock "0.3.0"]]}})
