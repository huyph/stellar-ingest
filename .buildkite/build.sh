#!/bin/bash
set -euo pipefail

buildkite-agent artifact download target/uberjar/*.jar ./ --step ':clojure: build' --build ${BUILDKITE_BUILD_ID}

INGEST_TAG=$(buildkite-agent meta-data get "ingest-version")
echo "$INGEST_TAG"

if [[ $INGEST_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  docker build -f scripts/docker/Dockerfile -t data61/stellar-ingest:snapshot .
fi

