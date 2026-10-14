package com.example.weather.db;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 PostgreSQL + PostGIS 컨테이너(Testcontainers)에 Flyway 마이그레이션을 적용하고 앱 컨텍스트를 띄우는 통합 테스트의 기반 클래스.
 * 컨테이너는 테스트 JVM당 한 번만 띄우고, 같은 설정의 컨텍스트는 테스트 클래스 간에 재사용된다. Docker가 필요하다.
 */
@SpringBootTest
public abstract class PostgisIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("weather_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
