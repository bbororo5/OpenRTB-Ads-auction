INSERT INTO provider_config_version (version, checksum, published_at)
VALUES (1, 'stage8c-aws-v1', transaction_timestamp());

INSERT INTO provider_policy (version, provider_id, active)
VALUES (1, 'provider-stage8c', true);

INSERT INTO provider_key (version, provider_id, key_id, active)
VALUES (1, 'provider-stage8c', 'key-stage8c', true);

INSERT INTO provider_config_head (scope, active_version)
VALUES ('global', 1);
