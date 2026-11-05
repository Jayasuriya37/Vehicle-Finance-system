package com.vehiclefinance.api.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

@Component
public class PrivateUploadDirectories implements ApplicationRunner {
    private final DocumentStorageProperties properties;

    public PrivateUploadDirectories(DocumentStorageProperties properties) { this.properties = properties; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        restrict(Path.of(properties.getLocalDirectory()));
        restrict(Path.of(properties.getMultipartTempDirectory()));
    }

    private void restrict(Path configuredPath) throws Exception {
        Path directory = configuredPath.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows permissions are inherited from the account-protected application directory.
        }
    }
}
