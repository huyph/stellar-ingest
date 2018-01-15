#!/bin/bash  

SCRIPTPATH="$( cd "$(dirname "$0")" ; pwd -P )"

DATAPATH="/tmp/data/user"

setup() {
    if [ -d "$DATAPATH" ]; then
        echo "Data mount point found: $DATAPATH"
    else
        echo "Creating data mount point found: $DATAPATH"
        mkdir -p "$DATAPATH"
        
        if [ $? -ne 0 ]; then
            echo "Error creating mount point... exiting."
            exit 1
        fi
    fi
}

startme() {
    echo "Starting stellar-ingest..."
    docker-compose -f "$SCRIPTPATH/docker-compose.yml" -p stellar-ingest up -d
}

stopme() {
    echo "Stopping stellar-ingest..."
    docker-compose -f "$SCRIPTPATH/docker-compose.yml" -p stellar-ingest down
}

case "$1" in 
    start)   setup; startme ;;
    stop)    stopme ;;
    restart) stopme; startme ;;
    *) echo "usage: $0 start|stop|restart" >&2
       exit 1
       ;;
esac
