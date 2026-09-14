package org.jeecgframework.boot;

import org.jeecg.config.UniqueNameGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableFeignClients(basePackages = {"org.jeecg"})
@ComponentScan(nameGenerator = UniqueNameGenerator.class, basePackages = {"com.sac.platform","org.jeecg"})
public class SacCloudSsoStartApplication {

    public static void main(String[] args) {
        SpringApplication.run(SacCloudSsoStartApplication.class, args);
    }

}
