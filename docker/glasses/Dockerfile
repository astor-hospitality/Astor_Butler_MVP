FROM eclipse-temurin:25-jre
COPY requirements.txt /tmp/glasses-requirements.txt
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv curl \
    && python3 -m venv /opt/stt \
    && /opt/stt/bin/pip install --no-cache-dir -r /tmp/glasses-requirements.txt \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY app.jar /app/app.jar
COPY glasses_stt.py /app/glasses_stt.py
ENV ASTOR_GLASSES_PYTHON=/opt/stt/bin/python \
    GLASSES_HTTP_ADDRESS=0.0.0.0 GLASSES_HTTP_PORT=8091 \
    ASTOR_GLASSES_STT_SCRIPT=/app/glasses_stt.py \
    ASTOR_GLASSES_STT_MODEL_DIR=/models \
    ASTOR_GLASSES_WORK_DIR=/tmp/astor-glasses
USER 10001:10001
EXPOSE 8091
ENTRYPOINT ["java","-Xmx384m","-Dloader.main=museon_online.astor_glasses_pilot.GlassesPilotApplication","-cp","/app/app.jar","org.springframework.boot.loader.launch.PropertiesLauncher"]
