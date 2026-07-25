package com.bbororo.rtb.ssp.trust;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 주입받은 지역 PostgreSQL 연결에서만 현재 설정 버전을 읽는다.
 *
 * <p>리전 이름이나 원격 저장소 주소를 알지 않는다. 한 SQL 문으로 head가 가리키는 버전과 그 정책·키를
 * 함께 읽어, 서로 다른 버전의 행을 섞지 않는다.</p>
 */
public final class PostgreSqlProviderConfigReader implements ProviderConfigReader {

    private static final String LOAD_ACTIVE_VERSION = """
            SELECT active_version
            FROM provider_config_head
            WHERE scope = 'global'
            """;

    private static final String LOAD_ACTIVE_SNAPSHOT = """
            SELECT
                head.active_version,
                policy.provider_id,
                policy.active AS provider_active,
                key_config.key_id,
                key_config.active AS key_active
            FROM provider_config_head AS head
            JOIN provider_config_version AS version_config
              ON version_config.version = head.active_version
            LEFT JOIN provider_policy AS policy
              ON policy.version = head.active_version
            LEFT JOIN provider_key AS key_config
              ON key_config.version = policy.version
             AND key_config.provider_id = policy.provider_id
            WHERE head.scope = 'global'
            ORDER BY policy.provider_id, key_config.key_id
            """;

    private final DataSource dataSource;

    public PostgreSqlProviderConfigReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public long loadActiveVersion() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOAD_ACTIVE_VERSION);
             ResultSet result = statement.executeQuery()) {

            if (!result.next()) {
                throw new IllegalStateException("활성 공급자 설정 버전이 없습니다.");
            }
            return result.getLong("active_version");
        } catch (SQLException exception) {
            throw new IllegalStateException("지역 공급자 설정 버전을 읽지 못했습니다.", exception);
        }
    }

    @Override
    public ProviderTrustSnapshot loadActiveSnapshot() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOAD_ACTIVE_SNAPSHOT);
             ResultSet result = statement.executeQuery()) {

            if (!result.next()) {
                throw new IllegalStateException("활성 공급자 설정 버전이 없습니다.");
            }

            long version = result.getLong("active_version");
            Map<String, MutablePolicy> policies = new HashMap<>();
            appendRow(result, policies);
            while (result.next()) {
                appendRow(result, policies);
            }

            Map<String, ImmutableProviderTrustSnapshot.ProviderPolicy> immutablePolicies = new HashMap<>();
            policies.forEach((providerId, policy) -> immutablePolicies.put(
                    providerId,
                    new ImmutableProviderTrustSnapshot.ProviderPolicy(policy.active, policy.activeKeyIds)
            ));
            return new ImmutableProviderTrustSnapshot(version, immutablePolicies);
        } catch (SQLException exception) {
            throw new IllegalStateException("지역 공급자 설정을 읽지 못했습니다.", exception);
        }
    }

    private static void appendRow(ResultSet result, Map<String, MutablePolicy> policies) throws SQLException {
        String providerId = result.getString("provider_id");
        if (providerId == null) {
            return;
        }

        boolean providerActive = result.getBoolean("provider_active");
        MutablePolicy policy = policies.get(providerId);
        if (policy == null) {
            policy = new MutablePolicy(providerActive);
            policies.put(providerId, policy);
        }
        String keyId = result.getString("key_id");
        if (keyId != null && result.getBoolean("key_active")) {
            policy.activeKeyIds.add(keyId);
        }
    }

    private static final class MutablePolicy {

        private final boolean active;
        private final Set<String> activeKeyIds = new HashSet<>();

        private MutablePolicy(boolean active) {
            this.active = active;
        }
    }
}
