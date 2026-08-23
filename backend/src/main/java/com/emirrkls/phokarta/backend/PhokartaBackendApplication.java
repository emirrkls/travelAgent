package com.emirrkls.phokarta.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PhokartaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhokartaBackendApplication.class, args);
    }
}
