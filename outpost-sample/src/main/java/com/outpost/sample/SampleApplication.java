package com.outpost.sample;

import com.outpost.core.produce.OutpostTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.Collections;

@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    /**
     * demo profile 下演示编程式与注解式两种注册方式；默认 profile 不运行，
     * 避免在没有 MySQL/Redis 的环境里产生启动副作用。
     */
    @Bean
    @Profile("demo")
    public CommandLineRunner outpostDemo(OutpostTemplate template,
                                         SampleOrderService orderService) {
        return args -> {
            template.executeInTransaction(() -> {
                template.registerCacheInvalidate("order",
                        Collections.singletonList("order:1"));
                return null;
            });
            orderService.updateOrder("2");
        };
    }
}
