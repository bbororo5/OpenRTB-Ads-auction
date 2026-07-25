CREATE TABLE provider_config_version (
    version BIGINT PRIMARY KEY,
    checksum TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE provider_policy (
    version BIGINT NOT NULL,
    provider_id TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (version, provider_id),
    CONSTRAINT provider_policy_version_fk
        FOREIGN KEY (version)
        REFERENCES provider_config_version (version)
        ON DELETE RESTRICT
);

CREATE TABLE provider_key (
    version BIGINT NOT NULL,
    provider_id TEXT NOT NULL,
    key_id TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (version, provider_id, key_id),
    CONSTRAINT provider_key_policy_fk
        FOREIGN KEY (version, provider_id)
        REFERENCES provider_policy (version, provider_id)
        ON DELETE RESTRICT
);

CREATE TABLE provider_config_head (
    scope TEXT PRIMARY KEY CHECK (scope = 'global'),
    active_version BIGINT NOT NULL,
    CONSTRAINT provider_config_head_version_fk
        FOREIGN KEY (active_version)
        REFERENCES provider_config_version (version)
        ON DELETE RESTRICT
);
