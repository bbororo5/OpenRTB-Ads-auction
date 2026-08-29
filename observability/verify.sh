#!/usr/bin/env bash
set -euo pipefail

observability_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "${observability_dir}/.." && pwd)"
verify_running=false
verify_profiles=false

for argument in "$@"; do
  case "${argument}" in
    --running) verify_running=true ;;
    --profiles) verify_profiles=true ;;
    *) printf 'Unknown argument: %s\n' "${argument}" >&2; exit 2 ;;
  esac
done

wait_until_ready() {
  local name="$1"
  local url="$2"
  local attempts="${3:-90}"
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent --show-error "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  printf '%s did not become ready: %s\n' "${name}" "${url}" >&2
  return 1
}

metric_sum() {
  local url="$1"
  local metric="$2"
  curl --fail --silent --show-error "${url}" \
    | awk -v metric="${metric}" '$1 == metric || index($1, metric "{") == 1 { sum += $NF } END { printf "%.0f", sum }'
}

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

docker run --rm \
  -v "${observability_dir}/profiler/host.yaml:/etc/otelcol-ebpf-profiler/host.yaml:ro" \
  -e HOST_ROLE=validation \
  -e DEPLOYMENT_ENVIRONMENT=validation \
  -e PYROSCOPE_OTLP_ENDPOINT=pyroscope:4040 \
  otel/opentelemetry-collector-ebpf-profiler:0.159.0 \
  validate \
  --feature-gates=service.profilesSupport \
  --config=/etc/otelcol-ebpf-profiler/host.yaml

docker build -q -t rtb-observability-config:verify "${observability_dir}" >/dev/null

if [[ "${verify_running}" != "true" ]]; then
  printf 'Observability configuration validation passed for %s\n' "${repository_dir}"
  exit 0
fi

wait_until_ready Collector http://127.0.0.1:13133/
wait_until_ready Prometheus http://127.0.0.1:9090/-/ready
wait_until_ready Tempo http://127.0.0.1:3200/ready
wait_until_ready Loki http://127.0.0.1:3100/ready
wait_until_ready Pyroscope http://127.0.0.1:4040/ready
wait_until_ready Grafana http://127.0.0.1:3000/api/health

for datasource_uid in prometheus tempo loki pyroscope; do
  curl --fail --silent --show-error \
    "http://127.0.0.1:3000/api/datasources/uid/${datasource_uid}" \
    | grep "\"uid\":\"${datasource_uid}\"" >/dev/null
done
curl --fail --silent --show-error http://127.0.0.1:3000/api/datasources/uid/tempo \
  | grep 'tracesToProfiles' >/dev/null
curl --fail --silent --show-error http://127.0.0.1:3000/api/datasources/uid/loki \
  | grep 'derivedFields' >/dev/null

tempo_before="$(metric_sum http://127.0.0.1:3200/metrics tempo_distributor_spans_received_total)"
loki_before="$(metric_sum http://127.0.0.1:3100/metrics loki_distributor_lines_received_total)"

docker compose -f "${observability_dir}/compose.yml" --profile verify run --rm telemetrygen \
  >/dev/null 2>&1
docker compose -f "${observability_dir}/compose.yml" --profile verify run --rm telemetrygen-logs \
  >/dev/null 2>&1
sleep 2

curl --fail --silent --show-error http://127.0.0.1:9090/api/v1/targets \
  | grep '"health":"up"' >/dev/null
tempo_after="$(metric_sum http://127.0.0.1:3200/metrics tempo_distributor_spans_received_total)"
loki_after="$(metric_sum http://127.0.0.1:3100/metrics loki_distributor_lines_received_total)"
if ((tempo_after <= tempo_before)); then
  printf 'Tempo did not receive the generated trace.\n' >&2
  exit 1
fi
if ((loki_after <= loki_before)); then
  printf 'Loki did not receive the generated log.\n' >&2
  exit 1
fi

if [[ "${verify_profiles}" == "true" ]]; then
  wait_until_ready eBPF-profiler http://127.0.0.1:13134/
  profiles_before="$(metric_sum http://127.0.0.1:4040/metrics pyroscope_distributor_profiles_received_total)"
  for attempt in {1..30}; do
    sleep 1
    profiles_after="$(metric_sum http://127.0.0.1:4040/metrics pyroscope_distributor_profiles_received_total)"
    if ((profiles_after > profiles_before)); then
      printf 'Running metrics, traces, logs, and profiles pipeline verification passed.\n'
      exit 0
    fi
  done
  printf 'Pyroscope did not receive an OTLP profile.\n' >&2
  exit 1
fi

printf 'Running metrics, traces, and logs pipeline verification passed.\n'
