package com.superprogrammer.file.controller;

import com.superprogrammer.common.result.R;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<R<StoredFile>> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(R.ok(fileStorageService.store(file)));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> get(@PathVariable String fileId) {
        Resource resource = fileStorageService.load(fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileStorageService.detectMimeType(fileId)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileId + "\"")
                .body(resource);
    }
}
