#!/bin/sh
set -eu

psql --host postgres-seoul --username postgres --dbname rtb \
  --set=ON_ERROR_STOP=1 <<'SQL'
INSERT INTO provider_config_version (version, checksum, published_at)
VALUES (1, 'local-development-provider-config-v1', clock_timestamp())
ON CONFLICT (version) DO NOTHING;

INSERT INTO provider_policy (version, provider_id, active)
VALUES (1, 'provider-local', true)
ON CONFLICT (version, provider_id) DO NOTHING;

INSERT INTO provider_key (version, provider_id, key_id, active)
VALUES (1, 'provider-local', 'key-local', true)
ON CONFLICT (version, provider_id, key_id) DO NOTHING;

INSERT INTO provider_config_head (scope, active_version)
VALUES ('global', 1)
ON CONFLICT (scope) DO NOTHING;
SQL
