package com.shanyangcode.userservice.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.shanyangcode.common.enums.UserSessionStatusEnum;
import com.shanyangcode.userservice.mapper.UserSessionMapper;
import com.shanyangcode.userservice.model.entity.UserSession;
import com.shanyangcode.userservice.service.UserSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Slf4j
@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession>
    implements UserSessionService {

    /**
     * 会话成员列表缓存 key 前缀；RealTimeService 每条群消息都会 Feign 调一次 getUserIdBySessionId
     */
    public static final String SESSION_MEMBERS_KEY_PREFIX = "session:members:";

    private static final int SESSION_MEMBERS_TTL_BASE_SECONDS = 600;

    private static final int SESSION_MEMBERS_TTL_JITTER_SECONDS = 120;

    private final StringRedisTemplate stringRedisTemplate;

    public UserSessionServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public List<Long> getUserIdBySessionId(Long sessionId) {
        String key = SESSION_MEMBERS_KEY_PREFIX + sessionId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return JSONUtil.toList(cached, Long.class);
            }
        } catch (Exception e) {
            log.warn("读取会话成员缓存失败，回源 DB。sessionId={}", sessionId, e);
        }

        QueryWrapper<UserSession> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId);
        List<UserSession> userSessions = this.list(queryWrapper);
        List<Long> ids = userSessions.stream().map(UserSession::getUserId).collect(Collectors.toList());

        try {
            long ttl = SESSION_MEMBERS_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(SESSION_MEMBERS_TTL_JITTER_SECONDS);
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(ids), ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入会话成员缓存失败。sessionId={}", sessionId, e);
        }
        return ids;
    }


    @Override
    public void evictSessionMembersCache(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(SESSION_MEMBERS_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("失效会话成员缓存失败。sessionId={}", sessionId, e);
        }
    }


    @Override
    public List<Long> getSessionIdsByUserId(Long userId) {
        QueryWrapper<UserSession> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        List<UserSession> userSessions = this.list(queryWrapper);
        return userSessions.stream().map(UserSession::getSessionId).collect(Collectors.toList());
    }

    @Override
    public int getGroupMemberCount(Long sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getStatus, UserSessionStatusEnum.NORMAL.getCode());
        return Math.toIntExact(this.count(wrapper));
    }
}
