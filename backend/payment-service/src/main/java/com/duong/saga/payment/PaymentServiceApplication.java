package com.duong.saga.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** scanBasePackages = com.duong.saga để nạp cả thư viện messaging dùng chung. */
@SpringBootApplication(scanBasePackages = "com.duong.saga")
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
