package com.example.s3proxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configuration class for database-specific settings and monitoring
 * Schema initialization is now handled by Flyway migrations
 */
@Configuration
public class DatabaseConfig {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    /**
     * MySQL-specific configuration
     */
    @Configuration
    @Profile("mysql")
    static class MySQLConfig {
        
        private final JdbcTemplate jdbcTemplate;
        
        public MySQLConfig(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }
        
        @EventListener(ApplicationReadyEvent.class)
        public void logMySQLInfo() {
            log.info("Using MySQL database with Flyway migrations");
            logDatabaseInfo();
        }
        
        private void logDatabaseInfo() {
            try {
                // Log table information using new table names
                Integer filesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM minio_files", Integer.class);
                Integer userFilesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM minio_user_files", Integer.class);
                
                log.info("Database initialized - Files: {}, User mappings: {}", filesCount, userFilesCount);
                log.info("MySQL indexes created for optimal query performance");
                
            } catch (Exception e) {
                log.warn("Could not retrieve database statistics: {}", e.getMessage());
            }
        }
    }
    
    /**
     * H2-specific configuration (default)
     */
    @Configuration
    @Profile({"h2", "!mysql"})
    static class H2Config {
        
        private static final Logger log = LoggerFactory.getLogger(H2Config.class);
        
        @EventListener(ApplicationReadyEvent.class)
        public void logH2Info() {
            log.info("Using H2 database with Flyway migrations");
            log.info("H2 Console available at: http://localhost:8080/h2-console");
        }
    }
}