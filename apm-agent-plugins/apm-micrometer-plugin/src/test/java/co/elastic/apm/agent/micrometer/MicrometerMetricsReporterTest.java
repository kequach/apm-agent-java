/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.micrometer;

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.MockTracer;
import co.elastic.apm.agent.configuration.MetricsConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import co.elastic.apm.agent.report.ReporterConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.CountingMode;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.util.TimeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;


class MicrometerMetricsReporterTest {

    private MeterRegistry meterRegistry;
    private MicrometerMetricsReporter metricsReporter;
    private MockReporter reporter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ElasticApmTracer tracer;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry simpleMeterRegistry = new SimpleMeterRegistry();
        CompositeMeterRegistry nestedCompositeMeterRegistry = new CompositeMeterRegistry(Clock.SYSTEM, List.of(simpleMeterRegistry));
        meterRegistry = new CompositeMeterRegistry(Clock.SYSTEM, List.of(nestedCompositeMeterRegistry));
        reporter = new MockReporter();
        tracer = MockTracer.createRealTracer(reporter);
        doReturn(61_000L).when(tracer.getConfig(ReporterConfiguration.class)).getMetricsIntervalMs();
        metricsReporter = new MicrometerMetricsReporter(tracer, true); //all calls to run() are explicit from the tests
        //note the default mode is CUMULATIVE, so no need to addConfig(meterRegistry, meterRegistryConfig);
        metricsReporter.registerMeterRegistry(meterRegistry);
        metricsReporter.registerMeterRegistry(nestedCompositeMeterRegistry);
        metricsReporter.registerMeterRegistry(simpleMeterRegistry);
        assertThat(metricsReporter.getMeterRegistries().containsKey(meterRegistry)).isFalse();
        assertThat(metricsReporter.getMeterRegistries().containsKey(nestedCompositeMeterRegistry)).isFalse();
        assertThat(metricsReporter.getMeterRegistries().containsKey(simpleMeterRegistry)).isTrue();
    }

    @AfterEach
    void tearDown() {
        int metricReports = reporter.getBytes().size();
        tracer.stop();
        reporter.awaitUntilAsserted(() -> assertThat(reporter.getBytes()).hasSizeGreaterThan(metricReports));
    }

    @Test
    void testSameMetricSet() {
        meterRegistry.counter("counter", List.of(Tag.of("foo", "bar"))).increment(42);
        meterRegistry.gauge("gauge", List.of(Tag.of("foo", "bar")), 42, v -> 42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(42);
        assertThat(metricSet.get("metricset").get("samples").get("gauge").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testMultipleMetricSets() {
        meterRegistry.counter("counter", List.of(Tag.of("foo", "bar"))).increment(42);
        meterRegistry.gauge("gauge", List.of(Tag.of("foo", "baz")), 42, v -> 42);

        List<JsonNode> metricSets = getMetricSets();
        assertThat(metricSets).hasSize(2);
        Optional<JsonNode> fooBar = metricSets.stream().filter(metricSet -> metricSet.get("metricset").get("tags").get("foo").textValue().equals("bar")).findAny();
        assertThat(fooBar).isNotEmpty();
        assertThat(fooBar.get().get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(42);
        Optional<JsonNode> fooBaz = metricSets.stream().filter(metricSet -> metricSet.get("metricset").get("tags").get("foo").textValue().equals("baz")).findAny();
        assertThat(fooBaz).isNotEmpty();
        assertThat(fooBaz.get().get("metricset").get("samples").get("gauge").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testDisabledMetrics() {
        doReturn(List.of(WildcardMatcher.valueOf("root.metric"), WildcardMatcher.valueOf("root.metric.exclude.*")))
            .when(tracer.getConfig(ReporterConfiguration.class)).getDisableMetrics();

        List<Tag> tags = List.of(Tag.of("foo", "bar"));
        meterRegistry.counter("root.metric", tags).increment(42);
        meterRegistry.counter("root.metric.include", tags).increment(42);
        meterRegistry.counter("root.metric.exclude.counter", tags).increment(42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples")).hasSize(1);
        assertThat(metricSet.get("metricset").get("samples").get("root_metric_include").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testDedotMetricName() {
        assertThat(tracer.getConfig(MetricsConfiguration.class).isDedotCustomMetrics()).isTrue();
        meterRegistry.counter("foo.bar").increment(42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("samples").get("foo_bar").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testDisableDedotMetricName() {
        doReturn(false).when(tracer.getConfig(MetricsConfiguration.class)).isDedotCustomMetrics();
        meterRegistry.counter("foo.bar").increment(42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("samples").get("foo.bar").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testNonAsciiMetricNameDisabledMetrics() {
        meterRegistry.counter("网络").increment(42);

        JsonNode metricSet = getSingleMetricSet();
        System.out.println("JsonNode metric = " + metricSet.toPrettyString());
        assertThat(metricSet.get("metricset").get("samples").get("网络").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testTagsSanitation() {
        List<Tag> tags = List.of(
            Tag.of("with.dot", "dot"),
            Tag.of("with*asterisk", "asterisk"),
            Tag.of("with\"quotation", "quotation")
        );
        meterRegistry.counter("counter", tags).increment(42);
        meterRegistry.gauge("gauge", tags, 42, v -> 42);

        JsonNode metricSet = getSingleMetricSet();
        JsonNode metricset = metricSet.get("metricset");
        JsonNode tagsNode = metricset.get("tags");
        assertThat(tagsNode.get("with_dot").textValue()).isEqualTo("dot");
        assertThat(tagsNode.get("with_asterisk").textValue()).isEqualTo("asterisk");
        assertThat(tagsNode.get("with_quotation").textValue()).isEqualTo("quotation");
        assertThat(metricset.get("samples").get("counter").get("value").doubleValue()).isEqualTo(42);
        assertThat(metricset.get("samples").get("gauge").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testDifferentMetricSets() {
        meterRegistry.counter("counter", List.of(Tag.of("foo", "bar"))).increment(42);
        meterRegistry.gauge("gauge", List.of(Tag.of("baz", "qux")), 42, v -> 42);

        List<JsonNode> metricSet = getMetricSets();
        assertThat(metricSet).hasSize(2);
    }

    @Test
    void testCounter() {
        meterRegistry.counter("counter", List.of(Tag.of("foo", "bar"), Tag.of("baz", "qux"))).increment(42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("tags").get("baz").textValue()).isEqualTo("qux");
        assertThat(metricSet.get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testCounterWithMetricsIntervalDisabled() {
        doReturn(0L).when(tracer.getConfig(ReporterConfiguration.class)).getMetricsIntervalMs();
        meterRegistry.counter("counter", List.of(Tag.of("foo", "bar"), Tag.of("baz", "qux"))).increment(42);
        List<JsonNode> metricSets = getMetricSets(null);
        assertThat(metricSets).isEmpty();
    }

    @Test
    void testFunctionCounter() {
        FunctionCounter.builder("counter", 42, i -> i).tag("foo", "bar").register(meterRegistry);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testStepCounterIntervalTakesPrecedenceAndNoReportBeforeFirstStep() {
        ThreadSafeMockClock clock = new ThreadSafeMockClock();
        metricsReporter.resetNow(clock.wallTime());
        SimpleConfig meterRegistryConfig = new SimpleConfig() {

            @Override
            public CountingMode mode() {
                return CountingMode.STEP;
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(10);
            }

            @Override
            public String get(@Nonnull String key) {
                return null;
            }
        };
        meterRegistry = new SimpleMeterRegistry(meterRegistryConfig, clock);
        metricsReporter.addConfig(meterRegistry, meterRegistryConfig);
        metricsReporter.registerMeterRegistry(meterRegistry);
        meterRegistry.counter("counter").increment();
        clock.add(5, TimeUnit.SECONDS);
        List<JsonNode> metricSets = getMetricSets(clock);
        //interval is 30s but step is 10s so that 10s should take precedence
        //which means after 5 seconds there should be no value reported
        assertThat(metricSets).isEmpty();

        //but after 15 seconds there should be an entry
        clock.add(10, TimeUnit.SECONDS);
        metricSets = getMetricSets(clock);
        assertThat(metricSets).hasSize(1);
        assertThat(metricSets.get(0).get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(1);
        System.out.println("E-- "+meterRegistry.counter("counter").count());
    }

    @Test
    void testCounterReset() {
        ThreadSafeMockClock clock = new ThreadSafeMockClock();
        metricsReporter.resetNow(clock.wallTime());
        SimpleConfig meterRegistryConfig;
        meterRegistry = new SimpleMeterRegistry(meterRegistryConfig = new SimpleConfig() {

            @Override
            public CountingMode mode() {
                return CountingMode.STEP;
            }

            @Override
            public Duration step() {
                return Duration.ofSeconds(30);
            }

            @Override
            public String get(@Nonnull String key) {
                return null;
            }
        }, clock);
        metricsReporter.addConfig(meterRegistry, meterRegistryConfig);
        metricsReporter.registerMeterRegistry(meterRegistry);
        meterRegistry.counter("counter").increment();

        clock.addSeconds(31);
        assertThat(getSingleMetricSet(clock).get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(1);

        clock.addSeconds(30);
        assertThat(getSingleMetricSet(clock).get("metricset").get("samples").get("counter").get("value").doubleValue()).isEqualTo(0);
    }

    @Test
    void testGauge() {
        meterRegistry.gauge("gauge", List.of(Tag.of("foo", "bar")), 42, v -> 42);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("gauge").get("value").doubleValue()).isEqualTo(42);
    }

    @Test
    void testGaugeNaNOnSecondInvocation() {
        AtomicBoolean fistInvocation = new AtomicBoolean(true);
        meterRegistry.gauge("gauge", 42, v -> fistInvocation.getAndSet(false) ? 42 : Double.NaN);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("samples").get("gauge").get("value").doubleValue()).isEqualTo(42);

        assertThat(getMetricSets()).isEmpty();
    }

    @Test
    void testTimer() {
        Timer timer = Timer.builder("timer").tag("foo", "bar").register(meterRegistry);
        timer.record(1, TimeUnit.MICROSECONDS);
        timer.record(2, TimeUnit.MICROSECONDS);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("timer.count").get("value").intValue()).isEqualTo(2);
        assertThat(metricSet.get("metricset").get("samples").get("timer.sum.us").get("value").longValue()).isEqualTo(3);
    }

    @Test
    void testTimerWithDotInMetricName() {
        Timer timer = Timer.builder("timer.dot").tag("foo", "bar").register(meterRegistry);
        timer.record(1, TimeUnit.MICROSECONDS);
        timer.record(2, TimeUnit.MICROSECONDS);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("timer_dot.count").get("value").intValue()).isEqualTo(2);
        assertThat(metricSet.get("metricset").get("samples").get("timer_dot.sum.us").get("value").longValue()).isEqualTo(3);
    }

    @Test
    void testFunctionTimer() {
        FunctionTimer.builder("timer", 42, i -> i, i -> i, TimeUnit.MICROSECONDS)
            .tag("foo", "bar")
            .register(meterRegistry);

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("timer.count").get("value").intValue()).isEqualTo(42);
        assertThat(metricSet.get("metricset").get("samples").get("timer.sum.us").get("value").longValue()).isEqualTo(42);

    }

    @Test
    void testLongTaskTimer() {
        LongTaskTimer timer = LongTaskTimer.builder("timer").tag("foo", "bar").register(meterRegistry);
        timer.start();

        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("timer.count").get("value").intValue()).isEqualTo(1);
        assertThat(metricSet.get("metricset").get("samples").get("timer.sum.us").get("value").longValue()).isNotNegative();
    }

    @Test
    void testDistributionSummary() {
        DistributionSummary timer = DistributionSummary.builder("timer").tag("foo", "bar").register(meterRegistry);
        timer.record(1);
        timer.record(2);


        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("tags").get("foo").textValue()).isEqualTo("bar");
        assertThat(metricSet.get("metricset").get("samples").get("timer.count").get("value").intValue()).isEqualTo(2);
        assertThat(metricSet.get("metricset").get("samples").get("timer.sum").get("value").longValue()).isEqualTo(3);
    }

    @Test
    void tryToSerializeInvalidGaugeValues() {
        for (Double invalidValue : Arrays.asList(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)) {
            List<Tag> tags = List.of(Tag.of("foo", "bar"));
            meterRegistry.gauge("gauge1", tags, 42, v -> invalidValue);
            meterRegistry.gauge("gauge2", tags, 42, v -> 42D);
            JsonNode metricSet = getSingleMetricSet();
            assertThat(metricSet.get("metricset").get("samples").get("gauge1"))
                .describedAs("value of %s is not expected to be written to json", invalidValue)
                .isNull();

            // serialization should handle ignoring the 1st value
            assertThat(metricSet.get("metricset").get("samples").get("gauge2").get("value").doubleValue())
                .isEqualTo(42D);
        }
    }

    @Test
    void testWorkingWithProperContextCL() {
        List<Tag> tags = List.of(Tag.of("foo", "bar"));
        meterRegistry.gauge("gauge1", tags, 42, v -> {
            if (Thread.currentThread().getContextClassLoader() == null) {
                throw new RuntimeException("Context CL cannot be null when querying this gauge");
            }
            return 42D;
        });
        JsonNode metricSet;
        ClassLoader originalContextCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            metricSet = getSingleMetricSet();
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextCL);
        }
        assertThat(metricSet.get("metricset").get("samples").get("gauge1").get("value").doubleValue()).isEqualTo(42D);
        assertThat(metricsReporter.getFailedMeters()).isEmpty();
    }

    @Test
    void testExclusionOfFailedGauge_singleGauge() {
        List<Tag> tags = List.of(Tag.of("foo", "bar"));
        meterRegistry.gauge("gauge1", tags, 42, v -> {
            throw new RuntimeException("Failed to read gauge value");
        });
        assertThat(metricsReporter.getFailedMeters()).isEmpty();
        assertThat(getMetricSets())
            .describedAs("json should not be reported for gauge1")
            .isEmpty();

        getMetricSets();
    }

    @Test
    void testExclusionOfFailedGauge_firstFails() {
        List<Tag> tags = List.of(Tag.of("foo", "bar"));
        meterRegistry.gauge("gauge1", tags, 42, v -> {
            throw new RuntimeException("Failed to read gauge value");
        });
        meterRegistry.gauge("gauge2", tags, 42, v -> 42D);
        assertThat(metricsReporter.getFailedMeters()).isEmpty();
        JsonNode metricSet = getSingleMetricSet();
        assertThat(metricSet.get("metricset").get("samples").get("gauge1"))
            .describedAs("value of %s is not expected to be written to json", "gauge1")
            .isNull();

        // serialization should handle ignoring the 1st value
        assertThat(metricSet.get("metricset").get("samples").get("gauge2").get("value").doubleValue()).isEqualTo(42D);
    }

    @Test
    void testExclusionOfFailedGauge_secondFails() {
        List<Tag> tags = List.of(Tag.of("foo", "bar"));
        meterRegistry.gauge("gauge1", tags, 42, v -> 42D);
        meterRegistry.gauge("gauge2", tags, 42, v -> {
            throw new RuntimeException("Failed to read gauge value");
        });
        assertThat(metricsReporter.getFailedMeters()).isEmpty();
        JsonNode metricSet = getSingleMetricSet();

        // serialization should handle ignoring the 1st value
        assertThat(metricSet.get("metricset").get("samples").get("gauge1").get("value").doubleValue()).isEqualTo(42D);

        assertThat(metricSet.get("metricset").get("samples").get("gauge2"))
            .describedAs("value of %s is not expected to be written to json", "gauge1")
            .isNull();
    }

    @Test
    void tryToSerializeInvalidCounterValues() {
        for (Double invalidValue : Arrays.asList(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)) {
            List<Tag> tags = List.of(Tag.of("foo", "bar"));
            meterRegistry.more().counter("custom-counter-1", tags, 42, v -> invalidValue);
            meterRegistry.more().counter("custom-counter-2", tags, 42, v -> 42D);
            JsonNode metricSet = getSingleMetricSet();
            assertThat(metricSet.get("metricset").get("samples").get("custom-counter-1"))
                .describedAs("value of %s is not expected to be written to json", invalidValue)
                .isNull();

            // serialization should handle ignoring the 1st value
            assertThat(metricSet.get("metricset").get("samples").get("custom-counter-2").get("value").doubleValue())
                .isEqualTo(42D);
        }
    }

    @Test
    void testNotReportingEmptySamples() {
        List<Tag> invalidMetricTags = List.of(Tag.of("test", "invalid"));
        meterRegistry.more().counter("custom-counter", invalidMetricTags, 42, v -> Double.NaN);
        List<Tag> validMetricTags = List.of(Tag.of("test", "valid"));
        meterRegistry.more().counter("custom-counter", validMetricTags, 42, v -> 42D);
        List<JsonNode> metricSets = getMetricSets();
        assertThat(metricSets)
            .describedAs("json should not be reported for %s", invalidMetricTags)
            .hasSize(1);
        assertThat(metricSets.get(0).get("metricset").get("tags").get("test").textValue()).isEqualTo("valid");
    }

    @Test
    void tryToSerializeInvalidTimerValues() {
        for (Double invalidValue : Arrays.asList(Double.POSITIVE_INFINITY, Double.NaN)) {
            List<Tag> tags = List.of(Tag.of("foo", "bar"));
            meterRegistry.more().timer("custom-timer-1", tags, 42, v -> 42L, v -> invalidValue, TimeUnit.MICROSECONDS);
            meterRegistry.more().timer("custom-timer-2", tags, 42, v -> 42L, v -> 42D, TimeUnit.MICROSECONDS);
            JsonNode metricSet = getSingleMetricSet();
            assertThat(metricSet.get("metricset").get("samples").get("custom-timer-1.count"))
                .describedAs("value of %s is not expected to be written to json", invalidValue)
                .isNull();
            assertThat(metricSet.get("metricset").get("samples").get("custom-timer-1.sum.us"))
                .describedAs("value of %s is not expected to be written to json", invalidValue)
                .isNull();

            // serialization should handle ignoring the 1st value
            assertThat(metricSet.get("metricset").get("samples").get("custom-timer-2.count").get("value").longValue())
                .isEqualTo(42L);
            assertThat(metricSet.get("metricset").get("samples").get("custom-timer-2.sum.us").get("value").doubleValue())
                .isEqualTo(42D);
        }
    }

    @Test
    void tryToSerializeInvalidTimeGaugeValues() {
        for (Double invalidValue : Arrays.asList(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)) {
            List<Tag> tags = List.of(Tag.of("foo", "bar"));
            meterRegistry.more().timeGauge("custom-time-gauge-1", tags, 42, TimeUnit.SECONDS, v -> invalidValue);
            meterRegistry.more().timeGauge("custom-time-gauge-2", tags, 42, TimeUnit.SECONDS, v -> 42D);
            JsonNode metricSet = getSingleMetricSet();
            assertThat(metricSet.get("metricset").get("samples").get("custom-time-gauge-1"))
                .describedAs("value of %s is not expected to be written to json", invalidValue)
                .isNull();

            // serialization should handle ignoring the 1st value
            assertThat(metricSet.get("metricset").get("samples").get("custom-time-gauge-2").get("value").doubleValue())
                .isEqualTo(42D);
        }
    }

    private JsonNode getSingleMetricSet() {
        return getSingleMetricSet(null);
    }
    private JsonNode getSingleMetricSet(Clock clock) {
        List<JsonNode> metricSets = getMetricSets(clock);
        assertThat(metricSets).hasSize(1);
        return metricSets.get(0);
    }

    private List<JsonNode> getMetricSets() {
        return getMetricSets(null);
    }
    private List<JsonNode> getMetricSets(Clock clock) {
        if (clock == null) {
            //default tests use a +61seconds invocation, as the default
            //SimpleMeterRegistry interval is 60 seconds
            long now = System.currentTimeMillis();
            metricsReporter.resetNow(now);
            metricsReporter.run(now + 61*1000L);
        } else {
            //Assume the caller has reset to some appropriate time
            metricsReporter.run(clock.wallTime());
        }
        List<JsonNode> metricSets = reporter.getBytes()
            .stream()
            .map(k -> new String(k, StandardCharsets.UTF_8))
            .flatMap(s -> Arrays.stream(s.split("\n")))
            .map(this::deserialize)
            .collect(Collectors.toList());
        reporter.reset();
        return metricSets;
    }

    private JsonNode deserialize(String json) {
        System.out.println(json);
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public class ThreadSafeMockClock implements Clock {

        // has to be non-zero to prevent divide-by-zeroes and other weird math results based
        // on the clock
        private AtomicLong timeNanos = new AtomicLong((long) TimeUtils.millisToUnit(1, TimeUnit.NANOSECONDS));

        @Override
        public long monotonicTime() {
            return timeNanos.get();
        }

        @Override
        public long wallTime() {
            return TimeUnit.MILLISECONDS.convert(timeNanos.get(), TimeUnit.NANOSECONDS);
        }

        public long add(long amount, TimeUnit unit) {
            return timeNanos.addAndGet(unit.toNanos(amount));
        }

        public long add(Duration duration) {
            return add(duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        public long addSeconds(long amount) {
            return add(amount, TimeUnit.SECONDS);
        }

    }
}
