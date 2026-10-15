package com.insightbi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only backup download and non-destructive verification.
 * A restore HTTP route is intentionally NOT provided.
 */
@RestController
@RequestMapping("/api/admin/metadata-backup")
class MetadataBackupApi {
  private final MetadataBackupService service;
  MetadataBackupApi(MetadataBackupService service){this.service=service;}

  @GetMapping(produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
  ResponseEntity<byte[]> export(){
    byte[] encrypted=service.exportEncrypted();
    String filename="insight-bi-metadata-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+".ibak";
    return ResponseEntity.ok()
      .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
      .header(HttpHeaders.CACHE_CONTROL,"no-store")
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(encrypted);
  }

  @PostMapping(value="/verify",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
  Map<String,Object> verify(@RequestBody byte[] file){
    return service.verify(file);
  }
}
