# Stellar graph schema

To build a graph _stellar-ingest_ requires a graph _schema_ to be defined by the
user.

Strictly speaking, a graph schema is just an abstract description of the classes
that  constitute  the graph  **vertices**  (e.g.   _Person_, _Firm_)  and  their
relationships that are represented as  graph **edges** (e.g.  Person _works for_
Firm). Additionally  both vertices  and edges may  have **attributes**,  that is
values attached  to them that  further qualify them  (e.g. a Pesron's  _name_ or
_address_).

## The graph schema JSON document

Throughout  _stellar-ingest_ documentation  _graph schema_  often refers  to the
_graph schema document_, a JSON-encoded  message that contains the proper schema
(as  defined above)  together with  additional information,  mainly required  to
populate the graph with actual data provided by the user.

In particular  the graph schema document  contains at least a  list of _sources_
(paths of  CSV files to  be ingested) and  a list of  _mappings_ (correspondence
between CSV columns and elements).

A fully  working example of  graph schema document  is provided for  the example
[imdb dataset](../resources/examples/imdb_norm/imdb_norm_schema.json).  The main
[README](../README.md) document demonstrates  how to use it  for graph ingestion
with both current _stellar-ingest_ interfaces (REST and CLI).

The remainder of this page describes in detail the schema document format, using
the imdb example.  The description focuses on the _raw_  format, encoded in JSON
and directly usable by _stellar-ingest_.

For an example of building the schema document from within a Python script, see
instead the example provided with _Stellar_ [Python
client](https://github.com/data61/stellar-py/blob/0.2.0/examples/stellar.ipynb).

### Top-level entries of the graph schema JSON document

The graph  schema document  is a  single JSON object  (delimited by  `{}`), that
wraps a  set of top-level  entries.

```json
{
  "sources" : [ ... ],
  "graphSchema" : { ... },
  "mapping" : { ... }
}
```

When stored in  a JSON file (to  pass it to _stellar-ingest_  CLI interface) the
object is just written  out as it is. When passed  via _stellar-ingest_ REST API
this  object  (without  further  qalification  or wrapping)  is  passed  as  the
ingestion `POST` request `body`.

The entries `sources`, `graphSchema` and `mapping` are examined in the dedicated
section below.

#### Additional top-level entries

When ingestion  via REST  API is performed,  the following  additional top-level
entries  must be  included in  the  request body,  as discussed  in the  [README
file](../README.md).

``` json
{
  ...
  "label": "imdb",
  "output": "imdb_output",
  "completeUrl": "https://requestb.in/HERE-YOUR-BIN"
  "abortUrl": "https://requestb.in/HERE-YOUR-BIN",
  "sessionId": "example-session",
}
```

Entries `label`  (the output  graph label) and  `output` (the  output directory)
simply replace the equivalent CLI parameters.

Entries `completeUrl` and `abortUrl` 

### The _sources_ entry



```json
"sources" : [ "films.csv", "persons.csv", "corps.csv", "producers.csv", "actors.csv", "staff.csv" ],
```

### The _graphSchema_ entry

```json
"graphSchema" : {
    "classes" : [ {
      "name" : "Film",
      "props" : {
        "title" : "string",
        "year" : "string",
        "classification" : "string",
        "isforeign" : "boolean"
      }
    }, {
      "name" : "Person",
      "props" : {
        "name" : "string"
      }
    }, {
      "name" : "Company",
      "props" : {
        "name" : "string"
      }
    } ],
    "classLinks" : [ {
      "name" : "actedin",
      "source" : "Person",
      "target" : "Film"
    }, {
      "name" : "workedin",
      "source" : "Person",
      "target" : "Film"
    }, {
      "name" : "produced",
      "source" : "Company",
      "target" : "Film"
    } ]
  }
```

### The _mapping_ entry

```json

"mapping" : {
    "nodes" : [ {
      "@type" : "Film",
      "@id" : {
        "source" : "films.csv",
        "column" : "id"
      },
      "title" : {
        "source" : "films.csv",
        "column" : "filmtitle"
      },
      "year" : {
        "source" : "films.csv",
        "column" : "year"
      },
      "classification" : {
        "source" : "films.csv",
        "column" : "genre"
      },
      "isforeign" : {
        "source" : "films.csv",
        "column" : "foreign"
      }
    }, {
      "@type" : "Company",
      "@id" : {
        "source" : "corps.csv",
        "column" : "corporation"
      },
      "name" : {
        "source" : "corps.csv",
        "column" : "corporation"
      }
    }, {
      "@type" : "Person",
      "@id" : {
        "source" : "persons.csv",
        "column" : "id"
      },
      "name" : {
        "source" : "persons.csv",
        "column" : "stagename"
      }
    } ],
    "links" : [ {
      "@type" : {
        "source" : "Company",
        "name" : "produced"
      },
      "@src" : {
        "source" : "producers.csv",
        "column" : "corpname"
      },
      "@dest" : {
        "source" : "producers.csv",
        "column" : "filmid"
      }
    }, {
      "@type" : {
        "source" : "Person",
        "name" : "actedin"
      },
      "@src" : {
        "source" : "actors.csv",
        "column" : "personid"
      },
      "@dest" : {
        "source" : "actors.csv",
        "column" : "filmid"
      }
    }, {
      "@type" : {
        "source" : "Person",
        "name" : "workedin"
      },
      "@src" : {
        "source" : "staff.csv",
        "column" : "personid"
      },
      "@dest" : {
        "source" : "staff.csv",
        "column" : "filmid"
      }
    } ]
  }
  
```


## Notes on usage

Currently, it is responsibility of the user to provide a correct and well formed
schema document.   Validation of syntax  and datasources by  _stellar-ingest_ is
minimal. Also, there is no way  for _stellar-ingest_ to catch any semantic error
made by the user, like mixing up  data fields (e.g. assign an address instead of
a person's name).
