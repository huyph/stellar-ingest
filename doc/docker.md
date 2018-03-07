# Using _stellar-ingest_ via Docker

The official [Docker images](https://hub.docker.com/r/data61/stellar-ingest/tags/)
can be used to run _stellar-ingest_ via CLI and REST.

Below  are  the  steps  required  to   ingest  the  imdb  example  dataset  (see
[README.md](../README.md)).

## CLI ingestion via Docker image

``` bash
# Move the example directory.
cd resources/examples/imdb_norm

# Run the Docker container.
docker run \
  -v $PWD:/opt/stellar/data \
  -u $(id -u):$(id -g) \
  data61/stellar-ingest \
  stellar_ingest.schema \
  imdb_norm_schema.json \
  imdb_output \
  imdb
```

The  command  above  runs  stellar-ingest Docker  image,  mounting  the  current
directory into the  container's data directory (`-v` option) and  using the user
and group identifiers of the current user (`-u` option) to avoid file permission
issues. 

## REST ingestion via Docker image

Start the server from the Docker image with these commands:

``` bash
# Move the example directory.
cd resources/examples/imdb_norm

# Run the Docker container.
docker run \
  -v $PWD:/opt/stellar/data \
  -u $(id -u):$(id -g) \
  -p 3000:3000 \
  data61/stellar-ingest
```

The first two  command options are explained above. Option  `-p` makes port 3000
inside the container, accessible to the host (with the same number).

Now, trigger ingestions with a REST API request:

```bash
curl -X POST \
     --header 'Content-Type: application/json' \
     --header 'Accept: application/json' \
     -d '@imdb_norm_schema_rest.json'
     'http://localhost:3000/ingestor/ingest'
```

## Advanced Docker usage

For those who are more familiar with Docker, the container's _entry point_ calls
the Java  virtual machine with  the correct  classpath set, while  its _command_
invokes the main function in  namespace `stellar_ingest.rest`, as it can be
seen in _stellar-ingest_ [Dockerfile](../scripts/docker/Dockerfile):

```yaml
# By default run the REST interface namespace.
ENTRYPOINT ["/usr/bin/java", "-cp", "/opt/stellar-ingest/stellar-ingest.jar"]
CMD ["stellar_ingest.rest"]
```

### Runtime parameters

It  is therefore  possible, by  passing  additional command  line parameters  to
`docker run`, to  run as different application namespace or  function (as in the
CLI ingestion example).

Also, it is  possible to pass additional  options to the JVM  running inside the
container. For instance, to start the REST server with up to 2GB of heap memory:

``` bash
docker run \
  -v $PWD:/opt/stellar/data \
  -u $(id -u):$(id -g) \
  -p 3000:3000 \
  data61/stellar-ingest \
  -Xmx2g stellar_ingest/rest
```

### Mountpoints

When   running   inside   Docker,    _stellar-ingest_   working   directory   is
`/opt/stellar/data`.
