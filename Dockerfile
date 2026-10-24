# Build any module:  docker build --build-arg MODULE=patient-service -t hms/patient-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN mvn -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
RUN useradd --uid 10001 --no-create-home hms
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
