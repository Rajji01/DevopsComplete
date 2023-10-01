package com.backend.Devops.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")

public class TestController {
    @Value("${testing.property}")
    private String value;

//    @Value("${testing.helper-service}")
//    private String serviceUrl;

    @GetMapping("/m1")
    public String restApi() {
        System.out.println("indiside m1=====>");
        return "value from property file1111" + value;
    }

    //access another microservice endpoint using clusterIP service
    //using service's Ip address and port of service which you can see using k get svc
    @GetMapping("/m2")
    public String resApi() {
        System.out.println("indiside m2=====>");
        String response=null;
        try {
            RestTemplate restTemplate = new RestTemplate();
            response = restTemplate.getForObject("http://10.100.13.14:8088/api2/m2", String.class);
            return response;
        } catch (Exception ex) {
            System.out.println("ex occured = " + ex.getMessage());
        }
        return response;
    }

    @GetMapping("/m3")
    public String resApi2() {
        System.out.println("indiside m3" +
                "=====>");
        String response=null;
        try {
            RestTemplate restTemplate = new RestTemplate();
            response = restTemplate.getForObject("http://helper-service:8088/api2/m2", String.class);
            return response;
        } catch (Exception ex) {
            System.out.println("ex occured = " + ex.getMessage());
        }
        return response;
    }
}
