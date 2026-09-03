package com.superprogrammer.knowledge.connector;

import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * C6 连接器（WP6 Step3）：byte[] → {@link MultipartFile} 适配器。
 * 连接器下载的字节流要走与手工上传完全相同的管线（store 白名单/magic 嗅探/配额/版本链），
 * 而那条管线的入口签名是 MultipartFile——适配而非旁路，安全闸门一处不漏。
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String contentType;
    private final byte[] bytes;

    public InMemoryMultipartFile(String name, String contentType, byte[] bytes) {
        this.name = name == null || name.isBlank() ? "connector-download" : name;
        this.contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    @NonNull
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(@NonNull File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes);
        }
    }
}
