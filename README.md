# Datomic Curation Tools

A set of tools for use with the WormBase Datomic database.

## Clojure Script Web Application(s) ##

Web applications which use the [pseduoace](https://github.com/WormBase/pseudoace)
representation of the [Wormbase database migration](https://github.com/WormBase/db-migration)

Application are named:

* trace: Querying and viewing of the objects in the migrated database
   using a "Table Maker" style interface.

* colonnnade: Editing objects in the migrated database (links to
   trace)


## Dependencies ##

* Datomic
* pseudoace


## Environment setup

1. Download and install [leiningen](http://leiningen.org/)
2. Configure the environment
   Use the following script.

   ```bash

    source env.sh
    ```
  Set TRACE_OAUTH2_CLIENT_SECRET and TRACE_OAUTH2_CLIENT_ID to the
  respective values.  The values for these variables are obtained from
  the
  [google developers console](https://console.developers.google.com/apis/credentials/oauthclient?project=wb-test-trace)


3. Build the application, checking for old dependencies

   ```bash
   lein do deps, ancient
   lein cljsbuild once
   ```

4. Run the web applications
  ```bash

  lein ring server "${TRACE_PORT}"
  ```

  or

  ```bash
  lein ring server-headless "${TRACE_PORT}"
  ```


Applications can be deployed to a Java server container,
by supplying the war file generated by the lein command:

```bash

lein ring uberwar
```

## Docker

### Setup of ECR repositories

Ensure the following two repositories are created in the [ECR][Elastic Container Registry]:

  * wormbase/datomic-curaiton-tools
  * wormbase/datomic-curaiton-tools_nginx-proxy

```bash
aws ecr describe-repositories
```

If they are not, then create them with:

```bash

aws ecr create-repository wormbase/datomic-curaiton-tools
aws ecr create-repository wormbase/datomic-curaiton-tools_nginx-proxy
```

### Build the application artefact

```bash
make clean && make docker/app.jar
```

### Build docker images

```bash
make build
```

Inspect the output of `docker images` and check it makes sense.

### Run docker images locally to test

```bash
make run
```

Test the web interface locally:

```bash
python -m webbrowser http://localhost/colonnade/
```

### Deploy the images to ECR

```bash
# Tag first
make docker-tag

# Push the images to ECR
make docker-push-ecr
```

### Test locally with Elastic Bean Stalk configuration

*Important:*

The docker images referenced by the `Dockerrun.aws.json`
must have previously been pushed to the ECR repositories.

```bash
WS_RELEASE="WS255" # example - change to release under test
eb local run --port=8090 --envvars TRACE_DB=datomic:ddb://us-east-1/${WS_RELEASE}/wormbase
```
