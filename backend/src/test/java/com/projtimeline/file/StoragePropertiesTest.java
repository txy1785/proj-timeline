package com.projtimeline.file;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePropertiesTest {
    @Test
    void fallsBackToSafeDefaults() {
        StorageProperties properties = new StorageProperties("  ", 0, List.of());

        assertThat(properties.root()).isEqualTo("./data/files");
        assertThat(properties.maxFileBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(properties.allowedExtensions()).contains("pdf", "docx", "png", "zip");
    }

    @Test
    void whitelistIsCaseInsensitiveAndRejectsUnknown() {
        StorageProperties properties = new StorageProperties("/tmp/files", 1024, List.of("PDF", "Docx"));

        assertThat(properties.allows("pdf")).isTrue();
        assertThat(properties.allows("DOCX")).isTrue();
        assertThat(properties.allows("exe")).isFalse();
        assertThat(properties.allows(null)).isFalse();
    }
}
