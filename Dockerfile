FROM clojure:lein-2.5.3

MAINTAINER Trevor Hartman <trevorhartman@gmail.com>

EXPOSE 3000

RUN mkdir -p /usr/src/app

COPY ./src /usr/src/app/

COPY ./project.clj /usr/src/app/project.clj

WORKDIR /usr/src/app

RUN lein deps

VOLUME /usr/src/app/config/

CMD ["lein", "run"]
