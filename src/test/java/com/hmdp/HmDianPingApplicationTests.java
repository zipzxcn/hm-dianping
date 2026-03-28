package com.hmdp;


import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        // 定义线程任务
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long orderId = redisIdWorker.nextId("order");
                System.out.println("orderId = " + orderId);
            }
            // 调用一次countDown ，内部变量就减少1
            latch.countDown();
        };
        // 获取当前时间毫秒值
        long begin = System.currentTimeMillis();
        // 开启300个线程
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        // main阻塞 等待异步线程
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = "+(end-begin));
    }
}
