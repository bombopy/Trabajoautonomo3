package org.example.fleet.delivery.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** Builds the application's only database connection pool from environment variables. */
public final class PostgresDataSourceFactory {
    private PostgresDataSourceFactory() {
    }

    public static DataSource create() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env("DB_URL", "jdbc:postgresql://localhost:5432/delivery"));
        config.setUsername(env("DB_USER", "delivery"));
        config.setPassword(env("DB_PASSWORD", "delivery"));
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setPoolName("delivery-postgres-pool");
        return new HikariDataSource(config);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
