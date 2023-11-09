FROM clojure:lein

# Yetibot needs curl. If we ever switch to alpine, make sure to install it.

MAINTAINER Trevor Hartman <trevorhartman@gmail.com>

EXPOSE 3003

ENV WORKDIR /usr/src/app
ENV LOGDIR /var/log/yetibot

RUN mkdir -p $WORKDIR && mkdir -p $LOGDIR

COPY ./src $WORKDIR/src/

COPY ./resources $WORKDIR/resources/

COPY ./test $WORKDIR/test/

COPY ./project.clj $WORKDIR/project.clj

COPY .java.policy $HOME/
COPY .java.policy $WORKDIR/.java.policy
# overwrite the default location for linux
COPY .java.policy /docker-java-home/jre/lib/security/java.policy

# prepare ssh
RUN mkdir -p /root/.ssh && touch /root/.ssh/known_hosts

WORKDIR $WORKDIR

# lein deps requires git
RUN apt-get update && apt-get install git -y && apt-get clean
RUN lein deps

VOLUME $WORKDIR/config/

VOLUME $LOGDIR

HEALTHCHECK CMD curl --fail http://localhost:3003/healthz || exit 1

CMD ["lein", "with-profile", "+docker", "run"]
