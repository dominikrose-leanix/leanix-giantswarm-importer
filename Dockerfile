FROM java:8

# Install maven
RUN apt-get update -q && apt-get install -y maven

WORKDIR /code

# Prepare by downloading dependencies
ADD pom.xml /code/pom.xml
RUN mvn dependency:resolve && mvn verify

# Adding source, compile and package into a fat jar
ADD src /src/main/java/de/leanix/giantswarm_importer
RUN mvn package

ENTRYPOINT ["/usr/lib/jvm/java-8-openjdk-amd64/bin/java", "-jar", "target/giantswarm-importer.jar"]