#!/bin/bash

BASEDIR=$(dirname "$0")

docker build -t data61/stellar-ingest:snapshot-centos -f "${BASEDIR}/Dockerfile.centos" "${BASEDIR}/../../"
