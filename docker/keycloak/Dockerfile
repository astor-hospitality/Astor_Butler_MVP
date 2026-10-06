ARG KEYCLOAK_BASE=quay.io/keycloak/keycloak:26.8.0
FROM ${KEYCLOAK_BASE}
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=false
ENV KC_HTTP_RELATIVE_PATH=/astor-auth KC_HTTP_MANAGEMENT_RELATIVE_PATH=/
RUN /opt/keycloak/bin/kc.sh build
COPY --chown=1000:0 --chmod=0555 entrypoint.sh /opt/astor/entrypoint.sh
ENTRYPOINT ["/opt/astor/entrypoint.sh"]
CMD ["start", "--optimized", "--import-realm"]
