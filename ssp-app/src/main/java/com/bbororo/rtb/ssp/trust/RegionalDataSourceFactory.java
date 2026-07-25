package com.bbororo.rtb.ssp.trust;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;

/**
 * 배포 환경이 주입한 현재 지역 DB 연결만 만든다.
 *
 * <p>서울·도쿄 같은 지역 이름이나 DB 선택 규칙을 가지지 않는다. 같은 애플리케이션 바이너리에 다른
 * 연결 값을 주입하는 방식으로 새 지역을 추가한다.</p>
 */
public final class RegionalDataSourceFactory {

    private RegionalDataSourceFactory() {
    }

    public static HikariDataSource createFromEnvironment() {
        return create(new DatabaseConnectionSettings(
                requiredEnvironment("DATABASE_URL", System.getenv()),
                requiredEnvironment("DATABASE_USERNAME", System.getenv()),
                requiredEnvironment("DATABASE_PASSWORD", System.getenv())
        ));
    }

    public static HikariDataSource create(DatabaseConnectionSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(2);
        config.setPoolName("provider-config-reader");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        return new HikariDataSource(config);
    }

    private static String requiredEnvironment(String name, Map<String, String> environment) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("환경 변수 %s가 필요합니다.".formatted(name));
        }
        return value;
    }

    public record DatabaseConnectionSettings(String jdbcUrl, String username, String password) {

        public DatabaseConnectionSettings {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl은 비어 있을 수 없습니다.");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username은 비어 있을 수 없습니다.");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("password는 비어 있을 수 없습니다.");
            }
        }
    }
}
