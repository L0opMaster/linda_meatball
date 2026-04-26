# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

RUN chmod +x ./mvnw
RUN ./mvnw clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jdk-jammy
ARG PROFILE=dev
ARG APP_VERSION=1.0.0

WORKDIR /app
COPY --from=build /app/target/*.jar /app/

EXPOSE 8081

ENV DB_URL=jdbc:mysql://host.docker.internal:3306/kaknnea?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC

ENV ACTIVE_PROFILE=${PROFILE}
ENV JAR_VERSION=${APP_VERSION}

CMD java -jar -Dspring.profiles.active=${ACTIVE_PROFILE} backend-${JAR_VERSION}.jar