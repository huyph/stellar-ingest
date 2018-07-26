#!/bin/bash

INGEST_VERSION=$(lein project-version 2> /dev/null)
INGEST_TAG=$(git describe --tags|head -1 2> /dev/null)

lein cloverage --coveralls && curl -F 'json_file=@target/coverage/coveralls.json' "https://coveralls.io/api/v1/jobs";
echo ""; echo ""; echo "branch: $BUILDKITE_BRANCH, version: $INGEST_VERSION, PR: $BUILDKITE_PULL_REQUEST, travis_tag: $BUILDKITE_TAG, ingest_tag: $INGEST_TAG"

if [ "$BUILDKITE_BRANCH" == "devel" ]; then
	if [[ $INGEST_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
		echo "Publishing snapshot from devel branch.";
		lein deploy;
		lein deploy-uberjar;
		./scripts/docker/dockerize.sh;
	else
		echo "ERROR invalid snapshot version."; exit 1;
	fi;
fi

if [ -n "$BUILDKITE_TAG"  ]; then
	if [ "$BUILDKITE_TAG" == "v$INGEST_VERSION" ]; then
		echo "Publishin release version $INGEST_VERSION with tag $TRAVIS_TAG";
		lein deploy;
		lein deploy-uberjar;
		./scripts/docker/dockerize.sh;
	else
		echo "ERROR version/tag mismatch $INGEST_VERSION/$TRAVIS_TAG"; exit 1;
	fi;
fi
