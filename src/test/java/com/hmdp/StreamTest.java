package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

@Slf4j
@SpringBootTest
public class StreamTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void streamGroup() {
        try {
            // 创建 stream.orders 流 + g1 消费组
            stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
        } catch (Exception e) {
           log.error("消费组g1已存在！");
        }
    }
}
