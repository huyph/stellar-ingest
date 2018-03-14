# Input data format

Currently _stellar-ingest_ accepts relational input data only in the form of
_comma separated values_ (CSV) files, as defined by
[RFC4180](https://tools.ietf.org/html/rfc4180).

## Input data syntactic requirements

Because  of the  procedure internally  used by  _stellar-ingest_ to  convert CSV
files into  graphs, additional limitations  to the  format and content  of input
data apply. All input files must be pre-processed accordingly.

- All  input files  are expected to  have a  header row, that  is the  first row
  should contain the (comma-separated) column names.

- Only  comma is  accepted as  separator (e.g.  no  tabs or  semi-colons). 

- Double-quotes can be used to enclose  values, although this is of limited use,
  considering the following point.
  
- All  white spaces (also leading  and trailing) included between  two separators
  (commas) are considered part of the field.

- There should be no missing values, that is a sequence of two separators coming
  immediately one after  the other. If values can be  missing, it is recommended
  that an appropriate placeholder is used.

- UTF-8 input  is supported for the  data, although use of  foreign scripts and
  special characters in the column names is discouraged. Column names should use
  exclusively  ASCII  characters  of  the English  alphabet  and  ASCII  hyphens
  (_minus_ key) or dash (_underscore_ key) as word separator.

__Note:__ it is  recommended that the same limitations given  above for choosing
CSV  column  names,  are  also  applied  when  naming  elements  in  the  [graph
schema](schema.md) (vertex and edge types and their attributes).

## Input data structure requirements

In the mappings, each type of vertex or edge must be populated from a single CSV
file. It is  not possible to retrieve attributes from  different files (vertical
partition). It is in principle possible to populate a single type using (entire)
rows of different files (horizontal partition),  but it is responsibility of the
user  to ensure  the uniqueness  of identifiers  among these  files, hence  this
practice is discouraged.

## Input data validation

It is recommended  that users validate their input data,  before proceeding with
large-volume analytics tasks, in which the effects of erroneous ingestion may be
difficult to identify.

A simple validation  procedure consists in extracting an  easily manageable data
subset (like  a few rows),  converting it to  a graph using  _stellar-ingest_ in
standalone mode (see [README](../README.md)) and manually inspecting the output.
A  user  need  not  have  a  full understanding  of  the  EPGM  format  to  spot
inconsistencies in the output such as  values assigned to the wrong attribute or
mangled values.

Another recommended procedure, in case of  datasets of multiple CSV files, is to
initially  ingest  inidividual files.  This  is  possible  only for  files  that
    represent graph vertices.

__Note:__ during ingestion validation, it can  be useful to remember that, while
each  graph element  receives a  new identifier  during ingestion,  its original
identifier (from the CSV file) is preserved as attribute `__id`.

