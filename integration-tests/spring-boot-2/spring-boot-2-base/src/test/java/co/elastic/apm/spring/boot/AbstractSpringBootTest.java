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
package co.elastic.apm.spring.boot;

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.MockTracer;
import co.elastic.apm.agent.bci.ElasticApmAgent;
import co.elastic.apm.agent.impl.context.web.WebConfiguration;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.report.ReporterConfiguration;
import co.elastic.apm.api.ElasticApm;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.stagemonitor.configuration.ConfigurationRegistry;

import java.util.Collections;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
public abstract class AbstractSpringBootTest {

    private static MockReporter reporter;
    private static ConfigurationRegistry config;
    @LocalServerPort
    private int port;
    private TestRestTemplate restTemplate;

    @BeforeClass
    public static void beforeClass() {
        MockTracer.MockInstrumentationSetup mockInstrumentationSetup = MockTracer.createMockInstrumentationSetup();
        config = mockInstrumentationSetup.getConfig();
        reporter = mockInstrumentationSetup.getReporter();
        ElasticApmAgent.initInstrumentation(mockInstrumentationSetup.getTracer(), ByteBuddyAgent.install());
    }

    @Before
    public void setUp() {
        doReturn(true).when(config.getConfig(ReporterConfiguration.class)).isReportSynchronously();
        restTemplate = new TestRestTemplate(new RestTemplateBuilder()
            .setConnectTimeout(0)
            .setReadTimeout(0)
            .basicAuthorization("username", "password"));
        reporter.reset();
    }

    @AfterClass
    public static void tearDown() {
        ElasticApmAgent.reset();
    }

    @Test
    public void greetingShouldReturnDefaultMessage() throws Exception {
        assertThat(restTemplate.getForObject("http://localhost:" + port + "/", String.class))
            .contains("Hello World");

        // the transaction might not have been reported yet, as the http call returns when the ServletOutputStream has been closed,
        // which is before the transaction has ended
        final Transaction transaction = reporter.getFirstTransaction(500);
        assertThat(transaction.getNameAsString()).isEqualTo("TestApp#greeting");
        assertThat(transaction.getContext().getUser().getDomain()).isEqualTo("domain");
        assertThat(transaction.getContext().getUser().getId()).isEqualTo("id");
        assertThat(transaction.getContext().getUser().getEmail()).isEqualTo("email");
        assertThat(transaction.getContext().getUser().getUsername()).isEqualTo("username");
        // as this test runs in a standalone application and not in a servlet container,
        // the service.name will not be overwritten for the webapp class loader based on spring.application.name
        assertThat(transaction.getTraceContext().getServiceName()).isNull();
        assertThat(transaction.getFrameworkName()).isEqualTo("Spring Web MVC");
        assertThat(transaction.getFrameworkVersion()).isEqualTo("5.1.9.RELEASE");
    }

    @Test
    public void testStaticFile() {
        doReturn(Collections.emptyList()).when(config.getConfig(WebConfiguration.class)).getIgnoreUrls();
        assertThat(restTemplate.getForObject("http://localhost:" + port + "/script.js", String.class))
            .contains("// empty test script");

        assertThat(reporter.getFirstTransaction(500).getNameAsString()).isEqualTo("ResourceHttpRequestHandler");
        assertThat(reporter.getFirstTransaction().getFrameworkName()).isEqualTo("Spring Web MVC");
        assertThat(reporter.getFirstTransaction().getContext().getUser().getUsername()).isEqualTo("username");
    }

    @RestController
    @SpringBootApplication
    public static class TestApp {

        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @GetMapping("/")
        public String greeting() {
            ElasticApm.currentTransaction().setUser("id", "email", "username", "domain");
            return "Hello World";
        }

        @Configuration
        @EnableWebSecurity
        public class WebSecurityConfig extends WebSecurityConfigurerAdapter {
            @Override
            protected void configure(HttpSecurity http) throws Exception {
                http.authorizeRequests()
                    .anyRequest().authenticated()
                    .and()
                    .httpBasic();
            }

            @Bean
            @Override
            public UserDetailsService userDetailsService() {
                return new InMemoryUserDetailsManager(User.withDefaultPasswordEncoder()
                    .username("username")
                    .password("password")
                    .roles("USER")
                    .build());
            }
        }

    }
}
