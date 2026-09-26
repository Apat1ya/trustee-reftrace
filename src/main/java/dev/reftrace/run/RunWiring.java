package dev.reftrace.run;

import dev.reftrace.browse.BrowserWorkerFactory;
import dev.reftrace.browse.DeviceProfile;
import dev.reftrace.browse.PropertiesMapping;
import dev.reftrace.config.ReftraceProperties;
import dev.reftrace.config.Routes;
import dev.reftrace.crawl.WalkWorkers;
import dev.reftrace.judge.Judge;
import dev.reftrace.report.ReportWriter;
import dev.reftrace.report.RunRetention;
import dev.reftrace.sitemap.StartPages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class RunWiring {

    private static final String USER_AGENT = "reftrace-monitor";

    @Bean
    public PropertiesMapping propertiesMapping(ReftraceProperties properties) {
        return new PropertiesMapping(properties);
    }

    @Bean
    public RestClient siteRestClient(ReftraceProperties properties, ObservationRegistry observations) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        Duration pageLoad = properties.limits().action().pageLoad();
        if (!pageLoad.isZero()) {
            factory.setReadTimeout(pageLoad);
        }
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .observationRegistry(observations)
                .build();
    }

    @Bean
    public StartPages startPages(RestClient siteRestClient, Routes routes, ReftraceProperties properties) {
        return new StartPages(siteRestClient, routes, properties.keyParam());
    }

    @Bean
    public Judge judge(Routes routes) {
        return new Judge(routes);
    }

    @Bean
    public ReportWriter reportWriter(ReftraceProperties properties, ObjectMapper mapper) {
        return new ReportWriter(Path.of(properties.report().outputDir()), properties.keyParam(), mapper);
    }

    @Bean
    public RunRetention runRetention(ReftraceProperties properties, ObjectMapper mapper) {
        return new RunRetention(Path.of(properties.report().outputDir()), Path.of(System.getProperty("java.io.tmpdir")),
                properties.report().retention(), mapper);
    }

    @Bean
    public WalkWorkers walkWorkers(BrowserWorkerFactory browserWorkerFactory, Judge judge, Routes routes,
                                   ReftraceProperties properties, Clock clock, ObservationRegistry observations) {
        return new WalkWorkers(browserWorkerFactory, judge, routes, properties.keyParam(), properties.qrCheck(),
                WalkWorkers.Settings.of(properties), clock, observations);
    }

    @Bean
    RunMetrics runMetrics(MeterRegistry meterRegistry, PropertiesMapping propertiesMapping) {
        return new RunMetrics(meterRegistry,
                propertiesMapping.deviceProfiles().stream().map(DeviceProfile::name).toList());
    }

    @Bean
    Runner runner(ReftraceProperties properties, PropertiesMapping propertiesMapping,
                  StartPages startPages, WalkWorkers walkWorkers, ReportWriter reportWriter,
                  RunRetention runRetention, Clock clock, ObservationRegistry observations,
                  RunMetrics runMetrics) {
        return new Runner(properties, propertiesMapping.deviceProfiles(), startPages, walkWorkers,
                reportWriter, runRetention, clock, observations, runMetrics);
    }
}
