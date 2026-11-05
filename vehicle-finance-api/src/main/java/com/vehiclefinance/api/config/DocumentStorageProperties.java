package com.vehiclefinance.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vfs.documents")
public class DocumentStorageProperties {
    private boolean uploadEnabled;
    private String storage = "local";
    private String localDirectory = "./data/quarantine";
    private String multipartTempDirectory = "./data/multipart-tmp";
    private long maxBytes = 5L * 1024 * 1024;
    private String s3Bucket = "";
    private String s3KmsKeyId = "";

    public boolean isUploadEnabled() { return uploadEnabled; }
    public void setUploadEnabled(boolean uploadEnabled) { this.uploadEnabled = uploadEnabled; }
    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }
    public String getLocalDirectory() { return localDirectory; }
    public void setLocalDirectory(String localDirectory) { this.localDirectory = localDirectory; }
    public String getMultipartTempDirectory() { return multipartTempDirectory; }
    public void setMultipartTempDirectory(String multipartTempDirectory) { this.multipartTempDirectory = multipartTempDirectory; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }
    public String getS3KmsKeyId() { return s3KmsKeyId; }
    public void setS3KmsKeyId(String s3KmsKeyId) { this.s3KmsKeyId = s3KmsKeyId; }
}
