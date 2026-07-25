#!/bin/sh
set -eu

seoul_psql() {
  psql --host postgres-seoul --username postgres --dbname rtb --set=ON_ERROR_STOP=1 "$@"
}

tokyo_psql() {
  psql --host postgres-tokyo --username postgres --dbname rtb --tuples-only --no-align "$@"
}

wait_for_tokyo() {
  expected="$1"
  attempts=0

  while [ "$attempts" -lt 20 ]; do
    actual="$(tokyo_psql --command "SELECT active_version FROM provider_config_head WHERE scope = 'global'")"
    if [ "$actual" = "$expected" ]; then
      return 0
    fi

    attempts=$((attempts + 1))
    sleep 1
  done

  echo "Tokyo did not receive provider configuration version ${expected}" >&2
  exit 1
}

seoul_psql <<'SQL'
BEGIN;
INSERT INTO provider_config_version (version, checksum, published_at)
VALUES (1, 'version-1', clock_timestamp());
INSERT INTO provider_policy (version, provider_id, active)
VALUES (1, 'provider-a', true);
INSERT INTO provider_key (version, provider_id, key_id, active)
VALUES (1, 'provider-a', 'key-a', true);
INSERT INTO provider_config_head (scope, active_version)
VALUES ('global', 1);
COMMIT;
SQL

wait_for_tokyo 1

tokyo_active="$(tokyo_psql --command "
  SELECT active
  FROM provider_policy
  WHERE version = 1 AND provider_id = 'provider-a'")"
tokyo_key_active="$(tokyo_psql --command "
  SELECT active
  FROM provider_key
  WHERE version = 1 AND provider_id = 'provider-a' AND key_id = 'key-a'")"

test "$tokyo_active" = "t"
test "$tokyo_key_active" = "t"

seoul_psql <<'SQL'
BEGIN;
INSERT INTO provider_config_version (version, checksum, published_at)
VALUES (2, 'version-2', clock_timestamp());
INSERT INTO provider_policy (version, provider_id, active)
VALUES (2, 'provider-a', false);
INSERT INTO provider_key (version, provider_id, key_id, active)
VALUES (2, 'provider-a', 'key-a', false);
UPDATE provider_config_head
SET active_version = 2
WHERE scope = 'global';
COMMIT;
SQL

wait_for_tokyo 2

tokyo_disabled="$(tokyo_psql --command "
  SELECT active
  FROM provider_policy
  WHERE version = 2 AND provider_id = 'provider-a'")"
test "$tokyo_disabled" = "f"

echo "provider configuration logical replication verified"
