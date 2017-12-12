# stellar-ingest - Stellar data ingestion module.

## Build status

|Branch|Status|
|:-----|:----:|
|*master*|[![Build Status](https://travis-ci.org/data61/stellar-ingest.svg?branch=master)](https://travis-ci.org/data61/stellar-ingest)|
|*devel*|[![Build Status](https://travis-ci.org/data61/stellar-ingest.svg?branch=devel)](https://travis-ci.org/data61/stellar-ingest)|

## License

Copyright 2017 CSIRO Data61

Licensed under  the Apache License, Version  2.0 (the "License"); you  may not
use  the files  included  in this  repository except  in  compliance with  the
License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless  required  by  applicable  law   or  agreed  to  in  writing,  software
distributed under  the License  is distributed  on an  "AS IS"  BASIS, WITHOUT
WARRANTIES OR  CONDITIONS OF  ANY KIND,  either express  or implied.   See the
License for the specific language  governing permissions and limitations under
the License.

## Compilation

stellar-ingest is a Clojure program and uses Leiningen as build tool. 

To create a  standalone executable jar use the  command (from stellar-ingest's
code repository root):

lein uberjar

## Usage

In its  current form stellar-ingest  comprises several *applications*  or data
processing workflows (e.g. ingesting CSV files to memory, streaming graph data
to  Kafka),  with  difference  interfaces  (CLI,  REST  API).  Some  of  these
functionalities were introduced for testing or demonstration.

The  following are  instructions for  the  officially maintained  uses of  the
ingestor program.

### Command line ingestion

This mode of operation  can be used to ingest local  CSV files, accompained by
the respective schema  files, and turn them into graphs.  The resulting graphs
are written  out to  file in  GDF format to  be visualized  with Gephi  and in
EPGM-JSON format for further processing.

Once the program has been compiled, from the repository root issue:

``` bash
java \
-cp ./target/uberjar/stellar-ingest-CURRENT.VERSION-standalone.jar \
stellar_ingest.schema \
path/to/schema_file.json \
path/to/data_file.csv \
arbitrary-graph-label
```

A ready-to-use example is provided with the source code repository:

``` bash
java \
-cp ./target/uberjar/stellar-ingest-CURRENT.VERSION-standalone.jar \
stellar_ingest.schema \
resources/examples/imdb/imdb_schema_2.json \
resources/examples/imdb/imdb_small_2.csv \
imdb
```





