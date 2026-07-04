package com.skew.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SkewEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkewEngineApplication.class, args);
	}

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	@Bean
	public org.springframework.web.client.RestClient.Builder restClientBuilder() {
		return org.springframework.web.client.RestClient.builder();
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
	public org.springframework.ai.embedding.EmbeddingModel embeddingModel() {
		return new org.springframework.ai.embedding.EmbeddingModel() {
			@Override
			public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
				return new org.springframework.ai.embedding.EmbeddingResponse(java.util.List.of());
			}
			@Override
			public float[] embed(org.springframework.ai.document.Document document) {
				return new float[768];
			}
		};
	}
}



