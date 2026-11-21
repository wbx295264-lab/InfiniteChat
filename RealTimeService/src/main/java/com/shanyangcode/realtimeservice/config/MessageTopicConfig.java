package com.shanyangcode.realtimeservice.config;

import com.shanyangcode.common.constant.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 消息主链路 topic 显式声明
 * <p>
 * 本地 Kafka num.partitions 默认为 1，若不声明，push/store 的消费并发形同虚设。
 * NewTopic 只在 topic 不存在时创建；已有的 1 分区旧 topic 需手动 --alter 扩分区。
 * DLT 分区数必须 ≥ 源 topic：DeadLetterPublishingRecoverer 默认投到同一分区号。
 */
@Configuration
public class MessageTopicConfig {

    public static final String DLT_SUFFIX = ".DLT";

    @Value("${im.topic.partitions:6}")
    private int partitions;

    @Bean
    public NewTopic storeTopic() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic pushTopic() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic storeTopicDlt() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE + DLT_SUFFIX).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic pushTopicDlt() {
        return TopicBuilder.name(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH + DLT_SUFFIX).partitions(partitions).replicas(1).build();
    }
}
