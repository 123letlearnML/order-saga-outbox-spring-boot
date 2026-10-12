package com.duong.saga.messaging;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SagaProperties.class)
public class MessagingConfig {

    /**
     * Bảng outbox/inbox của thư viện được migrate bằng 1 Flyway RIÊNG, lịch sử ghi vào bảng riêng.
     *
     * <p>Bản đầu để migration của thư viện (V0_x) chung bảng lịch sử với service (V1, V2...). Khi thêm V0_2 cho
     * thư viện, mọi database đã chạy tới V1 đều báo "resolved migration not applied: 0.2" (migration lùi thứ tự)
     * và service không khởi động được. Thư viện và service phải có dòng version độc lập.
     */
    @Bean
    FlywayMigrationStrategy migrateServiceThenMessaging(DataSource dataSource) {
        return serviceFlyway -> {
            // Service trước: Flyway của service gặp schema có bảng mà chưa có lịch sử của nó sẽ từ chối chạy.
            serviceFlyway.migrate();
            // Thư viện sau: baseline ở version 0 => V0_1, V0_2... vẫn chạy đủ trên schema đã có bảng của service.
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/messaging")
                    .table("flyway_messaging_history")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
        };
    }
}
