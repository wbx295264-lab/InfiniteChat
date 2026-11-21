package com.shanyangcode.realtimeservice.consumer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.shanyangcode.common.constant.CommonConstant;
import com.shanyangcode.common.constant.SessionTypeConstant;
import com.shanyangcode.common.model.dto.ChatRequest;
import com.shanyangcode.common.model.dto.MessageBody;
import com.shanyangcode.common.model.dto.MessageRequest;
import com.shanyangcode.common.model.vo.MessageResponse;
import com.shanyangcode.common.utils.FormatDateUtil;
import com.shanyangcode.realtimeservice.client.AiServiceClient;
import com.shanyangcode.realtimeservice.client.UserServiceClient;
import com.shanyangcode.realtimeservice.metrics.ImMetrics;
import com.shanyangcode.realtimeservice.utils.SnowflakeDynamicUtil;
import com.shanyangcode.realtimeservice.websocket.ChannelManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
@Slf4j
public class ConsumerMessageService {


    @Resource
    private UserServiceClient userServiceClient;


    @Resource
    private AiServiceClient aiServiceClient;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ImMetrics imMetrics;


    /**
     * push-topic 以 sessionId 为 key 分区，同会话消息永远在同一分区、同一线程，多线程消费不打乱会话内顺序。
     * concurrency 不应超过分区数（见 MessageTopicConfig）。
     */
    @KafkaListener(topics = "push-topic", groupId = "infinite-chat-push-group-0", concurrency = "${im.push.concurrency:6}")
    public void consume(String message, @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long producedAt) {
        // producedAt 是 producer 打的 CreateTime（毫秒），同机压测时钟一致，可直接相减
        imMetrics.recordKafkaDelay(System.currentTimeMillis() - producedAt);

        MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
        if (messageRequest.getSessionType() == SessionTypeConstant.SIGNAL_TYPE) {
            signalMessage(messageRequest);
        } else if (messageRequest.getSessionType() == SessionTypeConstant.GROUP_TYPE) {
            groupMessage(messageRequest);
        } else if (messageRequest.getSessionType() == SessionTypeConstant.ROBOT_TYPE) {
            aiSignalMessage(messageRequest);
        }
    }


    public void aiSignalMessage(MessageRequest messageRequest) {
        MessageResponse messageResponse = createMessageResponse(messageRequest);
        messageResponse.setMessageId(SnowflakeDynamicUtil.nextId());
        // 获取 AI 回复
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setPrompt(messageRequest.getBody().getContent());
        chatRequest.setSessionId(messageRequest.getSessionId());
        chatRequest.setUserId(messageRequest.getSenderId());
        String chat = aiServiceClient.chat(chatRequest);


        MessageBody messageBody = messageResponse.getBody();
        messageBody.setContent(chat);
        messageResponse.setBody(messageBody);
        messageResponse.setSenderId(CommonConstant.AI_ID);

        pushMessageToUser(messageResponse, chatRequest.getUserId());
        BeanUtil.copyProperties(messageResponse, messageRequest);

        kafkaTemplate.send(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, JSONUtil.toJsonStr(messageRequest)).whenComplete((success, failure) -> {
            if (failure != null) {
                log.error("Kafka 发送失败 topic={}, messageId={}", CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, messageRequest.getMessageId(), failure);
            }
        });
    }



    public void signalMessage(MessageRequest messageRequest) {
        MessageResponse messageResponse = createMessageResponse(messageRequest);
        String payload = JSONUtil.toJsonStr(messageResponse);
        pushMessageToUser(payload, messageRequest.getSenderId());
        pushMessageToUser(payload, messageRequest.getReceiverId());
    }

    public void groupMessage(MessageRequest messageRequest) {
        List<Long> receiveUserIds = userServiceClient.getUserIdBySessionId(messageRequest.getSessionId());
        MessageResponse messageResponse = createMessageResponse(messageRequest);
        // 序列化一次，frame 每个成员各 new 一个（写出后会被 release，不能复用）
        String payload = JSONUtil.toJsonStr(messageResponse);
        for (Long receiveUserId : receiveUserIds) {
            pushMessageToUser(payload, receiveUserId);
        }
    }

    public MessageResponse createMessageResponse(MessageRequest messageRequest) {
        MessageResponse messageResponse = new MessageResponse();
        BeanUtil.copyProperties(messageRequest, messageResponse);
        messageResponse.setCreatedTime(FormatDateUtil.formatDate(messageRequest.getCreatedTime()));
        return messageResponse;
    }

    public void pushMessageToUser(MessageResponse messageResponse, Long receiverId) {
        pushMessageToUser(JSONUtil.toJsonStr(messageResponse), receiverId);
    }

    public void pushMessageToUser(String payload, Long receiverId) {
        Channel channel = ChannelManager.getChannelByUserId(receiverId.toString());
        if (channel == null) {
            imMetrics.pushMiss();
            return;
        }
        channel.writeAndFlush(new TextWebSocketFrame(payload)).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                imMetrics.pushOk();
            } else {
                imMetrics.pushFail();
                log.warn("推送失败 receiverId={}, cause={}", receiverId,
                        future.cause() == null ? "unknown" : future.cause().getMessage());
            }
        });
    }

}
