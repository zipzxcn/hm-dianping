package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final String name; // key

    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "order:";  // key前缀



    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程id
        long threadId = Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(threadId), timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String key = KEY_PREFIX + name;
        // 移除锁
        stringRedisTemplate.delete(key);
    }
}
