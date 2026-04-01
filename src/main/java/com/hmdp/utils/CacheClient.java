package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 封装redis工具类
 */
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /// 定义线程池
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            10, // 1. 核心线程数 线程池中长期存活 即使没事做，也不会被回收
            10, // 2. 最大线程数
            0L,  // 3. 空闲线程存活时间 参数为0则一旦空闲，立刻回收
            TimeUnit.MILLISECONDS, // 4. 空闲线程存活时间的时间单位 0为不等待
            new ArrayBlockingQueue<>(100), // 5. 阻塞队列（等待队列）有界队列（最多存 100 个任务） 优点：不会无限堆积导致内存爆掉（OOM）
            new ThreadPoolExecutor.AbortPolicy()  // 6. 拒绝策略 AbortPolicy直接抛出异常，拒绝执行
    );


    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void objectToCache(String key, Object object, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object), time, timeUnit);
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <T, R> T jsonToObjectPassThrough(R id, String partKey, Class<T> type, Function<R, T> dbFallback, Long time, TimeUnit timeUnit) {
        String key = partKey + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.isBlank(json)) {
            // 缓存中存在，返回
            return JSONUtil.toBean(json, type);
        }

        if (json != null) {
            return null;
        }

        // 查询数据库
        T object = dbFallback.apply(id);

        if (object == null) {
            // 数据库中和redis缓存中不存在数据，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }

        // 存入redis,设置过期时间
        this.objectToCache(key, object, time, timeUnit);

        return object;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void objectToJsonWithLogic(Object data, String key, Long time, TimeUnit timeUnit) {
        // 模拟重建延迟
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(data);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        // 写入redis缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    // 根据指定的key查询缓存，并反序列化为指定类型，需要利用互斥锁解决缓存击穿问题将逻辑进行封装
    public <T, R> T jsonToObjectBroken1(R id, String partKey, Class<T> type, Function<R, T> dbFallback, Long time, TimeUnit unit) {

        String key = partKey + id;
        while (true) {
            String json = stringRedisTemplate.opsForValue().get(key);  // 这里得到shopJson有三种情况，空串和空，有效对象
            // StringUtils.isBlank(shopJson) shopJson为null,""," "的情况下为true
            if (!StringUtils.isBlank(json)) {
                // 1.1.缓存中存在有效对象，命中，返回
                return JSONUtil.toBean(json, type);
            }

            // 空串""," "
            if (json != null) {
                return null;
            }

            // 尝试获取互斥锁
            String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(keyLock);
            if (isLock) {
                // 成功获取锁
                // 再次尝试从缓存中获取数据,double check
                String jsonStr = stringRedisTemplate.opsForValue().get(key);
                if (!StringUtils.isBlank(jsonStr)) {
                    return JSONUtil.toBean(jsonStr, type);
                }

                T apply = dbFallback.apply(id);

                if (apply == null) {
                    stringRedisTemplate.opsForValue().set(key, "", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
                    return null;
                }

                // 存入redis,设置过期时间
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(apply), time, unit);
                // 释放锁
                unlock(keyLock);
                return apply;
            } else {
                try {
                    // 获取锁失败，线程休眠等待
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
    }

    // 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题将逻辑进行封装
    public <T, R> T jsonToObjectBroken2(R id, String partKey, Class<T> type, Function<R, T> dbFallback, Long time, TimeUnit unit) {

        String key = partKey + id;
        //根据key从redis中查找缓存数据
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未命中
        if (StringUtils.isBlank(json)) {
            return null;
        }

        // 命中
        // 反序列化shopJson
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        T object = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 逻辑判断是否过期
        if (LocalDateTime.now().isBefore(expireTime)) { //isBefore(另一个时间)	判断 当前时间 是否 在 另一个时间 之前
            // 未过期，返回信息
            return object;
        }

        // 过期，尝试获取互斥锁
        String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(keyLock);
        if (isLock) {
            // 二次校验 double check，防止线程释放锁时，另一个线程拿到锁去查询数据库
            if (LocalDateTime.now().isBefore(expireTime)) { //isBefore(另一个时间)	判断 当前时间 是否 在 另一个时间 之前
                // 未过期，返回信息
                return object;
            }
            // 获取到互斥锁 开启线程 重建缓存
            EXECUTOR.submit(() -> {
                try {
                    T data = dbFallback.apply(id);
                    // 封装redisdata
                    this.objectToJsonWithLogic(data, key, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(keyLock);
                }
            });

        }
        // 返回过期数据
        return object;
    }


    /// 锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); // 或return flag != null && flag;
    }

    /// 释放锁
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
