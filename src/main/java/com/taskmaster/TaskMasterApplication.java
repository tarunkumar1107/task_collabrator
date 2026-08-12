package com.taskmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskMasterApplication.class, args);
    }
}
