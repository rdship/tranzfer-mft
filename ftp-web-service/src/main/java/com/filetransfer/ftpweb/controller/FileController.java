package com.filetransfer.ftpweb.controller;

import com.filetransfer.ftpweb.service.FileOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FTP-Web File API
 *
 * GET    /api/files/list?path=/inbox               — list directory
 * POST   /api/files/upload?path=/inbox             — upload file (multipart)
 * GET    /api/files/download?path=/inbox/file.csv  — download file
 * DELETE /api/files/delete?path=/inbox/file.csv    — delete file or directory
 * POST   /api/files/mkdir?path=/inbox/reports      — create directory
 * POST   /api/files/rename?from=/old&to=/new       — rename/move
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileOperationService fileOperationService;

    @GetMapping("/list")
    public List<FileOperationService.FileEntry> list(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "/") String path) throws IOException {
        return fileOperationService.list(email, path);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void upload(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "/inbox") String path,
            @RequestPart("file") MultipartFile file) throws IOException {
        fileOperationService.upload(email, path, file);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal String email,
            @RequestParam String path) throws IOException {
        Resource resource = fileOperationService.download(email, path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(resource.getFilename(), StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal String email,
            @RequestParam String path) throws IOException {
        fileOperationService.delete(email, path);
    }

    @PostMapping("/mkdir")
    @ResponseStatus(HttpStatus.CREATED)
    public void mkdir(
            @AuthenticationPrincipal String email,
            @RequestParam String path) throws IOException {
        fileOperationService.mkdir(email, path);
    }

    @PostMapping("/rename")
    public void rename(
            @AuthenticationPrincipal String email,
            @RequestParam String from,
            @RequestParam String to) throws IOException {
        fileOperationService.rename(email, from, to);
    }
}
