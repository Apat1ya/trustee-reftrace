package dev.reftrace;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsExportTest {

    private static final String INSTANCE = "service.instance.id";

    @Test
    void switchedOnTheMetricsArePushedAsAnInstanceOfTheirOwn() {
        try (ConfigurableApplicationContext first = exporting(); ConfigurableApplicationContext second = exporting()) {
            assertThat(first.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
            String firstInstance = first.getBean(OtlpConfig.class).resourceAttributes().get(INSTANCE);
            String secondInstance = second.getBean(OtlpConfig.class).resourceAttributes().get(INSTANCE);

            assertThat(firstInstance).isNotBlank().isNotEqualTo(secondInstance);
            assertThat(first.getBean(OtlpConfig.class).resourceAttributes())
                    .containsEntry("service.name", "trustee-reftrace");
            assertThat(first.getBean(Resource.class).getAttribute(AttributeKey.stringKey(INSTANCE)))
                    .as("the traces carry the same instance").isEqualTo(firstInstance);
        }
    }

    private static ConfigurableApplicationContext exporting() {
        return new SpringApplicationBuilder(ReftraceApplication.class)
                .run("--management.otlp.metrics.export.enabled=true",
                        "--management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics");
    }
}
