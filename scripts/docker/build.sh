#!/usr/bin/env bash

################################################################################
# Stellar docker image building and publishing script.
#
# Copyright 2017-2018 CSIRO Data61
#
script_version="0.0.2"
#
# Released under the Apache License, Version 2.0
# See http://www.apache.org/licenses/LICENSE-2.0
################################################################################

################################################################################
## Loggers ##
#
# we define standard loggers: info, warn, error, fatal and debug if `verbose`
# is defined. Loggers print to stderr with log level colourised according to
# severity.

enable_log_colours() {
    r=$(printf "\e[1;31m")      # red       (error, fatal)
    g=$(printf "\e[1;32m")      # green     (info)
    y=$(printf "\e[1;33m")      # yellow    (warning)
    b=$(printf "\e[1;34m")      # blue      (debug)
    m=$(printf "\e[1;35m")      # magenta   (process name)
    c=$(printf "\e[1;36m")      # cyan      (timestamp)
    x=$(printf "\e[0m")         # reset     (log message)
}

if [ -t 2 ]; then
    # only if standard error is connected to tty (not redirected)
    enable_log_colours
fi

# log formatter - do not use directly, use the predefined log levels below
prog_name="$(basename "$0")"
date_format="%F %T %Z"   # YYYY-MM-DD HH:MM:SS ZZZZ
logger() {
    local prefix="${m}${prog_name}: ${c}$(date "+${date_format}") $prefix"
    local i
    if [ "$#" -ne 0 ]; then
        for i; do           # read lines from args
            echo "${prefix}${i}${reset}" >&2
        done
    else
        while read i; do    # read lines from stdin
            echo "${prefix}${i}${reset}" >&2
        done
    fi
}

# log levels. Usage either:
#   <level> "line 1" "line 2" "line 3" ;
#   or to prefix each line of output from a child process
#   process | <level> ;
info()  { local prefix="${g} Info:${x} " ; logger "$@" ; }
warn()  { local prefix="${y} Warn:${x} " ; logger "$@" ; }
error() { local prefix="${r}Error:${x} " ; logger "$@" ; }
fatal() { local prefix="${r}Fatal:${x} " ; logger "$@" ; exit 1 ; }
debug() {
    [ -z "$verbose" ] || {
        local prefix="${b}Debug:${x} "
        logger "$@"
    }
}

################################################################################
# Project specific configuration.

### Project identity
# String to identify 
project_id="stellar-ingest"
# project_id="stellar-py"
# project_id="stellar-search"

### Command to obtain the string version.
# The command should print the version string alone (on stdout).
#
# Java with Maven
# version_cmd="mvn -q -Dexec.executable=\"echo\" -Dexec.args='${project.version}' --non-recursive exec:exec"
#
# Clojure with lein-project-version plugin
version_cmd="lein project-version|tail -1"
#
# Clojure going through Maven
# version_cmd="lein pom &> /dev/null && mvn -q -Dexec.executable=\"echo\" -Dexec.args='${project.version}' --non-recursive exec:exec"
#
# Dummy: always fail to get version.
# version_cmd=false

### Version regular expressions.
# Define the format of release and snapshot versions.
#
# Maven-style semantic versioning.
snapshot_re="^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$"
release_re="^[0-9]+\.[0-9]+\.[0-9]+$"
# TODO: introduce  -hotfix version  too. Make  this an array  of pairs,  to link
# version regex with docker tag.

################################################################################
# Utilities

# Get the repository slug (e.g. data61/stellar-py) from git configuration. 
# Parms: none
# Return: repository slug
get_slug() {
    # Git repo origin, e.g. https://user@github.com/data61/stellar-ingest.git
    # Remove the trailing '.git'
    local orig=$(git config --get remote.origin.url|sed 's/\.git$//') res="$?"
    # Check for an error in executing git.
    if [ ! "$res" -eq "0" ]; then
        fatal "Could not read git repository origin."
        return $res
    # Running in a non-git directory just returns no origin.
    elif [ -z "$orig" ]; then
        fatal "This does not seem to be a git repository."
        return 1        
    fi
    # info "Found git origin: $orig.git"
    # Parse the origin URL. Instead of just pulling out the slug, check that the
    # data make sense for a Stellar module, otherwise return error.
    local owner project
    base=$(echo $orig|cut -d"/" -f1-3) ||
        { fatal "Error parsing git repository origin."; return 1; }
    owner=$(echo $orig|cut -d"/" -f4) ||
        { fatal "Error parsing git repository origin."; return 1; }
    project=$(echo $orig|cut -d"/" -f5) ||
        { fatal "Error parsing git repository origin."; return 1; }
    if [[ ! $base =~ https://([-a-zA-Z0-9]+@)?github.com ]] ||
       [[ ! $owner = "data61" ]] ||
       [[ ! $project =~ ^stellar- ]]; then
        fatal "This does not seem to be an official Stellar repository."
        return 1
    fi
    # Return the repo slug.
    echo "$owner/$project"
}

# Get the project identifier for the repository slug (e.g. stellar-ingest)
# Parms: none
# Return: project identifier
get_project_id() {
    # Try to get the project slug, calling get_slug().
    local slug project_id
    slug=$(get_slug) || return
    project_id=$(echo $slug|cut -d"/" -f2) ||
        { fatal "Error parsing git project id from slug."; return 1; }
    echo $project_id
}

# # Create a temporary directory using project id and PID
# # Parms: main script process id
# # Return: the temp directory path
# create_temp_dir() {
#     local pid=$1
#     if [[ ! $pid =~ [0-9]+ ]]; then
#         fatal "Invalid process id: \"$pid\""
#         return 1
#     fi
#     local proj_id temp_dir
#     proj_id=$(get_project_id) || return
#     temp_dir="/tmp/$proj_id-tmp-$script_pid"
#     mkdir -p $temp_dir 2> /dev/null ||
#         { fatal "Error creating temp directory $temp_dir."; return 1; }
#     echo $temp_dir
# }

# Get the version string for the current project.
# Parms: none
# Return: version string
get_version() {
    # Try to get the version string from the project definition.
    local version=$(eval "$version_cmd 2> /dev/null") res="$?"
    if [ ! "$res" -eq "0" ]; then
        fatal "Error executing: $version_cmd"
        fatal "Could not get project version."
        return $res
    fi
    # Just in case, let's check the version string makes sense.
    if [[ ! $version =~ $snapshot_re ]] && [[ ! $version =~ $release_re ]]; then
        fatal "Invalid version string: \"$version\"."
        return 1
    fi
    # All good, log the version and return it.
    echo $version;
}

# Based on project version, pick a generic docker tag (latest, snapshot, etc.)
# Parms: none
# Return: docker tag string
get_docker_tag() {
    # Try to get the project version, calling get_version().
    local version tag
    version=$(get_version) || return
    if [[ $version =~ $snapshot_re ]]; then
        tag="snapshot"
    elif [[ $version =~ $release_re ]]; then
        tag="latest"
    else
        # Note: this should never happen.
        fatal "Invalid version string: \"$version\"."
        return 1
    fi
    echo $tag
}

################################################################################
# MAIN

# TODO: check that docker credentials are there, otherwise suggest login.

# Get script directory, to reference sibling files (e.g. Dockerfile).
# TODO make dockerfile name configurable, check it exists.
script_dir="$( cd "$(dirname "$0")" ; pwd -P )"

# Identify the project.
info "Identifying current project."
proj_slug=$(get_slug) || { fatal "Exiting script."; exit 1; }
info "Found valid project slug: $proj_slug"
proj_id=$(get_project_id) || { fatal "Exiting script."; exit 1; }
info "Found valid project ID: $proj_id"

# Create a temporary working directory, that will be deleted on exit.
info "Creating docker build temporary directory."
tmpdir=$(mktemp -d -t "$proj_id-tmp.XXXXXXXXXX") ||
    { fatal "Error creating temp dir. Exiting script."; exit 1; }
info "Created temp dir: $tmpdir"
cleanup() { rm -rf "$tmpdir"; }
trap cleanup EXIT

info "Getting version string from project definition."
version=$(get_version) || { fatal "Exiting script."; exit 1; }
info "Found valid version string: \"$version\"."

info "Generating docker tags from version string."
tag=$(get_docker_tag) || { fatal "Exiting script."; exit 1; }
info "Docker image will be tagged as: \"$version\" and \"$tag\"."

# Copy necessary files to the temp directory and use as build context.
# TODO: change to defer var expansion and move file list to configuration.
# TODO: make this an associative array, host-path/docker-path.
files=(
    "../../target/uberjar/stellar-ingest-$version-standalone.jar"
    "../../resources/imdb/imdb_small.csv"
)

info "Processing files required for Docker container."
for f in "${files[@]}"; do
    ff="$script_dir/$f"
    if [ -r "$ff" ]; then
        info "Found file: $f"
        cp -r $ff $tmpdir ||
            { fatal "Unexpected error on file: $ff"; exit 1; };
    else
        fatal "Cannot read file: $f"
        exit 1
    fi
done

# The Dockerfile must be copied within the context.
dockerfile=$script_dir/Dockerfile
cp $dockerfile $tmpdir &> /dev/null ||
    { fatal "Error copying $dockerfile to $tmpdir.";
      fatal "Exiting script.";
      exit 1; }

# TODO: Capture failure and print a message.
docker build -t $proj_slug:$version $tmpdir ||
    { fatal "Docker error. Exiting script."; exit 1; }
docker build -t $proj_slug:$tag $tmpdir ||
    { fatal "Docker error. Exiting script."; exit 1; }

# This script should be copied from my repo and source by theirs...
# but it needs configuration... can I maintain it for all modules.
# alterantively it could check its own version and refuse to run.

# The build/publish and cleanup.

# TODO: add 2 dry run options
# that do not create the container and create but do not publish...

exit 0







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
