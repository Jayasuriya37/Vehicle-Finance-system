package com.vehiclefinance.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class StateSnapshotStore {
    private static final String SNAPSHOT_KEY = "vehicle-finance-demo";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StateSnapshotStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, Object>> load() {
        List<String> payloads = jdbc.query("SELECT payload FROM vfs_state_snapshot WHERE snapshot_key = ?",
                (rs, row) -> rs.getString(1), SNAPSHOT_KEY);
        if (payloads.isEmpty()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(payloads.get(0), new TypeReference<Map<String, Object>>() { }));
        } catch (Exception exception) {
            throw new IllegalStateException("Persisted vehicle-finance state cannot be decoded; refusing to overwrite it.", exception);
        }
    }

    @Transactional
    public void save(Map<String, Object> snapshot) {
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            int updated = jdbc.update("UPDATE vfs_state_snapshot SET payload = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE snapshot_key = ?", payload, SNAPSHOT_KEY);
            if (updated == 0) {
                try {
                    jdbc.update("INSERT INTO vfs_state_snapshot(snapshot_key, payload, version, updated_at) VALUES (?, ?, 1, CURRENT_TIMESTAMP)", SNAPSHOT_KEY, payload);
                } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
                    jdbc.update("UPDATE vfs_state_snapshot SET payload = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE snapshot_key = ?", payload, SNAPSHOT_KEY);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Vehicle-finance state could not be serialized for persistence.", exception);
        }
    }
}
