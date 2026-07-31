#!/bin/sh
set -eu

for service in ssp-seoul ssp-tokyo; do
  base_url="http://${service}:8080"

  curl --fail --silent --show-error --output /dev/null "${base_url}/health/live"
  curl --fail --silent --show-error --output /dev/null "${base_url}/health/ready"

  metrics="$(curl --fail --silent --show-error "${base_url}/metrics")"
  printf '%s' "$metrics" | grep -q 'ssp_provider_http_requests_in_flight'
done
