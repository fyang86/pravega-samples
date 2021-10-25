# Flink-Catalog-Demo

In this demo we will show how Flink SQL is using Pravega as an external, persistent catalog and processing data from Pravega with the help of Pravega Schema Registry Service.
All exercises are performed in the Flink SQL CLI, 
and the entire process uses standard SQL syntax, without a single line of Java/Scala code or IDE installation.

This demo is inspired by @wuchong's excellent work. [Flink-SQL demo](https://flink.apache.org/2020/07/28/flink-sql-demo-building-e2e-streaming-application.html)

## Prerequsites

- A Linux or MacOS computer with minimum 2GB memory.
- Docker and docker compose installed.



### Starting the Demo Environment

The components required in this demo are all managed in containers, so we will use docker-compose to start them.

We have already provided `datagen` and `sql-client` images for the demo. You can also choose to build these two images on your own following the next steps.
If you don't want to build images on your own just jump to [Start the Docker Compose Environment](#start-the-docker-compose-environment).

### Get the data
The dataset we are using is from the Alibaba Cloud Tianchi public dataset. It contains the user behavior on the day of November 27, 2017 (behaviors include “click”, “like”, “purchase” and “add to shopping cart” events). Each row represents a user behavior event, with the user ID, product ID, product category ID, event type, and timestamp.
Please download the demo data from [Google Drive](https://drive.google.com/file/d/1P4BAZnpCY-knMt5HFfcx3onVjykYt3et/view?usp=sharing) and move it into the `./datagen` folder (as `./data/user_behavior.log`).

The folder will be mounted by the Docker containers.

### Build the Docker Images
Change image path to your own docker repository and build path in `docker-compose.yml` before building images.
```
docker-compose build
```

### Start the Docker Compose Environment

The Docker Compose environment consists of the following containers:
- **Flink SQL Client**: used to submit queries and visualize their results.
- **Flink Cluster**: a Flink JobManager container and a Flink TaskManager container to execute queries.
- **DataGen**: the data generator. By default, it will send 1000 data per second, lasting for about 3 hours. You can also
  modify the `speedup` parameter in `docker-compose.yml` to adjust the data generating speed. It will first register the
  schema to Pravega schema registry service and then send the generated data to Pravega cluster automatically.
- **Pravega**: used as streaming data storage. Data generated by Datagen container will be sent to Pravega. 
  And the data stored in Pravega will be sent to Flink as a data source using [Pravega-Flink connector](https://github.com/pravega/flink-connectors).
- **Schema Registry**: the registry service to store and manage schemas for the unstructured data stored in Pravega streams.

To start all containers, run the following command in the directory that contains the docker-compose.yml file.
```
docker-compose up -d
```
This command automatically starts all the containers defined in the Docker Compose configuration in a detached mode. 
Run docker ps to check whether the 6 containers are running properly. You can also visit http://localhost:8081/ to see if Flink is running normally.

Finally, you can use the following command to stop all the containers after you finished the tutorial.
`docker-compose down`

## The Demo

### Start the SQL Client
To enter the SQL CLI client run:
```
docker-compose exec sql-client ./sql-client.sh
```
The command starts the SQL CLI client in the container. You should see the welcome screen of the CLI client:
```
                                   ▒▓██▓██▒
                               ▓████▒▒█▓▒▓███▓▒
                            ▓███▓░░        ▒▒▒▓██▒  ▒
                          ░██▒   ▒▒▓▓█▓▓▒░      ▒████
                          ██▒         ░▒▓███▒    ▒█▒█▒
                            ░▓█            ███   ▓░▒██
                              ▓█       ▒▒▒▒▒▓██▓░▒░▓▓█
                            █░ █   ▒▒░       ███▓▓█ ▒█▒▒▒
                            ████░   ▒▓█▓      ██▒▒▒ ▓███▒
                         ░▒█▓▓██       ▓█▒    ▓█▒▓██▓ ░█░
                   ▓░▒▓████▒ ██         ▒█    █▓░▒█▒░▒█▒
                  ███▓░██▓  ▓█           █   █▓ ▒▓█▓▓█▒
                ░██▓  ░█░            █  █▒ ▒█████▓▒ ██▓░▒
               ███░ ░ █░          ▓ ░█ █████▒░░    ░█░▓  ▓░
              ██▓█ ▒▒▓▒          ▓███████▓░       ▒█▒ ▒▓ ▓██▓
           ▒██▓ ▓█ █▓█       ░▒█████▓▓▒░         ██▒▒  █ ▒  ▓█▒
           ▓█▓  ▓█ ██▓ ░▓▓▓▓▓▓▓▒              ▒██▓           ░█▒
           ▓█    █ ▓███▓▒░              ░▓▓▓███▓          ░▒░ ▓█
           ██▓    ██▒    ░▒▓▓███▓▓▓▓▓██████▓▒            ▓███  █
          ▓███▒ ███   ░▓▓▒░░   ░▓████▓░                  ░▒▓▒  █▓
          █▓▒▒▓▓██  ░▒▒░░░▒▒▒▒▓██▓░                            █▓
          ██ ▓░▒█   ▓▓▓▓▒░░  ▒█▓       ▒▓▓██▓    ▓▒          ▒▒▓
          ▓█▓ ▓▒█  █▓░  ░▒▓▓██▒            ░▓█▒   ▒▒▒░▒▒▓█████▒
           ██░ ▓█▒█▒  ▒▓▓▒  ▓█                █░      ░░░░   ░█▒
           ▓█   ▒█▓   ░     █░                ▒█              █▓
            █▓   ██         █░                 ▓▓        ▒█▓▓▓▒█░
             █▓ ░▓██░       ▓▒                  ▓█▓▒░░░▒▓█░    ▒█
              ██   ▓█▓░      ▒                    ░▒█▒██▒      ▓▓
               ▓█▒   ▒█▓▒░                         ▒▒ █▒█▓▒▒░░▒██
                ░██▒    ▒▓▓▒                     ▓██▓▒█▒ ░▓▓▓▓▒█▓
                  ░▓██▒                          ▓░  ▒█▓█  ░░▒▒▒
                      ▒▓▓▓▓▓▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒░░▓▓  ▓░▒█░

    ______ _ _       _       _____  ____  _         _____ _ _            _  BETA   
   |  ____| (_)     | |     / ____|/ __ \| |       / ____| (_)          | |  
   | |__  | |_ _ __ | | __ | (___ | |  | | |      | |    | |_  ___ _ __ | |_
   |  __| | | | '_ \| |/ /  \___ \| |  | | |      | |    | | |/ _ \ '_ \| __|
   | |    | | | | | |   <   ____) | |__| | |____  | |____| | |  __/ | | | |_
   |_|    |_|_|_| |_|_|\_\ |_____/ \___\_\______|  \_____|_|_|\___|_| |_|\__|

        Welcome! Enter 'HELP;' to list all available commands. 'QUIT;' to exit.


Flink SQL>
```

### Create Pravega Catalog with DDL
First we need to create a Pravega Catalog to access the data stored in Pravega.
In order to use Pravega Catalog, we need to add schema registry group and register the schema of your serialization format to schema registry service. This procedure was done in the datagen container. You can check this by calling Pravega Schema Registry's REST API:
```
curl -X GET http://{SchemaRegistryIP}:9092/v1/groups/userBehavior/schemas?namespace=examples
```


Then the datagen container will send data to `userBehavior` stream in `examples` scope of Pravega. 
Pravega will send data to Flink as a data source by Pravega Flink connector. 

Pravega `scope` is mapped to Flink catalog `database` while Pravega `stream` is mapped to Flink catalog `table` automatically 
so that we don't need to manually rewrite DDLs to create tables.
We run the following DDL statement in SQL CLI to create a Flink catalog that connects to Pravega and schema-registry.
We will use Avro as the format for serialization in table sink as predefined in datagen, while Json is also another available
format.

```sql
CREATE CATALOG pravega WITH(
    'type' = 'pravega',                                     --Flink catalog type
    'controller-uri' = 'tcp://pravega:9090',                --Pravega controller URI
    'schema-registry-uri' = 'http://schemaregistry:9092',   --Schema-Registry service URI
    'default-database' = 'examples',                        --Flink catalog database, mapped to Pravega scope
    'serialization.format' = 'Avro'                         --data serialization format
);
```

### Show Tables
The Pravega catalog will read schema info from schema registry service and then convert it to Flink table schema to create the flink table.
We can check the tables in Pravega catalog: 
```sql
USE CATALOG pravega;
SHOW TABLES;
DESCRIBE userBehavior;
SELECT * FROM userBehavior;
```
![image4](images/image4.png)
![image2](images/image2.gif)

### Running queries

We can run a query of cumulative unique visitors from 00:00 to every minute, where the number of UV at 10:00 represents the total number of UV from 00:00 to 10:00.
This can be easily and efficiently implemented by `CUMULATE` windowing. The `CUMULATE` functions assigns windows based on a time attribute column and returns
a new relation that includes all columns of original relation as well as additional 3 columns named “window_start”, “window_end”, “window_time” to indicate the assigned window.

The `CUMULATE` function takes 4 required parameters: `CUMULATE(TABLE data, DESCRIPTOR(timecol), step, size)` where `data` is a table with
an time attribute column, `timecol` is the time attribute column should be mapped to tumbling windows, `step` is a duration specifying the increased window size
and `size` is a duration specifying the max width of the cumulating windows. You can check more about `CUMULATE` and other Windowing TVFs [here](https://ci.apache.org/projects/flink/flink-docs-master/docs/dev/table/sql/queries/window-tvf/).

To use `CUMULATE` function, we need to create a new table in other catalog since Pravega catalog do not support
watermark which is needed in `CUMULATE` function. 
```sql
CREATE TABLE default_catalog.default_database.user_behavior (
  WATERMARK FOR ts as ts - INTERVAL '5' SECOND
) LIKE userBehavior;

INSERT INTO default_catalog.default_database.user_behavior
SELECT * FROM userBehavior;
```

We can have a cumulating window for 10 min step and 1 day max size and get the windows [00:00, 00:10), [00:10, 00:20),
..., [23:50, 24:00):
```sql
SELECT window_start, window_end, COUNT(DISTINCT user_id) as UV
FROM Table(
    CUMULATE(Table default_catalog.default_database.user_behavior, DESCRIPTOR(ts), INTERVAL '10' MINUTES, INTERVAL '1' DAY))
GROUP BY window_start,window_end;
```
![image3](images/image3.gif)

### Write data to Pravega

With the help of catalog, we can also easily write data back to Pravega.

First let's create a table to store all buy behavior transaction between 0:00 to 03:00 everyday.

The Pravega Catalog will first create a stream with table name in Pravega. 
Then it will convert flink table schema to schema info which can be read by Schema Registry so that this schema can be registered in
schema registry service, which will be used to write data into the Pravega stream later.
```sql
CREATE TABLE buyBehavior (
    user_id STRING,
    item_id STRING,
    category_id STRING,
    ts TIMESTAMP(3)
);
```
Then we write query results into table:
```sql
INSERT INTO buyBehavior
SELECT user_id, item_id, category_id, ts
FROM userBehavior
WHERE behavior = 'buy' AND HOUR(ts) <= 2;
```
![image1](images/image1.png)
You can read this stream in Pravega:
```
docker-compose exec pravega bin/pravega-cli stream read examples/userBehavior
```

You can also check by querying in the sql client:
```
SELECT * FROM buyBehavior
```
![image6](images/image6.png)


## Conclusion
The demo provides some clear and practical examples of the convenience and power of catalogs, featuring an easy connection to Pravega.
It enables us to reference existing metadata in Pravega and automatically maps them to Flink's corresponding metadata easily so that
we don't have to re-write DDLs in Flink when accessing data. 
The catalog greatly simplifies steps required to get started with Flink with Pravega, and greatly enhanced user experiences.