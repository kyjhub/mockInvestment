package com.papertrade.paper_trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PaperTradingApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaperTradingApplication.class, args);
	}

}
