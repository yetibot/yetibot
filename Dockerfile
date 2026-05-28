# syntax=docker/dockerfile:1
FROM clojure:lein

LABEL maintainer="Trevor Hartman <trevorhartman@gmail.com>"

EXPOSE 3003

ENV WORKDIR=/usr/src/app
ENV LOGDIR=/var/log/yetibot

RUN mkdir -p $WORKDIR && mkdir -p $LOGDIR

# Copy project files first for optimal layer caching
COPY ./project.clj $WORKDIR/project.clj
# Optionally copy profiles.clj if it exists (needed for +docker profile)
COPY profiles.clj* $WORKDIR/

COPY .java.policy /root/
COPY .java.policy $WORKDIR/.java.policy
COPY .java.policy /docker-java-home/jre/lib/security/java.policy

RUN mkdir -p /root/.ssh && touch /root/.ssh/known_hosts

WORKDIR $WORKDIR

RUN apt-get update && apt-get install curl git -y && apt-get clean

# The `code` command shells out to git and the Gemini CLI to author changes and
# open PRs. git is installed above; add Node.js and the Gemini CLI here so the
# command works at runtime. Set YB_GEMINI_KEY to enable it.
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @google/gemini-cli \
    && rm -rf /var/lib/apt/lists/*

# Download ALL dependencies into the image (not using cache mount)
# This bakes the JARs into the image layer for fast startup
RUN lein deps

# Pre-download docker profile dependencies too
RUN lein with-profile +docker deps

COPY ./src $WORKDIR/src/
COPY ./resources $WORKDIR/resources/
COPY ./test $WORKDIR/test/

VOLUME $WORKDIR/config/

VOLUME $LOGDIR

HEALTHCHECK CMD curl --fail http://localhost:3003/healthz || exit 1

CMD ["lein", "with-profile", "+docker", "run"]
