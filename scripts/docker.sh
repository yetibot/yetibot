#!/bin/bash
# script to build and deploy to Docker Hub

set -ev

docker login -e="$DOCKER_EMAIL" -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
docker version
lein docker build

# push the version tag
lein docker push

# check to see if it's a release version
version=`lein version`
echo $version

if [[ "$version" =~ "SNAPSHOT" ]]; then
  echo "Snapshot version, tagging docker snapshot and pushing..."
  docker tag devth/yetibot:$version devth/yetibot:snapshot
  docker push devth/yetibot:snapshot
else
  echo "Release version, tagging docker latest and pushing..."
  docker tag devth/yetibot:$version devth/yetibot:latest
  docker push devth/yetibot:latest
fi

