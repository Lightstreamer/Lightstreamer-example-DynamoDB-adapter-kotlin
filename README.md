# Lightstreamer - DynamoDB Demo - Kotlin Adapter

This project includes the resources needed to develop the Data and Metadata Adapters for the Lightstreamer DynamoDB Demo pluggable into Lightstreamer Server 

![Infrastructure](infrastructure.png)<br>

The Demo simulates a basic departure flight monitor with a few rows which represent information on flights departing from a hypothetical airport.
The data are simulated in the back-end and retrieved from an [Amazon DynamoDB](https://aws.amazon.com/en/dynamodb/) data source.

As an example of a client using this adapter, you may refer to the [Lightstreamer - DynamoDB Demo - Web Client](https://github.com/Lightstreamer/Lightstreamer-example-DynamoDB-client-javascript) and view the corresponding [Live Demo]().

## Details

The source code of the projects is basically divided into two packages: 

- demo, that implements the operations with DynamoDB both as regards reading the updates to be injected into the Lightstreamer server but also the simulator of flights information. In particular the following classes are defined:
    - `DemoPublisher.kt`, 
    - `DynamoData.kt`, 
    - `Util.kt`, 
- server, that implements the ightstreamer in-process adapters based on the [Java In-Process Adapter API ](https://sdk.lightstreamer.com/ls-adapter-inprocess/7.3.1/api/index.html). in particular:
    - `DemoDepartureProvider.kt` implements the Data Adapter publishing the simulated flights information;
    - `DemoDataProvider.kt` implements the Data Adapter publishing the current time of the simulation;
    - `DemoMetadataProvider.kt` implements a very basic Metadata Adapter for the demo.

## Build and Install

To build and install your own version of these adapters you have two options:
either use [Gradle](https://gradle.org/install/) (or other build tools) to take care of dependencies and building (recommended) or gather the necessary jars yourself and build it manually.
For the sake of simplicity only the Maven case is detailed here.

### Gradle

You can easily build and run this application using Gradle through the `build.gradle` file located in the root folder of this project. As an alternative, you can use an alternative build tool (e.g. Gradle, Ivy, etc.).

Assuming Gradle is installed and available in your path you can build the demo by running
```sh 
 $gradle dist 
```

If the task complete successful it also created a `build/dist` folder, ready to be deployed under the `LS_HOME/adapters`.

## See Also

### Clients Using This Adapter
<!-- START RELATED_ENTRIES -->

* [Lightstreamer - DynamoDB Demo - Web Client](https://github.com/Lightstreamer/Lightstreamer-example-DynamoDB-client-javascript)

<!-- END RELATED_ENTRIES -->

### Related Projects

* [LiteralBasedProvider Metadata Adapter](https://github.com/Lightstreamer/Lightstreamer-lib-adapter-java-inprocess#literalbasedprovider-metadata-adapter)

## Lightstreamer Compatibility Notes

- Compatible with Lightstreamer SDK for Java In-Process Adapters since 7.3.