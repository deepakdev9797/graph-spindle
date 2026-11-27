# GraphSpindle container image (UNTESTED: Docker was not available on the development machine).
# Build: docker build -t graphspindle .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && cp target/graph-spindle-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER app
# inside a container the server must listen on all interfaces; startup then REQUIRES
# GRAPHSPINDLE_ADMIN_API_KEY (the app refuses to start without it)
ENV GRAPHSPINDLE_BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
