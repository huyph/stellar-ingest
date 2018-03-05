FROM ubuntu:xenial

# Production packages.
RUN apt-get update && apt-get install -y openjdk-9-jre-headless

# Troubleshooting packages. Can be excluded in production.
RUN apt-get install -y emacs-nox vim less curl

# Program installation dirs.
RUN mkdir -p /opt/stellar-ingest/examples

# Copy program binary.
COPY stellar-ingest-*-standalone.jar /opt/stellar-ingest
RUN ln -s /opt/stellar-ingest/stellar-ingest-*-standalone.jar /opt/stellar-ingest/stellar-ingest.jar

# Copy example data.
COPY imdb_small.csv /opt/stellar-ingest/examples

# Make sure program dirs are readable by everyone.
RUN chmod -R o+rX /opt/stellar-ingest

# Ingestor REST port.
EXPOSE 3000

# Interpret relative paths coming from REST request as being based here.
WORKDIR /opt/stellar/data

# By default run the REST interface namespace.
ENTRYPOINT ["/usr/bin/java", "-cp", "/opt/stellar-ingest/stellar-ingest.jar"]
CMD ["stellar_ingest.rest"]
