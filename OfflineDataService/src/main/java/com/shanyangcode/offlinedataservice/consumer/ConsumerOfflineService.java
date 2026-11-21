package com.shanyangcode.offlinedataservice.consumer;

import cn.hutool.json.JSONUtil;
import com.shanyangcode.common.model.dto.MessageRequest;

import com.shanyangcode.offlinedataservice.service.MessageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@Slf4j
public class ConsumerOfflineService {

    @Resource
    private MessageService messageService;

    /**
     * 消息在 store-topic 中排队 + 被消费者取到的时间
     */
    private final Timer storeKafkaDelay;

    public ConsumerOfflineService(MeterRegistry registry) {
        this.storeKafkaDelay = Timer.builder("im.store.kafka.delay")
                .description("store-topic 排队 + 拉取耗时")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * store-topic 无 key，落库顺序本来就不保证；业务按 created_time 读取，乱序落库不影响读取，可安全提并发。
     * 落库异常直接抛出，交给 DefaultErrorHandler 重试后投 store-topic.DLT，不静默丢消息。
     */
    @KafkaListener(topics = "store-topic", groupId = "infinite-chat-store-group", concurrency = "${im.store.concurrency:6}")
    public void consume(String message, @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long producedAt) {
        storeKafkaDelay.record(Duration.ofMillis(Math.max(System.currentTimeMillis() - producedAt, 0)));
        MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
        messageService.saveMessageToMySQL(messageRequest);
    }
}
