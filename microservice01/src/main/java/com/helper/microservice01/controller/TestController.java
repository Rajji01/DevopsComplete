package com.helper.microservice01.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api2")
public class TestController {
    @Value("${testing.property}")
    private String value;

    @Value("${cache.file.path}")
    private String filePath;

    @GetMapping("/m2")
    public String restApi() {
        return value + " it's mine now response";
    }

    //reads the file written into the emptyDir volume (helperdeployEmptyDirVolume.yaml)
    @GetMapping("/readFile")
    public ResponseEntity<String> readFileData() {
        try {
            return ResponseEntity.ok(Files.readString(Paths.get(filePath), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found or unable to read.");
        }
    }
}
