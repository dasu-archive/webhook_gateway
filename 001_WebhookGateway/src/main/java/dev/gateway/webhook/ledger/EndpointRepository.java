package dev.gateway.webhook.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class EndpointRepository {

    private final JdbcClient jdbc;

    public EndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, slug, provider, secret_current, secret_previous, secret_rotated_at,
            target_url, signature_mode, max_attempts, retention_days, enabled, idempotency_confirmed, created_at
            """;

    public Optional<Endpoint> findBySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM endpoint WHERE slug = :slug")
                .param("slug", slug)
                .query(EndpointRepository::map)
                .optional();
    }

    public Optional<Endpoint> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM endpoint WHERE id = :id")
                .param("id", id)
                .query(EndpointRepository::map)
                .optional();
    }

    public List<Endpoint> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM endpoint ORDER BY id")
                .query(EndpointRepository::map)
                .list();
    }

    public long insert(String slug, String provider, byte[] secret, String targetUrl,
                       SignatureMode mode, int maxAttempts, int retentionDays) {
        jdbc.sql("""
                        INSERT INTO endpoint
                            (slug, provider, secret_current, target_url, signature_mode,
                             max_attempts, retention_days, enabled, created_at)
                        VALUES (:slug, :provider, :secret, :targetUrl, :mode,
                                :maxAttempts, :retentionDays, TRUE, NOW(3))
                        """)
                .param("slug", slug)
                .param("provider", provider)
                .param("secret", secret)
                .param("targetUrl", targetUrl)
                .param("mode", mode.name())
                .param("maxAttempts", maxAttempts)
                .param("retentionDays", retentionDays)
                .update();
        return jdbc.sql("SELECT id FROM endpoint WHERE slug = :slug")
                .param("slug", slug)
                .query(Long.class)
                .single();
    }

    /** 구 시크릿을 secret_previous 로 밀어내고 새 시크릿을 심는다. 설계 6.2. */
    public void rotateSecret(long id, byte[] newSecret) {
        jdbc.sql("""
                        UPDATE endpoint
                           SET secret_previous = secret_current,
                               secret_current  = :secret,
                               secret_rotated_at = NOW(3)
                         WHERE id = :id
                        """)
                .param("secret", newSecret)
                .param("id", id)
                .update();
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.sql("UPDATE endpoint SET enabled = :enabled WHERE id = :id")
                .param("enabled", enabled)
                .param("id", id)
                .update();
    }

    public void setIdempotencyConfirmed(long id, boolean confirmed) {
        jdbc.sql("UPDATE endpoint SET idempotency_confirmed = :confirmed WHERE id = :id")
                .param("confirmed", confirmed)
                .param("id", id)
                .update();
    }

    private static Endpoint map(ResultSet rs, int rowNum) throws SQLException {
        return new Endpoint(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("provider"),
                rs.getBytes("secret_current"),
                rs.getBytes("secret_previous"),
                instant(rs, "secret_rotated_at"),
                rs.getString("target_url"),
                SignatureMode.valueOf(rs.getString("signature_mode")),
                rs.getInt("max_attempts"),
                rs.getInt("retention_days"),
                rs.getBoolean("enabled"),
                rs.getBoolean("idempotency_confirmed"),
                instant(rs, "created_at")
        );
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
