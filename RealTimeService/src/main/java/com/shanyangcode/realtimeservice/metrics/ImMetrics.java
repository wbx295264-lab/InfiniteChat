package com.shanyangcode.realtimeservice.metrics;

import com.shanyangcode.realtimeservice.websocket.ChannelManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 实时消息链路指标
 * <p>
 * - im.channel.online：当前在线 WebSocket 连接数
 * - im.push.total{result=ok|fail|miss}：推送结果计数，miss 是消息静默丢失的唯一可见信号
 * - im.push.kafka.delay：消息在 push-topic 中排队 + 被消费者取到的时间
 */
@Component
public class ImMetrics {

    private final Counter pushOk;
    private final Counter pushFail;
    private final Counter pushMiss;
    private final Timer pushKafkaDelay;

    public ImMetrics(MeterRegistry registry) {
        Gauge.builder("im.channel.online", ChannelManager::onlineCount)
                .description("当前在线 WebSocket 连接数")
                .register(registry);
        pushOk = Counter.builder("im.push.total").tag("result", "ok").register(registry);
        pushFail = Counter.builder("im.push.total").tag("result", "fail").register(registry);
        pushMiss = Counter.builder("im.push.total").tag("result", "miss").register(registry);
        pushKafkaDelay = Timer.builder("im.push.kafka.delay")
                .description("push-topic 排队 + 拉取耗时")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void pushOk() {
        pushOk.increment();
    }

    public void pushFail() {
        pushFail.increment();
    }

    public void pushMiss() {
        pushMiss.increment();
    }

    public void recordKafkaDelay(long millis) {
        pushKafkaDelay.record(Duration.ofMillis(Math.max(millis, 0)));
    }
}
