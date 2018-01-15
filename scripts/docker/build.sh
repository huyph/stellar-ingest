jarfile="../../target/uberjar/stellar-ingest-0.0.2-SNAPSHOT-standalone.jar"
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
docker build -t stellar/ingest:0.0.2-SNAPSHOT .

# Tag to allow upload to CSIRO registry.
docker tag stellar/ingest:0.0.2-SNAPSHOT etd-docker01.it.csiro.au/stellar/ingest:0.0.2-SNAPSHOT

# TODO: add tag 'latest' to always pull latest version...

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
