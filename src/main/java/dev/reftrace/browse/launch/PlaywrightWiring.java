package dev.reftrace.browse.launch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RevealProperties.class)
public class PlaywrightWiring {
}
