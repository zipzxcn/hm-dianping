package com.hmdp.service.impl;


import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /// 定义线程池
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            10, // 1. 核心线程数 线程池中长期存活 即使没事做，也不会被回收
            10, // 2. 最大线程数
            0L,  // 3. 空闲线程存活时间 参数为0则一旦空闲，立刻回收
            TimeUnit.MILLISECONDS, // 4. 空闲线程存活时间的时间单位 0为不等待
            new ArrayBlockingQueue<>(100), // 5. 阻塞队列（等待队列）有界队列（最多存 100 个任务） 优点：不会无限堆积导致内存爆掉（OOM）
            new ThreadPoolExecutor.AbortPolicy()  // 6. 拒绝策略 AbortPolicy直接抛出异常，拒绝执行
    );


    @Override
    public Result queryById(Long id) {

        // 基于返回空数据解决缓存穿透
        // Shop shop = queryWithPassThrough(id);
        /*Shop shop = cacheClient.jsonToObjectPassThrough(id, RedisConstants.CACHE_SHOP_KEY,
                Shop.class, shopId -> this.getById(id), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);*/
        // 基于互斥锁解决缓存击穿
        //Shop shop = queryWithMutex1(id);
        Shop shop = cacheClient.jsonToObjectBroken1(id, RedisConstants.CACHE_SHOP_KEY,
                Shop.class, shopId -> this.getById(id), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 基于逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        /*Shop shop = cacheClient.jsonToObjectBroken2(id, RedisConstants.CACHE_SHOP_KEY,
                Shop.class, shopId -> this.getById(id), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);*/
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /// 缓存击穿 递归版本
    /*public Shop queryWithMutex(Long id) {
        // 1.根据id从redis中查找缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);  // 这里得到shopJson有三种情况，空串和空，有效对象

        // StringUtils.isBlank(shopJson) shopJson为null,""," "的情况下为true
        if (!StringUtils.isBlank(shopJson)) {
            // 1.1.缓存中存在有效对象，命中，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 空串""," "
        if (shopJson != null) {
            return null;
        }
        // shopJson == null,未命中
        String keyLock = null;
        Shop shop = null;
        try {
            // 尝试获取互斥锁
            keyLock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(keyLock);
            // 判断是否获取互斥锁
            if (!isLock) {
                // 未获取锁，线程休眠
                Thread.sleep(50);
                // 再次查询店铺
                this.queryWithMutex(id);
            }
            // 成功获取锁
            // 再次尝试从缓存中获取数据,double check
            String s = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.isBlank(s)) {
                return JSONUtil.toBean(s, Shop.class);
            }
            // 2.不存在,空，查询数据库
            shop = this.getById(id);

            if (shop == null) {
                // 数据库中和redis缓存中不存在数据，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 存入redis,设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(keyLock);
        }

        return shop;
    }*/

    /// 基于互斥锁解决缓存击穿 循环
    /*public Shop queryWithMutex1(Long id) {
        // 1.根据id从redis中查找缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        while (true) {

            String shopJson = stringRedisTemplate.opsForValue().get(key);  // 这里得到shopJson有三种情况，空串和空，有效对象

            // StringUtils.isBlank(shopJson) shopJson为null,""," "的情况下为true
            if (!StringUtils.isBlank(shopJson)) {
                // 1.1.缓存中存在有效对象，命中，返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 空串""," "
            if (shopJson != null) {
                return null;
            }

            // 尝试获取互斥锁
            String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(keyLock);

            if (isLock) {
                try {
                    // 成功获取锁
                    // 再次尝试从缓存中获取数据,double check
                    String shopJsonStr = stringRedisTemplate.opsForValue().get(key);
                    if (!StringUtils.isBlank(shopJsonStr)) {
                        return JSONUtil.toBean(shopJsonStr, Shop.class);
                    }
                    // 2.不存在,空，查询数据库
                    Shop shop = this.getById(id);

                    if (shop == null) {
                        // 数据库中和redis缓存中不存在数据，防止缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
                        return null;
                    }

                    // 存入redis,设置过期时间
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

                    //返回数据
                    return shop;
                } finally {
                    // 释放锁
                    unlock(keyLock);
                }
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
    }*/


    /// 缓存穿透
    /*public Shop queryWithPassThrough(Long id) {

        // 1.根据id从redis中查找缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);  // 这里得到shopJson有三种情况，空串和空，有效对象

        // StringUtils.isBlank(shopJson) shopJson为null,""," "的情况下为true
        if (!StringUtils.isBlank(shopJson)) {
            // 1.1.缓存中存在有效对象，命中，返回
            Shop cacheShop = JSONUtil.toBean(shopJson, Shop.class);
            return cacheShop;
        }

        // 空串""," "
        if (shopJson != null) {
            return null;
        }
        // shopJson == null

        // 2.不存在,空，查询数据库
        Shop shop = this.getById(id);

        if (shop == null) {
            // 数据库中和redis缓存中不存在数据，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 3.存入redis,设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;

    }*/


    /// 基于逻辑过期解决缓存击穿
    /*public Shop queryWithLogicalExpire(Long id) {
        // 1.根据id从redis中查找缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 未命中，返回null
        if (StringUtils.isBlank(shopJson)) {
            return null;
        }

        // 命中 逻辑判断是否过期
        // 反序列化shopJson
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {  // isAfter(另一个时间)	判断 当前时间 是否 在 另一个时间 之后
            // 未过期，返回信息
            return shop;
        }

        // 过期，尝试获取互斥锁
        String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(keyLock);
        if (isLock) {
            // 二次校验 double check，防止线程释放锁时，另一个线程拿到锁去查询数据库
            if (expireTime.isAfter(LocalDateTime.now())) {  // isAfter(另一个时间)	判断 当前时间 是否 在 另一个时间 之后
                // 未过期，返回信息
                return shop;
            }

            // 获取到互斥锁 开启线程 重建缓存
            EXECUTOR.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        saveShop(id, RedisConstants.CACHE_SHOP_TTL);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        // 释放互斥锁
                        unlock(keyLock);
                    }
                }
            });
        }

        //未获取到互斥锁,直接返回信息
        return shop;
    }*/

    /*/// 重建缓存
    public void saveShop(Long id, Long expireTime) {
        // 查询数据库
        Shop shop = this.getById(id);
        // 封装redisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 重建缓存 写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /// 锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); // 或return flag != null && flag;
    }

    /// 释放锁
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }*/
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商户不存在！");
        }
        // 修改数据
        this.updateById(shop);

        // 移除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }


}
