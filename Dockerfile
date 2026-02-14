# syntax=docker/dockerfile:1
FROM clojure:lein

LABEL maintainer="Trevor Hartman <trevorhartman@gmail.com>"

EXPOSE 3003

ENV WORKDIR /usr/src/app
ENV LOGDIR /var/log/yetibot

RUN mkdir -p $WORKDIR && mkdir -p $LOGDIR

COPY ./project.clj $WORKDIR/project.clj

COPY .java.policy $HOME/
COPY .java.policy $WORKDIR/.java.policy
COPY .java.policy /docker-java-home/jre/lib/security/java.policy

RUN mkdir -p /root/.ssh && touch /root/.ssh/known_hosts

WORKDIR $WORKDIR

RUN apt-get update && apt-get install curl git -y && apt-get clean
RUN --mount=type=cache,target=/root/.m2/repository lein deps

COPY ./src $WORKDIR/src/
COPY ./resources $WORKDIR/resources/
COPY ./test $WORKDIR/test/

VOLUME $WORKDIR/config/

VOLUME $LOGDIR

HEALTHCHECK CMD curl --fail http://localhost:3003/healthz || exit 1

CMD ["lein", "with-profile", "+docker", "run"]
