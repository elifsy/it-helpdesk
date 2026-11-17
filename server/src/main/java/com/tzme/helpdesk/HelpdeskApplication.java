package com.tzme.helpdesk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.tzme.helpdesk.mapper")
public class HelpdeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelpdeskApplication.class, args);
    }
}
