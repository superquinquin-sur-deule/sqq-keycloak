FROM quay.io/keycloak/keycloak:26.0 AS builder

COPY target/sqq-keycloak-extensions-*.jar /opt/keycloak/providers/

ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.0

COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
