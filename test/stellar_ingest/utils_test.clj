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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test utils file system functions.
;;
;; TODO: would be nice to have also Windows test cases, to ensure portability.

(deftest test-check-path-shorthand
  (testing "Function check-path-shorthand."
    ;; Strict shorthand: filename and absolute path.
    (is (= 1
         (check-path-shorthand "file.ext" "/foo/bar/baz/dir/file.ext")))
    ;; Strict shorthand: filename and relative path.
    (is (= 1
         (check-path-shorthand "file.ext" "bar/baz/dir/file.ext")))
    ;; Strict shorthand: relative (sub-)path and absolute path.
    (is (= 1
         (check-path-shorthand "dir/file.ext" "/foo/bar/baz/dir/file.ext")))
    ;; Strict shorthand:  relative (sub-)path and absolute  path, excessive path
    ;; separators are ignored.
    (is (= 1
         (check-path-shorthand "dir///file.ext" "/foo///baz//dir//file.ext")))
    ;; Non-strict shorthand: absolute paths
    (is (= 0
         (check-path-shorthand "/baz/dir/file.ext" "/baz/dir/file.ext")))
    ;; Non-strict shorthand: relative paths
    (is (= 0
         (check-path-shorthand "baz/dir/file.ext" "baz/dir/file.ext")))

    ;; Not shorthand: relative subpath, but not ending at same level as absolute
    ;; path.
    (is (= -1
         (check-path-shorthand "bar/baz/dir" "/foo/bar/baz/dir/file.ext")))
    ;; Not shorthand: use of '.' is not supported.
    (is (= -1
         (check-path-shorthand "dir/file.ext" "/foo/bar/baz/dir/./file.ext")))
    ;; Not shorthand: looks like a subpath, but initial '/' makes it absolute.
    (is (= -1
         (check-path-shorthand "/dir/file.ext" "/foo/bar/baz/dir/file.ext")))
    ;; Not shorthand: inverted parms, child longer than parent.
    (is (= -1
         (check-path-shorthand "/foo/bar/baz/dir/file.ext" "dir/file.ext")))))

(deftest test-resolve-path-shorthand
  ;; Vectors  are used  in  the test  cases. The  function  under test  actually
  ;; returns a generic sequence, but '=' works between different sequences.
  (testing "Function resolve-path-shorthand."
    ;; For  a leaf  shorthand (file  or last  directory), all  possible trailing
    ;; subpaths of its absolute path are parents.
    (is (= (resolve-path-shorthand
            "file.ext"
            ["file.ext"
             "dir/file.ext"
             "baz/dir/file.ext"
             "bar/baz/dir/file.ext"
             "foo/bar/baz/dir/file.ext"
             "/foo/bar/baz/dir/file.ext"])
           ;; Reference value.
           ["file.ext"
            "dir/file.ext"
            "baz/dir/file.ext"
            "bar/baz/dir/file.ext"
            "foo/bar/baz/dir/file.ext"
            "/foo/bar/baz/dir/file.ext"]))
    ;; For a non-leaf  shorthand (relative path), all possible  trailing subpaths of
    ;; its absolute path, that equal or encompass the shorthand are parents.
    (is (= (resolve-path-shorthand
            "baz/dir/file.ext"
            ["file.ext"
             "dir/file.ext"
             "baz/dir/file.ext"
             "bar/baz/dir/file.ext"
             "foo/bar/baz/dir/file.ext"
             "/foo/bar/baz/dir/file.ext"])
           ;; Reference value.
           ["baz/dir/file.ext"
            "bar/baz/dir/file.ext"
            "foo/bar/baz/dir/file.ext"
            "/foo/bar/baz/dir/file.ext"]))
    ;; For any shorthand, no  leading subpath of its absolute path  can be a parent,
    ;; only the absolute path itself.
    (is (= (resolve-path-shorthand
            "dir/file.ext"
            ["/foo"
             "/foo/bar"
             "/foo/bar/baz"
             "/foo/bar/baz/dir"
             "/foo/bar/baz/dir/file.ext"])
           ;; Reference value.
           ["/foo/bar/baz/dir/file.ext"]))))

;; (run-tests)
