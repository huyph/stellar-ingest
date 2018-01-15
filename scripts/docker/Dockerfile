FROM ubuntu:xenial

# Production packages.
RUN apt-get update && apt-get install -y openjdk-9-jre-headless

# Troubleshooting packages. Can be excluded in production.
RUN apt-get install -y emacs-nox vim less curl

RUN mkdir -p /opt/stellar
COPY stellar-ingest-0.0.2-SNAPSHOT-standalone.jar /opt/stellar
RUN ln -s /opt/stellar/stellar-ingest-0.0.2-SNAPSHOT-standalone.jar /opt/stellar/stellar-ingest.jar

# RUN echo "some stuff" > /usr/local/bin/docker-entrypoint.sh
# RUN chmod +x /usr/local/bin/docker-entrypoint.sh
# ENTRYPOINT ["docker-entrypoint.sh"]

RUN mkdir -p /data/user
RUN chmod -R o+rwx /data/user
RUN mkdir -p /data/examples
COPY imdb_small.csv /data/examples

EXPOSE 3000

#CMD ["/usr/bin/java", "-cp /opt/stellar/stellar-ingest.jar", "stellar_ingest.rest"]
CMD /usr/bin/java -cp /opt/stellar/stellar-ingest.jar stellar_ingest.rest
