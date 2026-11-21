package com.shanyangcode.realtimeservice.websocket;


import com.shanyangcode.common.constant.CommonConstant;
import com.shanyangcode.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;


/**
 * WebSocket 握手鉴权
 * <p>
 * im.auth.enabled=true（默认）：读 Authorization 头 → JWT 解析 → 与 Redis access:token 比对。
 * im.auth.enabled=false（压测 perf profile）：直接从 X-User-Id 头取 userId，握手阶段不访问 Redis。
 */
@Slf4j
@RequiredArgsConstructor
public class WebSocketAuthHeader extends ChannelInboundHandlerAdapter {

    public static final String PERF_USER_ID_HEADER = "X-User-Id";

    private final StringRedisTemplate stringRedisTemplate;

    private final boolean authEnabled;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String userId = authEnabled ? resolveByToken(request) : request.headers().get(PERF_USER_ID_HEADER);
        if (userId == null || userId.isEmpty()) {
            ctx.close();
            return;
        }
        ChannelManager.addUserChannel(userId, ctx.channel());
        ChannelManager.addChannelUser(userId, ctx.channel());
        ctx.fireChannelRead(msg);
    }

    /**
     * 线上形态：JWT + Redis 双重校验
     */
    private String resolveByToken(FullHttpRequest request) {
        String authHeader = request.headers().get("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }
        try {
            Claims claims = JwtUtil.parse(authHeader);
            if (claims == null) {
                return null;
            }
            String userId = claims.getSubject();
            if (userId == null || userId.isEmpty()) {
                return null;
            }
            String storedToken = stringRedisTemplate.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
            return (StringUtils.isEmpty(storedToken) || !authHeader.equals(storedToken)) ? null : userId;
        } catch (Exception e) {
            log.warn("WebSocket 握手鉴权异常", e);
            return null;
        }
    }
}
