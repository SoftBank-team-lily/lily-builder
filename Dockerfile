FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
# lily-jev 는 다른 레포다. 여기서 커밋을 받아 includeBuild('lily-jev') 가 되게 한다.
ARG JEV_REF=4dc2851739f61fbbf2d515b229d2f3dcf8378093
ADD https://github.com/SoftBank-team-lily/lily-jev/archive/${JEV_REF}.tar.gz /tmp/lily-jev.tar.gz
RUN mkdir -p /tmp/jev-src \
    && tar -xzf /tmp/lily-jev.tar.gz -C /tmp/jev-src --strip-components=1 \
    && mv /tmp/jev-src /workspace/lily-jev \
    && rm /tmp/lily-jev.tar.gz
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
