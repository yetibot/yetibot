#!/bin/bash
# script to build and deploy to Docker Hub

set -ev

# if [ "$TRAVIS_BRANCH" = "master" ]; then

  docker login -e="$DOCKER_EMAIL" -u="$DOCKER_USERNAME" -p="$DOCKER_PASSWORD"
  docker version
  lein docker build

  # push the version tag
  lein docker push

  # check to see if it's a release version
  version=$(lein version)
  echo "$version"

  if [[ "$version" =~ SNAPSHOT ]]; then
    echo "Snapshot version, tagging docker snapshot and pushing..."
    docker tag yetibot/yetibot:"$version" yetibot/yetibot:snapshot
    docker push yetibot/yetibot:snapshot
  else
    echo "Release version, tagging docker latest and pushing..."
    docker tag yetibot/yetibot:"$version" yetibot/yetibot:latest
    docker push yetibot/yetibot:latest
  fi

# else
#   echo "Not on master, skipping Docker build/push"
# fi
