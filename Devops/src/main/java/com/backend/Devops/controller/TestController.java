package com.backend.Devops.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TestController {
    @Value("${testing.property}")
    private String value;

    @GetMapping("/m1")
    public String restApi(){
    return "value from property file"+value;
    }
}
