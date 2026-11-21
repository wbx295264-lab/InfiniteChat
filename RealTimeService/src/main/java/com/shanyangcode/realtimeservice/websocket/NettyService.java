package com.shanyangcode.realtimeservice.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.NettyRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@RequiredArgsConstructor
public class NettyService {

    private final int port = 9101;

    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);


    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors() * 2);


    private final StringRedisTemplate stringRedisTemplate;

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 握手是否校验 token；perf profile 置 false，改用 X-User-Id 头
     */
    @Value("${im.auth.enabled:true}")
    private boolean authEnabled;

    /**
     * 读空闲超时（秒）。客户端 30s 一次 ping，线上 90s；perf profile 放宽到 3600
     */
    @Value("${im.idle.reader-seconds:90}")
    private int readerIdleSeconds;

    @PostConstruct
    public void start() throws InterruptedException {
        run();
    }




    public void run() throws InterruptedException {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline channelPipeline = socketChannel.pipeline();
                        channelPipeline.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0));
                        channelPipeline.addLast(new HttpServerCodec());
                        channelPipeline.addLast(new HttpObjectAggregator(65536));
                        channelPipeline.addLast(new WebSocketAuthHeader(stringRedisTemplate, authEnabled));
                        channelPipeline.addLast(new WebSocketServerProtocolHandler("/ws/netty"));
                        channelPipeline.addLast(new WebSocketHandel(kafkaTemplate, stringRedisTemplate));
                    }
                });
        serverBootstrap.bind(port).sync();

    }


    @PreDestroy
    public void destroy() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

}
