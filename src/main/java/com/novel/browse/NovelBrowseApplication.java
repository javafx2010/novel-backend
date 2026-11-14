package com.novel.browse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NovelBrowseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelBrowseApplication.class, args);
    }
}
