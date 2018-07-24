;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This file is part of stellar-ingest, Stellar data ingestion module.
;;
;; Copyright 2017-2018 CSIRO Data61
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

;; Example with imports
;; https://github.com/saagie/example-java-read-and-write-from-hdfs/blob/master/src/main/java/io/saagie/example/hdfs/Main.java

;; ubuntu@ip-10-0-10-100:~$ ls -1 / > /tmp/test.txt
;; ubuntu@ip-10-0-10-100:~$ 
;; ubuntu@ip-10-0-10-100:~$ 
;; ubuntu@ip-10-0-10-100:~$ hdfs dfs -copyFromLocal /tmp/test.txt /tmp
;; No command 'hdfs' found, did you mean:
;;  Command 'hfs' from package 'hfsutils-tcltk' (universe)
;;  Command 'hdfls' from package 'hdf4-tools' (universe)
;; hdfs: command not found
;; ubuntu@ip-10-0-10-100:~$ /opt/hadoop/bin/hdfs dfs -copyFromLocal /tmp/test.txt /tmp
;; ubuntu@ip-10-0-10-100:~$ 
;; ubuntu@ip-10-0-10-100:~$ /opt/hadoop/bin/hdfs dfs -ls /tmp
;; Found 2 items
;; drwxrwxrwt   - hdfs   supergroup          0 2018-06-19 08:13 /tmp/hive
;; -rw-r--r--   3 ubuntu supergroup        147 2018-07-23 05:09 /tmp/test.txt
;; ubuntu@ip-10-0-10-100:~$ /opt/hadoop/bin/hdfs dfs -cat /tmp/test.txt

;; ubuntu@13.211.142.240
;; ssh -N -i ~/Keys/filippo-dev-machines.pem -L 8020:localhost:8020 ubuntu@13.211.142.240

;; Reproducing the following example:
;; https://creativedata.atlassian.net/wiki/spaces/SAP/pages/52199514/Java+-+Read+Write+files+with+HDFS

;; ubuntu@ip-10-0-10-100:~$ /opt/hadoop/bin/hdfs version
;; Hadoop 2.8.4


;; hdfs://localhost:8020/tmp/test.txt

;; import org.apache.commons.io.IOUtils;
;; import org.apache.hadoop.conf.Configuration;
;; import org.apache.hadoop.fs.FSDataInputStream;
;; import org.apache.hadoop.fs.FSDataOutputStream;
;; import org.apache.hadoop.fs.FileSystem;
;; import org.apache.hadoop.fs.Path;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns stellar-ingest.hdfs
  ;; (:require
  ;;  ;; I/O.
  ;;  [clojure.data.csv :as csv]
  ;;  [clojure.java.io :as io]
  ;;  ;; Category theory types.
  ;;  [cats.core :as cats]
  ;;  [cats.monad.either :as either])
  (:import
   ;; HDFS imports
   (org.apache.hadoop.conf Configuration)
   (org.apache.hadoop.fs FileSystem LocalFileSystem Path FSDataOutputStream)
   (org.apache.hadoop.hdfs DistributedFileSystem)
   (java.net URI)
   )
  (:gen-class))

(defn connect-to-hdfs
  "Give a HDFS  URI as string, like \"hdfs://hostname:8020\" return  a file system
   handler that can be used to operate on HDFS."
  [hdfsuri]
  (let [conf (Configuration.)]    
    (.set conf "fs.defaultFS" hdfsuri)
    ;; Ugly  trick: can't  figure out  '.class.getName()' in  Clojure and  using
    ;; getClass() prefixes class name with 'class '. To correct drop 6 chars.
    (.set conf "fs.hdfs.impl"
          (subs (str (doto org.apache.hadoop.hdfs.DistributedFileSystem
                       .getClass
                       .getName)) 6))
    (.set conf "fs.file.impl"
          (subs (str (doto org.apache.hadoop.fs.LocalFileSystem
                       .getClass
                       .getName)) 6))
    (System/setProperty "HADOOP_USER_NAME" "hdfs")
    (System/setProperty "hadoop.home.dir" "/")
    (FileSystem/get (URI/create hdfsuri) conf)))

(comment

  (def hdfsuri "hdfs://127.0.0.1:8020")
  (def hdfsuri "hdfs://localhost:8020")
  (def hdfsuri "hdfs://13.211.142.240:8020")
  
  (def fs (connect-to-hdfs hdfsuri))
  (def wpath (Path. (str hdfsuri "/user/hdfs/ingest.txt")))
  (def wpath (Path. (str "" "/user/hdfs/ingest.txt")))
  (def wstream (.create fs wpath))
  ;; (.getWorkingDirectory fs)
  ;; #object[org.apache.hadoop.fs.Path 0x4e77d0ba "hdfs://127.0.0.1:8020/user/hdfs"]
  (let [now (str (java.util.Date.))]
    (println (str "Writing: " now))
    (.writeBytes wstream now))


  ;; CompilerException java.io.EOFException: End of File Exception between local host is: "liquid-ev/127.0.1.1"; destination host is: "localhost":8020; : java.io.EOFException; For more details see:  http://wiki.apache.org/hadoop/EOFException, compiling:(form-init6923650512603154354.clj:103:16) 
  ;; Try to compile this code and the original Java and run directly on server.
  ;; This way I'll know if there's something wrong with the networking part...
  
  ;; This seems all OK: "2.8.4"
  ;; (org.apache.hadoop.util.VersionInfo/getVersion)
  
  ) ;; End comment

;; YES! This works when running directly on the master, with the private DNS hostname.
;;
;; So, I only need to figure out where the networking problem is and it's done!
(defn -main "" []
  (println "Just testing HDFS.")
  (let [
        ;; hdfsuri "hdfs://127.0.0.1:8020"
        ;; hdfsuri "hdfs://ip-10-0-10-100:8020"
        hdfsuri "hdfs://localhost:8020"
        fs (connect-to-hdfs hdfsuri)
        wpath1 (Path. (str hdfsuri "/user/hdfs/ingest.txt"))
        wpath2 (Path. (str "" "/user/hdfs/ingest.txt"))
        wstream (.create fs wpath1)
        now (str (java.util.Date.))]
    (println (str "Writing: " now))
    (.writeBytes wstream now)))

;; //Create a path
;; Path hdfswritepath = new Path(newFolderPath + "/" + fileName);
;; //Init output stream
;; FSDataOutputStream outputStream=fs.create(hdfswritepath);
;; //Cassical output stream usage
;; outputStream.writeBytes(fileContent);
;; outputStream.close();











