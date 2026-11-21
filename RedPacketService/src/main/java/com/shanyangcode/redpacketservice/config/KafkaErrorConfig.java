package com.shanyangcode.redpacketservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.shanyangcode.redpacketservice.constant.KafkaConstant;

/**
 * Kafka 消费失败处理：重试 2 次（退避 1s），仍失败投到 <topic>.DLT，绝不静默跳过。
 * <p>
 * 领取事件消费者不再吞掉 DB 异常（DuplicateKeyException 除外），Hikari 超时、死锁等可重试异常会进入这里。
 * DLT 分区数必须 ≥ 源 topic：DeadLetterPublishingRecoverer 默认投到同一分区号。
 */
@Configuration
public class KafkaErrorConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    public NewTopic redpacketReceiveTopicDlt() {
        return TopicBuilder.name(KafkaConfig.TOPIC_REDPACKET_RECEIVE + DLT_SUFFIX)
                .partitions(KafkaConstant.DEFAULT_PARTITION_COUNT)
                .replicas(KafkaConstant.DEFAULT_REPLICA_COUNT)
                .build();
    }
}
