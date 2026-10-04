package museon_online.astor_butler.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LiquibaseModuleTest {

    // Spring Boot 4 keeps the Liquibase auto-configuration in its own module. Without it the application
    // starts healthy on an empty database and every query fails, because no migration ever runs.
    @Test void moduleThatRunsMigrationsIsOnTheClasspath() {
        assertThatCode(() -> Class.forName("org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration"))
                .doesNotThrowAnyException();
    }
}
