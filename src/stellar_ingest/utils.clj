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
   [trptcolin.versioneer.core :as version]
   [me.raynes.fs :as fs])
  (:import [java.io File])
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System utilities and development helpers.

(defn exit
  "Exit the  application with  error code  errn (default  0) in  a CIDER-friendly
  manner. If the function is called from inside the CIDER REPL, instead of exit,
  which would kill  the REPL, and exception is thrown.  Otherwise System/exit is
  called."
  ([errn]
   (let [errn (if (integer? errn) errn 1)
         in-cider (not (nil? (resolve 'cider.nrepl.version/version)))]
     (if in-cider
       (throw (new Exception (str "\n*** EXITING PROGRAM WITH CODE " errn)))
       (System/exit errn))))
  ([] (exit 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Temporary  file utilities  - Must  be replaced  with portable  versions and
;; moved to another namespace.

;; TODO: import portable FS library and change these functions.

;; TODO:  Only  basename  and  makepath  used in  public  code,  make-path  uses
;; file-to-string. Function filename unused. Rethink if they're needed.

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
  ;; If file is already an absolute  path, the just return it, otherwise combine
  ;; base and file.
  (if (fs/absolute? file)
    file
    (str (file-to-string base) (file-to-string file))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A shorthand  path is a portion  of an path that  can be used to  identify the
;; path in a  compact way: it is  defined as a trailing portion  of the original
;; path,  e.g. a  file name  can  be a  shorthand  for the  file absolute  path.
;; Shorthand are  useful, for instance,  in the schema definition,  to reference
;; sources in mappings without repeating the full path.

(defn check-path-shorthand
  "
  Given two paths,  assess if child can be interpreted  as shorthand for parent,
  i.e. if  child is a subpath  of parent, terminating  at the same level  (or is
  simply  equal  to the  parent).  Return  -1 for  no  shorthand,  1 for  strict
  shorthand (subpath) and 0 if they're equal.

  The two paths must be of type java.io.File or String and contain a valid path.
  Different types can be safely passed,  as long as they implement conversion to
  String, but behaviour is undefined.

  The  paths are  compared as  they  are, without  normalization (i.e.   special
  directory  symbols '.',  '..',  '~',  etc. are  not  resolved). Multiple  path
  separators in place of one are correctly ignored.
  "
  [child parent]
  (let [;; Turn the two  paths into vectors  of their  components  (as strings).
        ;; Ingore multiple  consecutive path  separators, e.g.  "///".   Get the
        ;; length of the resulting vectors.
        ;; For nil/empty string fs/split returns empty vector.
        c (into [] (filter #(not= "" %) (fs/split child)))
        p (into [] (filter #(not= "" %) (fs/split parent)))
        lc (count c)
        lp (count p)]
    ;; Meaningless inputs (child or parent: empty  string or nil) are treated as
    ;; no  shorthand (-1).  With valid  inputs,  if the  child is  longer or  if
    ;; trailing section of parent differs, it's no shorthand (-1).
    (if (or (= 0 lc) (= 0 lp) (> lc lp) (not= c (subvec p (- lp lc))))
      -1
      ;; If the  child is shorter and  equals a trailing portion  of the parent,
      ;; then it's  a strict shorthand (1).  Otherwise child and parent  are the
      ;; same path (0).
      (if (and (< lc lp) (= c (subvec p (- lp lc))))
        1
        0))))

(defn resolve-path-shorthand
  "
  Given a  shorthand path and a  collection of candidate parent  paths, return a
  collection of  matching parents, i.e.  parents  for which check-path-shorthand
  return equality or strict shorthand.

  See check-path-shorthand for valid input types and limitations on them.
  "
  [child parents]
  ;; Take parents that are equal to child or for which child is a shorthand.
  (filter #(>= (check-path-shorthand child %) 0) parents))
