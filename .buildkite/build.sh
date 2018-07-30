#!/bin/bash
set -euo pipefail

buildkite-agent artifact download target/uberjar/*.jar ./ --step ':clojure: build' --build ${BUILDKITE_BUILD_ID}
mv target/uberjar/stellar-ingest-*-standalone.jar .

INGEST_VERSION=$(buildkite-agent meta-data get "ingest-version")
echo "$INGEST_VERSION"

if [[ $INGEST_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  docker build -f scripts/docker/Dockerfile -t data61/stellar-ingest:snapshot .
fi

