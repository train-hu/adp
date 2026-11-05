package com.ops.deploy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AutoDeployApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoDeployApplication.class, args);
    }
}
