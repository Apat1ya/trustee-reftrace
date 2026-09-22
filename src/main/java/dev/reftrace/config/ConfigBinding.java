package dev.reftrace.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.scheduling.support.CronExpression;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
class ConfigBinding {

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, StartEntry> startEntryConverter() {
        return text -> StartEntry.ofPage(URI.create(text.trim()));
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, ExpectEntry> expectEntryConverter() {
        return text -> {
            if (!ExpectEntry.FAIL.equals(text.trim())) {
                throw new IllegalArgumentException("an expect entry is {path-segment: n}, {query: name}, "
                        + "{query-if-present: name}, {cookie: name}, {local-storage: name} or " + ExpectEntry.FAIL + ": " + text);
            }
            return ExpectEntry.failing();
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, LinkMatch> linkMatchConverter() {
        return LinkMatch::parse;
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, StepWord> stepWordConverter() {
        return text -> StepWord.of(text.trim());
    }

    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, CronExpression> cronExpressionConverter() {
        return text -> CronExpression.parse(text.trim());
    }
}
