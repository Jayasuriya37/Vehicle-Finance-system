package com.vehiclefinance.api.service;

import com.vehiclefinance.api.config.DocumentStorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

/** Stores uploads in a private quarantine namespace. This service never serves object bytes. */
@Service
public class QuarantinedDocumentStorage {
    public record StoredDocument(String objectRef, String originalName, String contentType, long sizeBytes, String scanStatus) { }

    private final DocumentStorageProperties properties;
    private final S3Client s3Client;

    public QuarantinedDocumentStorage(DocumentStorageProperties properties) {
        this.properties = properties;
        if ("s3".equalsIgnoreCase(properties.getStorage())) {
            if (properties.getS3Bucket() == null || properties.getS3Bucket().isBlank()
                    || properties.getS3KmsKeyId() == null || properties.getS3KmsKeyId().isBlank()) {
                throw new IllegalStateException("S3 document quarantine requires VFS_DOCUMENTS_BUCKET and VFS_DOCUMENTS_KMS_KEY_ID.");
            }
            String region = System.getenv("AWS_REGION");
            if (region == null || region.isBlank()) throw new IllegalStateException("AWS_REGION must be configured for S3 document storage.");
            this.s3Client = S3Client.builder().region(Region.of(region)).build();
        } else if ("local".equalsIgnoreCase(properties.getStorage())) {
            this.s3Client = null;
        } else {
            throw new IllegalStateException("Unsupported document storage mode; use local or s3.");
        }
    }

    public StoredDocument store(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("Choose a non-empty document.");
        if (upload.getSize() > properties.getMaxBytes()) throw new IllegalArgumentException("Document exceeds the configured upload size limit.");
        String detectedType = detectType(upload);
        String key = "quarantine/" + UUID.randomUUID();
        try {
            byte[] content = upload.getBytes();
            if (s3Client == null) writePrivateLocal(key, content);
            else {
                PutObjectRequest.Builder put = PutObjectRequest.builder()
                        .bucket(properties.getS3Bucket())
                        .key(key)
                        .contentType(detectedType)
                        .contentLength((long) content.length)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId(properties.getS3KmsKeyId())
                        .metadata(java.util.Map.of("scan-status", "PENDING"));
                s3Client.putObject(put.build(), RequestBody.fromBytes(content));
            }
            return new StoredDocument(key, safeOriginalName(upload.getOriginalFilename()), detectedType, content.length, "PENDING");
        } catch (IOException exception) {
            throw new IllegalStateException("Document could not be read for secure quarantine storage.", exception);
        }
    }

    private void writePrivateLocal(String key, byte[] content) throws IOException {
        Path root = Path.of(properties.getLocalDirectory()).toAbsolutePath().normalize();
        Path destination = root.resolve(key).normalize();
        if (!destination.startsWith(root)) throw new IllegalArgumentException("Invalid document object key.");
        Files.createDirectories(destination.getParent());
        try { Files.setPosixFilePermissions(destination.getParent(), EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) { /* Windows ACLs inherit from the secured application data directory. */ }
        Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
        try {
            Files.write(temporary, content);
            try { Files.setPosixFilePermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { /* Windows ACLs inherit from the secured application data directory. */ }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    private String detectType(MultipartFile upload) {
        String supplied = upload.getContentType() == null ? "" : upload.getContentType().toLowerCase(Locale.ROOT);
        try {
            byte[] header = upload.getInputStream().readNBytes(16);
            boolean pdf = startsWith(header, new byte[]{'%', 'P', 'D', 'F', '-'});
            boolean png = startsWith(header, new byte[]{(byte)0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            boolean jpeg = header.length >= 3 && header[0] == (byte)0xff && header[1] == (byte)0xd8 && header[2] == (byte)0xff;
            if (pdf && (supplied.isBlank() || supplied.equals("application/pdf"))) return "application/pdf";
            if (png && (supplied.isBlank() || supplied.equals("image/png"))) return "image/png";
            if (jpeg && (supplied.isBlank() || supplied.equals("image/jpeg"))) return "image/jpeg";
            throw new IllegalArgumentException("Document content does not match the supported PDF, PNG or JPEG file type.");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Document content could not be inspected.", exception);
        }
    }

    private boolean startsWith(byte[] input, byte[] signature) {
        if (input.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (input[i] != signature[i]) return false;
        return true;
    }

    private String safeOriginalName(String name) {
        if (name == null || name.isBlank()) return "document";
        String leaf = name.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return leaf.length() > 120 ? leaf.substring(leaf.length() - 120) : leaf;
    }
}
