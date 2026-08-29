#!/usr/bin/env bash
set -euo pipefail

observability_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "${observability_dir}/.." && pwd)"

docker compose -f "${observability_dir}/compose.yml" config --quiet

docker run --rm \
  -v "${observability_dir}/collector/agent.yaml:/etc/otelcol-contrib/agent.yaml:ro" \
  -v "/:/hostfs:ro" \
  -e HOST_ROLE=validation \
  -e DEPLOYMENT_ENVIRONMENT=validation \
  -e TEMPO_OTLP_ENDPOINT=tempo:4317 \
  -e LOKI_OTLP_ENDPOINT=http://loki:3100/otlp \
  otel/opentelemetry-collector-contrib:0.159.0 \
  validate --config=/etc/otelcol-contrib/agent.yaml

for prometheus_config in local.yaml aws-stage8c.yaml; do
  docker run --rm \
    -v "${observability_dir}/prometheus/${prometheus_config}:/etc/prometheus/prometheus.yml:ro" \
    --entrypoint promtool \
    prom/prometheus:v3.14.0 \
    check config /etc/prometheus/prometheus.yml
done

docker run --rm \
  -v "${observability_dir}/tempo/tempo.yaml:/etc/tempo/tempo.yaml:ro" \
  grafana/tempo:3.0.3 \
  -config.file=/etc/tempo/tempo.yaml \
  -config.verify=true

docker run --rm \
  -v "${observability_dir}/loki/loki.yaml:/etc/loki/loki.yaml:ro" \
  grafana/loki:3.7.7 \
  -config.file=/etc/loki/loki.yaml \
  -verify-config=true

docker build -q -t rtb-observability-config:verify "${observability_dir}" >/dev/null

if [[ "${1:-}" != "--running" ]]; then
  printf 'Observability configuration validation passed for %s\n' "${repository_dir}"
  exit 0
fi

curl --fail --silent --show-error http://127.0.0.1:13133/ >/dev/null
curl --fail --silent --show-error http://127.0.0.1:9090/-/ready >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3200/ready >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3100/ready >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3000/api/health >/dev/null

docker compose -f "${observability_dir}/compose.yml" --profile verify run --rm telemetrygen \
  >/dev/null 2>&1
docker compose -f "${observability_dir}/compose.yml" --profile verify run --rm telemetrygen-logs \
  >/dev/null 2>&1
sleep 2

curl --fail --silent --show-error http://127.0.0.1:9090/api/v1/targets \
  | grep '"health":"up"' >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3200/metrics \
  | grep '^tempo_distributor_spans_received_total.* [1-9][0-9]*$' >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3100/metrics \
  | grep '^loki_distributor_lines_received_total.* [1-9][0-9]*$' >/dev/null

printf 'Running metrics, traces, and logs pipeline verification passed.\n'
