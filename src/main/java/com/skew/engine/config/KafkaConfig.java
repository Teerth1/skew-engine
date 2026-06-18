package com.skew.engine.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    // Topic on which the SchwabSimulatorProducer publishes raw option ticks
    public static final String SCHWAB_TICKS_TOPIC = "schwab-option-ticks";
    
    // Topic for decoupled trade signal processing
    public static final String TRADE_SIGNALS_TOPIC = "trade-signals";

    @Bean
    public NewTopic schwabTicksTopic() {
        return TopicBuilder.name(SCHWAB_TICKS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic tradeSignalsTopic() {
        return TopicBuilder.name(TRADE_SIGNALS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, Object> tradeSignalContainerFactory(
            org.springframework.kafka.core.ConsumerFactory<String, Object> consumerFactory,
            @org.springframework.beans.factory.annotation.Value("${trading.signal-consumer.concurrency:3}") int concurrency) {
        
        org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        
        // Use virtual threads for the listener container to avoid blocking OS threads during slow LLM/API calls
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = 
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);
        executor.initialize();
        factory.getContainerProperties().setListenerTaskExecutor(executor);
        
        return factory;
    }
}
