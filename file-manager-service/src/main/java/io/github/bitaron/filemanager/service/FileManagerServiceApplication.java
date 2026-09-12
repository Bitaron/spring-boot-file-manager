package io.github.bitaron.filemanager.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Standalone Service: boots the same engine as Embedded Mode (via
 * {@code file-manager-spring-boot-starter}) plus a REST layer on top.
 */
@SpringBootApplication
public class FileManagerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileManagerServiceApplication.class, args);
    }
}
