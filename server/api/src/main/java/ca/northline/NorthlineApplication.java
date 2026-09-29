package ca.northline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;

@Modulithic(sharedModules = "shared")
@EnableAsync
@SpringBootApplication
public class NorthlineApplication {
    public static void main(String[] args) {
        SpringApplication.run(NorthlineApplication.class, args);
    }
}
