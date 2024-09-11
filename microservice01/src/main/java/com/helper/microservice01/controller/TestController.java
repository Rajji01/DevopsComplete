package com.helper.microservice01.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api2")
public class TestController {
    private static final String FILE_PATH = "/cache/myfile.txt";

    @Value("${testing.property}")
    private String value;

    @GetMapping("/m2")
    public String restApi(){
    return value + "it's mine now response";
    }


    @GetMapping("/readFile")
    public String readFileData() {
        try {
            // Read the content of the file
            return new String(Files.readAllBytes(Paths.get(FILE_PATH)));
        } catch (IOException e) {
            // Handle the exception if the file is not found or unreadable
            return "File not found or unable to read.";
        }
    }
}
