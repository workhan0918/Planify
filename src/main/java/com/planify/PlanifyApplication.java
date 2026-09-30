package com.planify;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PlanifyApplication {
    public static void main(String[] args) { SpringApplication.run(PlanifyApplication.class, args); }
    @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
