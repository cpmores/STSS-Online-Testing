package com.stss.online_testing;

import com.stss.online_testing.config.LoggerGrpcProperties;
import com.stss.online_testing.config.ProctorGrpcProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.stss.online_testing.mapper")
@EnableConfigurationProperties({LoggerGrpcProperties.class, ProctorGrpcProperties.class})
public class OnlineTestingApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnlineTestingApplication.class, args);
	}

}
