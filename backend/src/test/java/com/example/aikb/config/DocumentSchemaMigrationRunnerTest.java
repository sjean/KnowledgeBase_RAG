package com.example.aikb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSchemaMigrationRunnerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void runShouldDoNothingWhenDocumentsTableDoesNotExist() throws Exception {
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'DOCUMENTS'",
                Integer.class
        )).thenReturn(0);

        DocumentSchemaMigrationRunner runner = new DocumentSchemaMigrationRunner(jdbcTemplate);
        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS status VARCHAR(32)");
    }

    @Test
    void runShouldAddMissingColumnsAndBackfillLegacyStatus() throws Exception {
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'DOCUMENTS'",
                Integer.class
        )).thenReturn(1);

        DocumentSchemaMigrationRunner runner = new DocumentSchemaMigrationRunner(jdbcTemplate);
        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS status VARCHAR(32)");
        verify(jdbcTemplate).execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS error_message VARCHAR(2048)");
        verify(jdbcTemplate).execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS storage_path VARCHAR(1024)");
        verify(jdbcTemplate).execute("ALTER TABLE documents ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP");
        verify(jdbcTemplate).update(
                "UPDATE documents SET status = ? WHERE status IS NULL AND COALESCE(error_message, '') <> ''",
                "FAILED"
        );
        verify(jdbcTemplate).update(
                "UPDATE documents SET status = ? WHERE status IS NULL AND COALESCE(chunk_count, 0) > 0",
                "READY"
        );
        verify(jdbcTemplate).update(
                "UPDATE documents SET status = ? WHERE status IS NULL",
                "UPLOADED"
        );
        verify(jdbcTemplate).update("UPDATE documents SET updated_at = created_at WHERE updated_at IS NULL");
    }
}
