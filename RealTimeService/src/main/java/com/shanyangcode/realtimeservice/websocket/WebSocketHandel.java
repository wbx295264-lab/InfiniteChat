package com.shanyangcode.realtimeservice.websocket;

import cn.hutool.json.JSONUtil;
import com.shanyangcode.common.constant.CommonConstant;
import com.shanyangcode.common.model.dto.MessageRequest;
import com.shanyangcode.realtimeservice.constant.WebSocketConstant;
import com.shanyangcode.realtimeservice.utils.SnowflakeDynamicUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Date;


@Slf4j
@AllArgsConstructor
public class WebSocketHandel extends SimpleChannelInboundHandler<TextWebSocketFrame> {


    private final KafkaTemplate<String, String> kafkaTemplate;


    private final StringRedisTemplate stringRedisTemplate;

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, TextWebSocketFrame textWebSocketFrame) {
        Channel channel = channelHandlerContext.channel();
        String msg = textWebSocketFrame.text();

        try {
            if (WebSocketConstant.HEARTBEAT_PING.equals(msg)) {
                // 心跳响应
                if (channel.isActive()) {
                    log.debug("Received heartbeat ping from {}", channel.id());
                    channel.writeAndFlush(new TextWebSocketFrame(WebSocketConstant.HEARTBEAT_PONG));
                }
            } else {
                // 业务消息
                if (channel.isActive()) {
                    sendMessageKafka(msg, channel);
                } else {
                    log.warn("Channel {} inactive, skip message: {}", channel.id(), msg);
                }
            }

        } catch (Exception e) {
            log.error("Error handling message from {}: {}", channel.id(), msg, e);
            clearChannel(channel);
            channelHandlerContext.close();
        }
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {

        // 处理心跳
        if (evt instanceof IdleStateEvent event) {
            switch (event.state()) {
                case READER_IDLE:
                    log.warn("读空闲超时, channel={}", ctx.channel().id());
                    clearChannel(ctx.channel());
                    ctx.close();
                    break;
                case WRITER_IDLE:
                    log.warn("写空闲超时, channel={}", ctx.channel().id());
                    break;
                case ALL_IDLE:
                    log.warn("读写空闲超时, channel={}", ctx.channel().id());
                    break;
                default:
                    break;
            }
        }
    }


    public void sendMessageKafka(String message, Channel channel) {
        MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
        messageRequest.setMessageId(SnowflakeDynamicUtil.nextId());
        messageRequest.setCreatedTime(new Date());
        String payload = JSONUtil.toJsonStr(messageRequest);

        // 消息存储, 存储只存储一次，避免重复消费
        // 回调运行在 kafka-producer-network-thread 上，只记失败，成功分支不打日志
        kafkaTemplate.send(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, payload).whenComplete((success, failure) -> {
            if (failure != null) {
                log.error("Kafka 发送失败 topic={}, messageId={}", CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, messageRequest.getMessageId(), failure);
            }
        });

        // 消息推送消息，以 sessionId 为 key 保证同会话顺序
        kafkaTemplate.send(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, messageRequest.getSessionId().toString(), payload).whenComplete((success, failure) -> {
            if (failure != null) {
                log.error("Kafka 发送失败 topic={}, messageId={}", CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, messageRequest.getMessageId(), failure);
            }
        });
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught in channel pipeline", cause);
        ctx.close();
    }


    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        clearChannel(ctx.channel());
        super.handlerRemoved(ctx);
    }


    public void clearChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        String userId = ChannelManager.getUserIdByChannel(channel);
        try {
            if (userId != null) {
                ChannelManager.removeUserChannel(userId);
                saveOfflineTime(userId);
            }
            ChannelManager.removeChannelUser(channel);
        } catch (Exception e) {
            log.error("clearChannel failed for channel: {}, userId: {}", channel.id(), userId, e);
        } finally {
            if (channel.isActive()) {
                channel.close();
            }
        }
    }


    private void saveOfflineTime(String userId) {
        String key = CommonConstant.OFFLINE_KEY_REDIS + userId;
        String timestamp = String.valueOf(System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key, timestamp);
        log.debug("记录用户离线时间: userId={}, timestamp={}", userId, timestamp);
    }

}
