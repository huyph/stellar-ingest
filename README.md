# _stellar-ingest_ - Stellar data ingestion module

## Build status

|Branch|Test|Coverage|
|:-----|:----:|:----:|
|*master*|[![Build Status](https://travis-ci.org/data61/stellar-ingest.svg?branch=master)](https://travis-ci.org/data61/stellar-ingest)|[![Coverage Status](https://coveralls.io/repos/github/data61/stellar-ingest/badge.svg?branch=master)](https://coveralls.io/github/data61/stellar-ingest?branch=master)|
|*devel*|[![Build Status](https://travis-ci.org/data61/stellar-ingest.svg?branch=devel)](https://travis-ci.org/data61/stellar-ingest)|[![Coverage Status](https://coveralls.io/repos/github/data61/stellar-ingest/badge.svg?branch=devel)](https://coveralls.io/github/data61/stellar-ingest?branch=devel)|

## Introduction

This repository hosts **_stellar-ingest_**, a module of the [Stellar - Graph
Analytics platform](https://www.stellargraph.io/) developed by [CSIRO
Data61](http://data61.csiro.au/). This module takes care of ingesting relational
data, stored as CSV files, into a graph, for further processing by _Stellar_.

If you are interested in running the entire _Stellar_ platform, please refer to
the instructions on the [main Stellar
repository](https://github.com/data61/stellar).

## License

Copyright 2017-2018 CSIRO Data61

Licensed under the  [Apache License](LICENSE), Version 2.0  (the "License"); you
may not use the files included in  this repository except in compliance with the
License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless  required  by  applicable  law   or  agreed  to  in  writing,  software
distributed under  the License  is distributed  on an  "AS IS"  BASIS, WITHOUT
WARRANTIES OR  CONDITIONS OF  ANY KIND,  either express  or implied.   See the
License for the specific language  governing permissions and limitations under
the License.

## Obtaining _stellar-ingest_

The remainder of this page describes how to build and run _stellar-ingest_ from
its source code.

Additionally _stellar-ingest_ is available as a Docker image. See details at the
[dedicated documentation page](doc/docker.md).

## Compilation

stellar-ingest is a [Clojure](https://clojure.org/) program and uses
[Leiningen](https://leiningen.org/) as build tool.

To create a standalone executable (jar file) run the following command from
within _stellar-ingest_ code repository root:

`lein uberjar`

## Usage

Currently,  _stellar-ingest_ offers  two   main  interfaces  to  perform  graph
ingestion:

- a  command line interface  (CLI), invoking  _stellar-ingest_ as a  utility for
  every new ingestion, passing parameters on the command line;
- a  RESTful API,  starting _stellar-ingest_  as a  server and  making ingestion
  requests via http.

In  both   cases  the   following  pieces  of   information  are   required  for
_stellar-ingest_ to operate:

- input CSV files (see [details on format](doc/input.md));
- a  JSON-encoded [graph schema](doc/schema.md), including  mappings between CSV
  columns and graph elements.

The resulting schema is represented using the Extended Property Graph Model
([EPGM](https://dbs.uni-leipzig.de/file/EPGM.pdf)) and stored using its JSON
serialization format. For additional details see also the [_stellar-utils-
documentation](https://github.com/data61/stellar-utils/blob/master/README.md).

__Note__: the _Stellar_ platform includes a Python client library, which allows
to access all modules from Python scripts, using the REST API.  An example,
which includes data ingestion, can be found [here]
(https://github.com/data61/stellar-py/blob/0.2.0/examples/stellar.ipynb).

### CLI ingestion

Once the program has been compiled, from the repository root issue:

``` bash
java \
  -cp ./target/uberjar/stellar-ingest-0.1.0-standalone.jar \
  stellar_ingest.schema \
  path/to/schema_file.json \
  path/to/output_directory \
  arbitrary-graph-label
```

A ready-to-use example is provided with the source code repository:

``` bash
# Move the example directory.
cd resources/examples/imdb_norm

# Run this command as it is.
java \
  -cp ../../../target/uberjar/stellar-ingest-0.1.0-standalone.jar \
  stellar_ingest.schema \
  imdb_norm_schema.json \
  imdb_output \
  imdb
```

A directory  `imdb_output` will be  created, which  contains an EPGM  graph with
label `imdb`.

### REST ingestion

To run _stellar-ingest_ in server mode and access the REST API issue this command:

``` bash
java \
  -cp ./target/uberjar/stellar-ingest-0.1.0-standalone.jar \
  stellar_ingest.schema
```

After `stellar_ingest.schema`  it is possible to  specify a port number  for the
server to  listen on. The  default port is 3000.  Along with REST  request, this
port also serves an API documentation page. To see it point a web browser to:

```
http:\\localhost:3000

```

Graph  ingestion  is  triggered  by  am http  `POST`  request  to  the  endpoint
`ingestor/ingest`.  The request body, encoded in  JSON, is composed by the graph
schema, as used in the CLI ingestion process, enriched by a few elements:

``` json
{
  "abortUrl": "https://requestb.in/HERE-YOUR-BIN",
  "completeUrl": "https://requestb.in/HERE-YOUR-BIN"
  "sessionId": "example-session",
  "label": "imdb",
  "output": "imdb_output",
  
  # This part is the same as the CLI ingestion.
  "sources": {},
  "mapping": {},
  "graphSchema": {},
}
```

You can try it on the included  imdb example. Add additional JSON file, with the
additional required elements, is provided (`imdb_norm_schema_rest.json`).

``` bash
# Move the example directory.
cd resources/examples/imdb_norm

# Start the server.
java \
  -cp ../../../target/uberjar/stellar-ingest-0.1.0-standalone.jar \
  stellar_ingest.rest

# Perform the request.
curl -X POST \
     --header 'Content-Type: application/json' \
     --header 'Accept: application/json' \
     -d '@imdb_norm_schema_rest.json' \
     'http://localhost:3000/ingestor/ingest'
```

## Documentation

Further documentation  is provided  in the [doc  directory](./doc/index.md) of
this repository.



