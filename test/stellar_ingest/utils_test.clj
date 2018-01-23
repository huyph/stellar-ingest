(ns stellar-ingest.utils-test
  (:require [clojure.test :refer :all]
            [stellar-ingest.utils :refer :all]))

;; To run these tests in REPL use:
;; (require '[clojure.test :as ctest])
;; (ctest/run-tests 'stellar-ingest.utils-test)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test utils functions to access program version information."

(deftest version-strings
  (testing "Utils functions to access program version information."
    ;; Check group-id (retrieved from lein's project.clj)
    (is
     (= "au.csiro.data61.stellar" (get-maven-group-id))
     "Group ID should be \"au.csiro.data61.stellar\".")
    ;; Check artifact-id (retrieved from lein's project.clj)
    (is
     (= "stellar-ingest" (get-maven-artifact-id))
     "Artifact ID should be \"stellar-ingest\".")
    ;; Check application name (defined in utils.clj)
    (is
     (= "Stellar Ingest" (get-application-name))
     "Application name should be \"Stellar Ingest\"."))
    ;; Check version string format (retrieved from lein's project.clj)
    (is
     (not (nil? (re-matches
                 #"[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?"
                 (get-application-version))))
     "Application version should be in the form \"X.Y.Z[-SNAPSHOT]\".")
    ;; Check the application banner (app. name + version format)
    (is
     (not (nil? (re-matches
                 #"Stellar Ingest v\.[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?"
                 (get-application-banner))))
     "Application banner doesn't have the expected text/format."))
