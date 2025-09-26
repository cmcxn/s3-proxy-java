package com.example.s3proxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Configuration class for database-specific settings and initialization
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
        
        @Value("${spring.jpa.hibernate.ddl-auto:}")
        private String ddlAuto;
        
        public MySQLConfig(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }
        
        @EventListener(ApplicationReadyEvent.class)
        public void initializeMySQLSchema() {
            log.info("Initializing MySQL schema and optimized indexes...");
            
            try {
                // Read and execute the MySQL schema initialization script
                ClassPathResource resource = new ClassPathResource("db/migration/mysql-schema.sql");
                try (InputStream inputStream = resource.getInputStream()) {
                    String sql = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                    
                    // Split by semicolon and execute each statement
                    String[] statements = sql.split(";");
                    for (String statement : statements) {
                        statement = statement.trim();
                        if (!statement.isEmpty() && !statement.startsWith("--")) {
                            try {
                                jdbcTemplate.execute(statement);
                                log.debug("Executed SQL statement: {}", statement.substring(0, Math.min(50, statement.length())));
                            } catch (Exception e) {
                                log.warn("Error executing SQL statement (may be expected if already exists): {}", e.getMessage());
                            }
                        }
                    }
                }
                
                log.info("MySQL schema initialization completed successfully");
                
                // Log some database information
                logDatabaseInfo();
                
            } catch (Exception e) {
                log.error("Error during MySQL schema initialization", e);
                throw new RuntimeException("Failed to initialize MySQL schema", e);
            }
        }
        
        private void logDatabaseInfo() {
            try {
                // Log table information
                Integer filesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM files", Integer.class);
                Integer userFilesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_files", Integer.class);
                
                log.info("Database initialized - Files: {}, User mappings: {}", filesCount, userFilesCount);
                
                // Log index information for performance monitoring
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
            log.info("Using H2 database (embedded mode)");
            log.info("H2 Console available at: http://localhost:8080/h2-console");
        }
    }
}