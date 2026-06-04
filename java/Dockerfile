FROM docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY shared/proto shared/proto
COPY java/pom.xml java/pom.xml
COPY java/src java/src

RUN mvn -f java/pom.xml -DskipTests package

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /workspace/java/target/online-testing-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080 50080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
