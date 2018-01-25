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

(ns stellar-ingest.utils
  (:require
   ;; Get version string from lein project.
   [trptcolin.versioneer.core :as version])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private project variables and configuration defaults.

;; Project maven  ids. These  must be  inserted here  manually and  must match
;; those  in file  project.clj, otherwise  the application  version cannot  be
;; obtained from the project properties.
(def ^{:private true} maven-group-id "au.csiro.data61.stellar")
(def ^{:private true} maven-artifact-id "stellar-ingest")

;; Application name,  as it will  be displayed  in the application  banner and
;; help screens.
(def ^{:private true} application-name "Stellar Ingest")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to access the project current version and application banner.

(defn get-maven-group-id
  "Return the  package group id string,  as used in project  definition and to
  identify the maven binary artifact."
   [] maven-group-id)

(defn get-maven-artifact-id
  "Return the package artifact id string, as used in project definition and to
  identify the maven binary artifact."
   [] maven-artifact-id)

(defn get-application-name
  "Return the  application name string. This  is the official name  exposed to
  the end user (e.g. in command line messages)."
   [] application-name)

(defn get-application-version
  "Return the  application name string. This  is the official name  exposed to
  the end user (e.g. in command line messages)."
   [] (version/get-version maven-group-id maven-artifact-id))

(defn get-application-banner
  "Return the  application banner (string),  that is the application  name and
  its current version."
  []
  (str (get-application-name) " v." (get-application-version)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Temporary  file utilities  - Must  be replaced  with portable  versions and
;; moved to another namespace.

;; TODO: import portable FS library and change these functions.

(defn file-to-string [f]
  (cond
    (instance? java.io.File f) (.getPath f)
    (instance? java.net.URL f) (.getPath f)
    (instance? java.lang.String f) f
    :else nil))

(defn path-basename [f]
  (let [f (file-to-string f)
        v (clojure.string/split f #"/")
        n (count v)]
    (if (= n 1)
      ""
      (if (and (= n 2) (= (first v) ""))
        "/"
        (str (clojure.string/join "/" (subvec v 0 (- n 1))) "/")))))

(defn path-filename [f]
  (last (clojure.string/split (file-to-string f) #"/")))

(defn make-path [base file]
  (str (file-to-string base) (file-to-string file)))
