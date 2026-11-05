package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.DocumentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantinedDocumentStorageTest {
    @TempDir Path tempDir;

    @Test
    void acceptsPdfBySignatureAndStoresOnlyInQuarantine() throws Exception {
        DocumentStorageProperties properties = localProperties();
        QuarantinedDocumentStorage storage = new QuarantinedDocumentStorage(properties);
        byte[] pdf = "%PDF-1.7\nsynthetic test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("file", "../../private.pdf", "application/pdf", pdf);

        QuarantinedDocumentStorage.StoredDocument stored = storage.store(file);
        assertTrue(stored.objectRef().startsWith("quarantine/"));
        assertEquals("private.pdf", stored.originalName());
        assertEquals("application/pdf", stored.contentType());
        assertEquals("PENDING", stored.scanStatus());
        Path storedPath = tempDir.resolve(stored.objectRef()).normalize();
        assertTrue(storedPath.startsWith(tempDir));
        assertTrue(Files.exists(storedPath));
        assertFalse(Files.exists(tempDir.resolve("private.pdf")));
    }

    @Test
    void rejectsSpoofedMimeAndOversizedFiles() {
        QuarantinedDocumentStorage storage = new QuarantinedDocumentStorage(localProperties());
        MockMultipartFile spoof = new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a pdf".getBytes());
        assertThrows(IllegalArgumentException.class, () -> storage.store(spoof));

        DocumentStorageProperties limited = localProperties();
        limited.setMaxBytes(4);
        QuarantinedDocumentStorage limitedStorage = new QuarantinedDocumentStorage(limited);
        MockMultipartFile large = new MockMultipartFile("file", "valid.pdf", "application/pdf", "%PDF-1.7".getBytes());
        assertThrows(IllegalArgumentException.class, () -> limitedStorage.store(large));
    }

    private DocumentStorageProperties localProperties() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setStorage("local");
        properties.setLocalDirectory(tempDir.toString());
        properties.setMaxBytes(5 * 1024 * 1024);
        return properties;
    }
}
