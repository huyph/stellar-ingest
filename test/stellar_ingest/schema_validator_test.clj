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

(ns stellar-ingest.schema-validator-test
  (:require [clojure.test :refer :all]
            [stellar-ingest.schema-validator :refer :all]
            ;; Support libraries.
            [cats.core :as cats]
            [cats.monad.either :as either]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftest test-resolve-source
  (testing "Function to resolve sources specified in schema mappings."
    (let [;; A  test schema, containing 3 sources and  no :schema-file entry, as
          ;; if passed as JSON through the REST API.
          scm1 {:sources ["/tmp/my/first/file.dat"
                          "/data/my/other/file.dat"
                          "some/relative/file.txt"]}
          ;; The same schema, but with the :schema-file entry, as if loaded from
          ;; a command line request.
          scm2 (assoc scm1
                      :schema-file "/home/user/myproject/ingest.json")]
      
      ;; Cases that throw Exception: Unknown data source.
      ;; - The source identifier is not in the sources list.
      (is (thrown? Exception (resolve-source "nope.csv" scm1)))
      ;; - The sources list in the schema is empty.
      (is (thrown? Exception (resolve-source "file.txt" {:sources []})))
      ;; - The sources list in the schema is not present or nil.
      (is (thrown? Exception (resolve-source "file.txt" {})))

      ;; Cases that throw Exception: Ambiguous data source.
      ;; - The source identifier is ambiguous (could refer to 2+ sources).
      (is (thrown? Exception (resolve-source "file.dat" scm1)))
      ;; - The source identifier is the empty string.
      (is (thrown? Exception (resolve-source "" scm1)))
      ;; - The source identifier is nil.
      (is (thrown? Exception (resolve-source nil scm1)))

      ;; Working cases: a single source can be identified in the list.
      ;; - No schema-file and source is absolute path: return unchanged.
      (is (= "/tmp/my/first/file.dat"
             (resolve-source "first/file.dat" scm1)))
      ;; - No schema-file and source is relative path: return unchanged.
      (is (= "some/relative/file.txt"
             (resolve-source "file.txt" scm1)))
      ;; - Schema-file present and source is absolute path: return unchanged.
      (is (= "/tmp/my/first/file.dat"
             (resolve-source "first/file.dat" scm2)))
      ;; - Schema-file  is present  and source  is  a relative  path: append  to
      ;;   schema-file directory.
      (is (= "/home/user/myproject/some/relative/file.txt"
             (resolve-source "file.txt" scm2))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftest test-validate-schema
  (testing "Entry point of schema validation."
    (let [;; scm1 has a valid schema type (map), scm doesn't (vector).
          scm1 {}
          scm2 []]
      ;; Validated schema is returned as either/right and has :validated true.
      (is (true? (let [vs (validate-schema scm1)]
                   (and (either/right? vs)
                        (map? (deref vs))
                        (true? (:validated (deref vs)))))))
      ;; Wrong object type passed as schema.
      (is (true? (let [vs (validate-schema scm2)]
                   (and (either/left? vs))))))))
      
;; (run-tests)













