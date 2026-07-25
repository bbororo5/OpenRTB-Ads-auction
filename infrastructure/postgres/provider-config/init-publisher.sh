#!/bin/sh
set -eu

role_exists="$(psql --host postgres-seoul --username postgres --dbname rtb --tuples-only --no-align \
  --command "SELECT 1 FROM pg_roles WHERE rolname = 'config_replicator'")"

if [ "$role_exists" != "1" ]; then
  psql --host postgres-seoul --username postgres --dbname rtb \
    --set=ON_ERROR_STOP=1 \
    --set=replication_password="$CONFIG_REPLICATOR_PASSWORD" <<'SQL'
CREATE ROLE config_replicator
  WITH LOGIN REPLICATION PASSWORD :'replication_password';
SQL
fi

psql --host postgres-seoul --username postgres --dbname rtb \
  --set=ON_ERROR_STOP=1 <<'SQL'
GRANT CONNECT ON DATABASE rtb TO config_replicator;
GRANT USAGE ON SCHEMA public TO config_replicator;
GRANT SELECT ON TABLE
  provider_config_version,
  provider_policy,
  provider_key,
  provider_config_head
TO config_replicator;
SQL

publication_exists="$(psql --host postgres-seoul --username postgres --dbname rtb --tuples-only --no-align \
  --command "SELECT 1 FROM pg_publication WHERE pubname = 'provider_config_publication'")"

if [ "$publication_exists" != "1" ]; then
  psql --host postgres-seoul --username postgres --dbname rtb \
    --set=ON_ERROR_STOP=1 <<'SQL'
CREATE PUBLICATION provider_config_publication
  FOR TABLE
    provider_config_version,
    provider_policy,
    provider_key,
    provider_config_head;
SQL
fi
