package com.duong.saga.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** scanBasePackages = com.duong.saga để nạp cả thư viện messaging dùng chung. */
@SpringBootApplication(scanBasePackages = "com.duong.saga")
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
