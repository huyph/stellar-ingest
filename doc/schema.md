# Stellar graph schema

To build a graph _stellar-ingest_ requires a graph _schema_ to be defined by the
user.

Strictly speaking, a graph schema is just an abstract description of the classes
that  constitute  the graph  **vertices**  (e.g.   _Person_, _Firm_)  and  their
relationships that are represented as  graph **edges** (e.g.  Person _works for_
Firm). Additionally  both vertices  and edges may  have **attributes**,  that is
values attached  to them that  further qualify them  (e.g. a Person's  _name_ or
_address_).

## The graph schema JSON document

Throughout  _stellar-ingest_ documentation  _graph schema_  often refers  to the
_graph schema document_, a JSON-encoded  message that contains the proper schema
(as  defined above)  together with  additional information,  mainly required  to
populate the graph with actual data provided by the user.

In particular  the graph schema document  contains at least a  list of _sources_
(paths of  CSV files to  be ingested) and  a list of  _mappings_ (correspondence
between CSV columns and graph elements).

A fully  working example of  graph schema document  is provided for  the example
[MovieDB dataset](../resources/examples/imdb_norm/imdb_norm_schema.json).  The main
[README](../README.md) document demonstrates  how to use it  for graph ingestion
with both current _stellar-ingest_ interfaces (REST and CLI).

The remainder of this page describes in detail the schema document format, using
the MovieDB example.  The description focuses on the _raw_  format, encoded in JSON
and directly usable by _stellar-ingest_.

For an example of building the schema document from within a Python script, see
instead the example provided with _Stellar_ [Python
client](https://github.com/data61/stellar-py/blob/v0.2.1/examples/stellar.ipynb).

__Note:__ it is recommended that the  names of schema elements contain no spaces
and  employ a  limited  set of  characters  and symbols.   Refer  to the  [input
format](input.md) documentation to learn more about these limitations.

### Top-level entries of the graph schema JSON document

The graph  schema document  is a  single JSON object  (delimited by  `{}`), that
wraps a set of top-level entries.

```json
{
  "sources" : [ ... ],
  "graphSchema" : { ... },
  "mapping" : { ... }
}
```

When stored in a  JSON file (e.g. to pass it  to _stellar-ingest_ CLI interface)
the object is just  written out as it is. When  passed via _stellar-ingest_ REST
API this  object (without  further qualification  or wrapping)  is passed  as the
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

Entries `completeUrl` and  `abortUrl` define URLs to  which respectively success
or failure of the ingestion will be notified. They are really relevant only when
running  the entire  _Stellar_ platform,  as  they are  instrumental to  correct
modules coordination. When  running _stellar-ingest_ standalone they  can be set
to  dummy  values  or  to  mock   servers  (like  those  freely  available  from
https://requestb.in).

Finally `sessionId` is  also only needed for module coordination  and can be set
to a dummy string when running standalone.

### The _sources_ entry

The entry `sources` should be set to an array of file paths. These must represent all 
and only the CSV files used to populate the graph.

```json
"sources" : [ "films.csv",
              "persons.csv",
              "corps.csv",
              "producers.csv",
              "actors.csv",
              "staff.csv" ],
              
```

The paths  can be absolute  or relative to the  directory where the  ingestor is
started.  Care must be taken, when using _stellar-ingest_ in a Docker container,
that any required host files  are found. The official [Docker images](docker.md)
starts _stellar-ingest_ inside the `/opt/stellar/data` directory.

### The _graphSchema_ entry

The `graphSchema`  object represent the  proper `graph schema`. It  contains two
entries,  `classes` and  `classLinks`, which  define respectively  the types  of
vertices and edges present  in the graph. Each of these two  entries is an array
of definitions.

```json
"graphSchema" : {
    "classes" : [ { ... } , { ... }, ... ],
    "classLinks" : [ { ... } , { ... }, ... ]
  }
```

Each vertex type, listed under `classes`, is defined by a `name` (unique among
all  classes) and  a property  object  `props`. The  latter associates  desired
property  names  (unique in  the  same  class,  but possibly  repeating  between
classes) with their type.

Property types  are currently not fully  exploited by all operations  within the
`Stellar` platform, yet  it is recommended that the user  generates schemas with
correct types. Valid types are `string`, `integer`, `float` and `boolean`. 

```json
"graphSchema" : {
    
    "classes" : [ 
    
    {
      "name" : "Film",
      "props" : {
        "title" : "string",
        "year" : "string",
        "classification" : "string",
        "isforeign" : "boolean"
      }
    },
    
    ...
   ],
    
    "classLinks" : [ { ... } , { ... }, ... ]
  }
```

Each  edge type,  listed  under `classLinks`,  is defined  by  a `name`  (unique
among all classes), a  `source` and a `target`. The last two  must be names of
vertex types  that were defined  under `classes`.  Additionally a edge  type can
have a property object `props`, similar to that defined for vertex types.

```json
"graphSchema" : {
    
    "classes" : [ { ... } , { ... }, ... ],
        
    "classLinks" : [
    {
      "name" : "actedin",
      "source" : "Person",
      "target" : "Film"
    },

    {
      "name" : "workedin",
      "source" : "Person",
      "target" : "Film"
    }, 
    
    ...
    ]
  }
```

### The _mapping_ entry

The  `mapping` object  specifies  mappings  between the  sources  and the  graph
schema, that  is a correspondence between  schema elements and CSV  columns.  It
contains two  entries, `nodes` and  `links`, which define  respectively mappings
for vertices and edges  of the graph.  Each of these two entries  is an array of
definitions.

```json
"mapping" : {
    "nodes" : [ { ... }, { ... }, ... ],
    "links" : [ { ... }, { ... }, ... ]
  }
```

Each node mappings, listed under `nodes` is an object that must at least contain
the _two special entries_ `@type` and `@id`. The former sets the vertex type and
the latter specifies  a unique identifier (_primary key_) for  this vertex type.
Additionally an arbitrary number of _attribute entries_ can exist.

Entry `@type` must be the name of a  class from the schema.  Entry `@id` and the
_attribute entries_ have the same structure:  they specify an input CSV file and
one of it columns. Each vertex mapping must be created from a single CSV file.

In the example below, vertices of type  _Film_ will be created from the CSV file
_films.csv_ (one vertex per row). Such vertices will be identified by the values
of column  _id_. Their attributes (defined  in the schema) will  be populated by
the specified column  values: attribute `title` from  column `filmtitle`, `year`
from `year`, `classification` from `genre` and `isforeign` from `foreign`.

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
     },
    ...
    ],
    
    "links" : [ { ... }, { ... }, ... ]
  }
  
```

Each link mappings, listed under `links` is an object that must at least contain
the _three  special entries_ `@type`,  `@src` and  `@dest`.  The first  sets the
edge  type and  the others  specify two  vertices (their  identifiers) that  are
connected by the edge.  Additionally  an arbitrary number of _attribute entries_
can exist.

Entry `@type` must be  the name of a classLink from  the schema. The _attribute
entries_ are defined like those in `nodes` mappings.  Entries `@src` and `@dest`
are the  edge counterpart of a  vertex `@id`. Their mapped  columns must contain
identifiers (_primary keys_)  of connected vertices.  Each edge  mapping must be
created from a single CSV file.

In the example  below, vertices of type  _actedin_ will be created  from the CSV
file _actors.csv_ (one vertex per row). Looking at the schema, defined earlier on
this page,  this kind  of edge connects  a _Person_ vertex  to a  _Film_ vertex.
Each row of  _actors.csv_ contains columns _personid_ and _filmid_  which link a
person (actor) to a  film. These values are used to  populate entries `@src` and
`@dest`.

```json
"mapping" : {
    "nodes" : [ { ... }, { ... }, ... ],
    
    "links" : [ {
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
    },
     ...
    ]
  }
  
```

## Notes on usage

Currently, it is responsibility of the user to provide a correct and well formed
schema document.  Validation  of syntax and data sources  by _stellar-ingest_ is
minimal. Also, there is no way for  _stellar-ingest_ to catch any errors such as
mixing up data fields (e.g. assign an address instead of a person's name).
