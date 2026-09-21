package dev.reftrace;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ReftraceApplication {

    public static final String RUN_ONCE = "run-once";

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ReftraceApplication.class, args);
        if (context.getBeanNamesForType(ExitCodeGenerator.class).length > 0) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
