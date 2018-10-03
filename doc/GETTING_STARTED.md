# Yetibot Getting Started

To get a Yetibot running you need:

1. Some config
1. A Postgres database
1. A way to run it (i.e. `docker` or `lein`)

## Docker Compose

Docker Compose satisfies these requirements very quickly. Run from the root of
this repo:

```bash
docker-compose up
```

This starts up a Postgres container and a Yetibot container, configured to
connect to IRC as user name `yetibot_demo`. Once it's up check out
[http://localhost:3456](http://localhost:3456) to view the dashboard.

See the [docker-compose.yml](../docker-compose.yml) file to look at exactly how
these containers are configured. This demonstrates a very minimal default config
that you can modify. For example, you could use Slack instead by switching to a
config like:

```yaml
    environment:
      - YB_ADAPTERS_SLACK_TYPE=slack
      - YB_ADAPTERS_SLACK_TOKEN=xoxb-my-token
      - YB_DB_URL=postgresql://yetibot:yetibot@postgres:5432/yetibot
```

## Config

A very minimal config would be:

```clojure
{:yetibot
  {:adapters {:freenode {:type "irc",
                        :username "my-yetibot",
                        :host "chat.freenode.net",
                        :port "7070",
                        :ssl "true"}}}}
```

This instructs Yetibot to join freenode with the username `my-yetibot` (change
it to whatever you like).

If you don't configure a Postgres database, it defaults to:

```bash
postgresql://localhost:5432/yetibot
```

It expects the database to already exist, but any tables will be created
idempotently on startup. To override the default connection string along with
the above config, it'd look like:

```clojure
{:yetibot
 {:adapters {:freenode {:type "irc",
                        :username "my-yetibot",
                        :host "chat.freenode.net",
                        :port "7070",
                        :ssl "true"}}
  :db {:url "postgresql://user:pass@mydb:5432/yetibot"}}}
```

For full config see the
[CONFIGURATION](https://github.com/yetibot/yetibot.core/blob/master/doc/CONFIGURATION.md)
docs.

## Postgres

There are many ways to install Postgres. Here we demonstrate two common
approaches:

### Docker

As usual, Docker makes things easier when it comes to infra:

```bash

docker run -d -p 5432:5432 --name postgres \
  --restart="always" \
  -v /pgdata:/var/lib/postgresql/data \
  -e POSTGRES_USER="yetibot" \
  -e POSTGRES_PASSWORD="yetibot" \
  -e POSTGRES_DB="yetibot" \
  postgres:latest

docker logs -f postgres

# to remove postgres docker container

docker rm -f postgres
```

Assuming you use a Docker link from another container to this container, the
connection string is then:

```bash
postgresql://yetibot:yetibot@postgres:5432/yetibot
```

As an example of Docker linking, you could use `psql` from another container
like:

```bash
docker run --rm -it --link postgres postgres bash
psql -h postgres -U yetibot
\l
\q
exit
```

### Ubuntu VM

Much of this is borrowed from [DigitalOcean's
docs](https://www.digitalocean.com/community/tutorials/how-to-install-and-use-postgresql-on-ubuntu-16-04):

```bash
sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib
sudo -u postgres psql
createdb yetibot
```

## Run it

There are a few ways to quickly run a Yetibot:

1. Docker - [read the Yetibot on Docker docs](doc/DOCKER.md)
1. Grab an archive of the source from the [Yetibot
   releases](https://github.com/yetibot/yetibot/releases), unzip, put the config
   in place and `lein run`
1. Clone the source of this repo, put the config in place and `lein run`

As an example, here's how you could get the latest code from `master`, extract,
put config in place, and run it (assumes you already have
[Leiningen](https://github.com/technomancy/leiningen) installed):

```bash
cd /tmp
curl https://codeload.github.com/yetibot/yetibot/tar.gz/master | tar xvz
cd yetibot-master
cat << EOF > config.edn
{:yetibot
 {:adapters
  {:freenode
   {:type "irc"
    :username "my-yetibot"
    :host "chat.freenode.net"
    :port "7070"
    :ssl "true"}}}}
EOF
YB_LOG_LEVEL=debug CONFIG_PATH=config.edn lein run
```

Once it starts up you'll see a log like:

```
17-05-28 23:27:56 deep.local INFO [yetibot.core.loader:41] - ☑ Loaded 84 namespaces matching [#"^yetibot\.(core\.)?commands.*" #"^.*plugins\.commands.*"]
```

At this point it should be connected to Freenode. Trying running a command
against it:

```bash
/msg my-yetibot !echo Hello, Yetibot!
```

And you should get a reply:

```bash
my-yetibot: Hello, Yetibot!
```

**NB**: Soon there will be a way to run a Yetibot with zero config in local REPL
mode :zap: #628 :zap:

## Questions

If these docs don't work for you please [open an
issue](https://github.com/yetibot/yetibot/issues/new)!

You can also try setting an env var `YB_LOG_LEVEL=debug` when running.
