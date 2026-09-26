package dev.reftrace;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TracingExportTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void withoutAnEndpointNothingIsExported() {
        assertThat(context.getBeanNamesForType(SpanExporter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpMeterRegistry.class)).isEmpty();
        assertThat(context.getBean(Sampler.class).getDescription()).contains("TraceIdRatioBased{1.000000}");
    }

    @Test
    void anEndpointExportsTheTracesThereOverHttp() {
        try (ConfigurableApplicationContext exporting = new SpringApplicationBuilder(ReftraceApplication.class)
                .properties("management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces")
                .run()) {
            assertThat(exporting.getBeansOfType(SpanExporter.class).values())
                    .singleElement().isInstanceOf(OtlpHttpSpanExporter.class);
            assertThat(exporting.getBeanNamesForType(OtlpMeterRegistry.class)).isEmpty();
        }
    }
}
