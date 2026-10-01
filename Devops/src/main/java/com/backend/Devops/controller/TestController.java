package com.backend.Devops.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")
public class TestController {
    private static final Logger log = LoggerFactory.getLogger(TestController.class);
    private static final String HELPER_PATH = "/api2/m2";

    private final RestTemplate restTemplate;

    @Value("${testing.property}")
    private String value;

    @Value("${host.port}")
    private String hostPort;

    @Value("${helper.service.url}")
    private String helperServiceUrl;

    @Value("${helper.service.cluster-ip-url}")
    private String helperClusterIpUrl;

    public TestController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    //value injected from Dockerfile ENV / env var
    @GetMapping("/m1")
    public String restApi() {
        log.info("inside m1");
        return "value from property file: " + value;
    }

    //access another microservice endpoint using clusterIP service
    //using service's Ip address and port of service which you can see using k get svc
    @GetMapping("/m2")
    public ResponseEntity<String> resApi() {
        log.info("inside m2");
        return callHelper(helperClusterIpUrl);
    }

    //access another microservice using the service DNS name (helper-service)
    @GetMapping("/m3")
    public ResponseEntity<String> resApi2() {
        log.info("inside m3");
        return callHelper(helperServiceUrl);
    }

    //getting value via configmap
    @GetMapping("/m4")
    public String resApi4() {
        log.info("inside m4");
        return "host port is = " + hostPort;
    }

    private ResponseEntity<String> callHelper(String baseUrl) {
        String url = baseUrl + HELPER_PATH;
        try {
            return ResponseEntity.ok(restTemplate.getForObject(url, String.class));
        } catch (RestClientException ex) {
            log.warn("call to {} failed: {}", url, ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("helper service unavailable at " + url);
        }
    }
}
