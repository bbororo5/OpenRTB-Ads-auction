FROM alpine:3.22

WORKDIR /opt/observability

COPY collector ./collector
COPY prometheus ./prometheus
COPY tempo ./tempo
COPY grafana ./grafana

CMD ["true"]
