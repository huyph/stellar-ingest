INGEST_VERSION=$(cd ../..; lein pom &> /dev/null && mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive exec:exec)
# echo $INGEST_VERSION

jarfile="../../target/uberjar/stellar-ingest-$INGEST_VERSION-standalone.jar"
example="../../resources/imdb/imdb_small.csv"

echo "Checking for required files:"
echo "- $jarfile"
echo "- $example"

if [ ! -r "$jarfile" ] || [ ! -r "$example" ]; then
    echo "Could not find some files required by the docker container."
    echo "You must first run 'lein uberjar' in the project root, then"
    echo "call this script from inside <project-root>/scripts/docker."
    exit 1
fi

# Copy here files
cp $jarfile .
cp $example .

# TODO: Capture failure and print a message.
docker build -t data61/stellar-ingest:$INGEST_VERSION .
docker build -t data61/stellar-ingest:latest .

# Remove temporary file copies.
rm $(basename $jarfile)
rm $(basename $example)

# To start the container use script ingest.sh included in this directory.
#
# To directly run the container use:
# mkdir -p /tmp/data/user; docker run -p 3000:3000 -v /tmp/data/user:/data/user stellar/ingest:0.0.2-SNAPSHOT
#
# Use this to run a shell in the container for debugging:
# mkdir -p /tmp/data/user; docker run -p 3000:3000  -v /tmp/data/user:/data/user -ti stellar/ingest:0.0.2-SNAPSHOT bash
