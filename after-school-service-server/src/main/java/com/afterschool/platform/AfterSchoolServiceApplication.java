package com.afterschool.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.afterschool.platform")
public class AfterSchoolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AfterSchoolServiceApplication.class, args);
    }
}
