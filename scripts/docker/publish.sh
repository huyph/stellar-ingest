INGEST_VERSION=$(cd ../..; lein pom &> /dev/null && mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive exec:exec)

docker login

# docker push registry-host:5000/namespace/repository-name
docker push data61/stellar-ingest:$INGEST_VERSION
docker push data61/stellar-ingest:latest
