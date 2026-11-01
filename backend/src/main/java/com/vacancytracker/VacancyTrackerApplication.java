package com.vacancytracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VacancyTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VacancyTrackerApplication.class, args);
    }
}
