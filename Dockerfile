FROM clojure:lein-2.8.1

MAINTAINER Trevor Hartman <trevorhartman@gmail.com>

EXPOSE 3000

ENV WORKDIR /usr/src/app
ENV LOGDIR /var/log/yetibot

RUN mkdir -p $WORKDIR && mkdir -p $LOGDIR

COPY ./src $WORKDIR/src/

COPY ./test $WORKDIR/test/

COPY ./project.clj $WORKDIR/project.clj

COPY .java.policy $HOME/
COPY .java.policy $WORKDIR/.java.policy
# overwrite the default location for linux
COPY .java.policy /docker-java-home/jre/lib/security/java.policy


WORKDIR $WORKDIR

RUN lein deps

VOLUME $WORKDIR/config/

VOLUME $LOGDIR

CMD ["lein", "run"]
