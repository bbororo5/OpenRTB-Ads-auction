#!/bin/sh
set -eu

source_connection="host=postgres-seoul port=5432 dbname=rtb user=config_replicator password=${CONFIG_REPLICATOR_PASSWORD} application_name=provider_config_subscription"
subscription_exists="$(psql --host postgres-tokyo --username postgres --dbname rtb --tuples-only --no-align \
  --command "SELECT 1 FROM pg_subscription WHERE subname = 'provider_config_subscription'")"

if [ "$subscription_exists" = "1" ]; then
  echo "provider configuration subscription already exists"
  exit 0
fi

psql --host postgres-tokyo --username postgres --dbname rtb \
  --set=ON_ERROR_STOP=1 \
  --set=source_connection="$source_connection" <<'SQL'
CREATE SUBSCRIPTION provider_config_subscription
  CONNECTION :'source_connection'
  PUBLICATION provider_config_publication
  WITH (
    copy_data = true,
    create_slot = true,
    enabled = true
  );
SQL
