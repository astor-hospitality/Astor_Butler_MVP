# ========= BUILD =========
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Кэшируем зависимости
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

# Собираем jar
COPY src src
RUN mvn -B -DskipTests package

# ========= RUN =========
FROM eclipse-temurin:25-jre
WORKDIR /app

# STT runs on Cloud.ru whisper-large-v3 (ASTOR_STT_PROVIDER=cloudru, the default): only curl for the
# healthcheck. The local faster-whisper stack (~1.5 GB) is built in only for the rollback image:
#   docker build --build-arg STT_LOCAL_WHISPER=true -t astor-butler:local-stt .
# and then ASTOR_STT_PROVIDER=local at runtime.
ARG STT_LOCAL_WHISPER=false
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && if [ "$STT_LOCAL_WHISPER" = "true" ]; then \
         apt-get install -y --no-install-recommends ffmpeg python3 python3-pip \
         && pip3 install --break-system-packages --no-cache-dir faster-whisper==1.1.1 requests; \
       fi \
    && rm -rf /var/lib/apt/lists/*

# Опциональные JVM-флаги (безопасно для контейнера)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC" \
    TZ=UTC

# Имя артефакта проекта сейчас astor-butler-<version>.jar.
COPY --from=build /workspace/target/*.jar /app/app.jar
# Tiny; used only with STT_LOCAL_WHISPER=true + ASTOR_STT_PROVIDER=local.
COPY scripts/stt_faster_whisper.py /app/stt_faster_whisper.py

EXPOSE 8088
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
