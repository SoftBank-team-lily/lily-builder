FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# 에이전트 DB 터널 인증서 서명 (TunnelCertificates)
RUN apk add --no-cache openssh-keygen && addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app
EXPOSE 8070
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
