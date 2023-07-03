package com.helper.microservice01.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api2")
public class TestController {
    @Value("${testing.property}")
    private String value;

    @GetMapping("/m2")
    public String restApi(){
    return value + "it's mine now response";
    }
}
