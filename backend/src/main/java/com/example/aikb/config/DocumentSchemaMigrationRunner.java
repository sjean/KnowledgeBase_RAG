package com.example.aikb.config;

import com.example.aikb.entity.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DocumentSchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentSchemaMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public DocumentSchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!documentsTableExists()) {
            return;
        }

        log.info("Ensuring legacy documents schema is compatible with async upload fields");
        jdbcTemplate.execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS status VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS error_message VARCHAR(2048)");
        jdbcTemplate.execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS storage_path VARCHAR(1024)");
        jdbcTemplate.execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP");

        jdbcTemplate.update(
                "UPDATE documents SET status = ? WHERE status IS NULL AND COALESCE(error_message, '') <> ''",
                DocumentStatus.FAILED.name()
        );
        jdbcTemplate.update(
                "UPDATE documents SET status = ? WHERE status IS NULL AND COALESCE(chunk_count, 0) > 0",
                DocumentStatus.READY.name()
        );
        jdbcTemplate.update(
                "UPDATE documents SET status = ? WHERE status IS NULL",
                DocumentStatus.UPLOADED.name()
        );
        jdbcTemplate.update("UPDATE documents SET updated_at = created_at WHERE updated_at IS NULL");
    }

    private boolean documentsTableExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'DOCUMENTS'",
                Integer.class
        );
        return count != null && count > 0;
    }
}
