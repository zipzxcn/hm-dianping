package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    private final RedisIdWorker redisIdWorker;

    //private final SeckillVoucherMapper seckillVoucherMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR =new ThreadPoolExecutor(
            100,
            100,
            0, TimeUnit.MINUTES,
            new ArrayBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    String queueName = "stream.orders";
    @PostConstruct // 在类初始化后执行，因为当这个类初始化好了之后，随时都是可能要执行的
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(()->{
            while (true){
                try {
                    // 如果消费者组不存在，创建消费者组
                    try {
                        // 尝试获取消费者组信息，如果不存在会抛出异常
                        stringRedisTemplate.opsForStream().groups(queueName);
                    } catch (Exception e) {
                        // 消费者组不存在，创建 stream.orders 流 + g1 消费组
                        stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
                    }

                    // 获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list==null || list.isEmpty()){
                        continue;
                    }
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常：",e);
                    handlePendingList();
                }
            }
        });
    }

    private void handlePendingList() {
        while (true){
            try {
                // 获取pending-list中的订单信息 xreadgroup group g1 c1 count 1 stream.orders >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                // 判断消息获取是否成功
                if (list==null || list.isEmpty()){
                    break;
                }
                // 解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 下单
                handleVoucherOrder(voucherOrder);
                // ACK确认消息
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                log.error("处理pending-list中的订单异常：",e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    /*// 声明阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    // 异步下单
    @PostConstruct // 在类初始化后执行，因为当这个类初始化好了之后，随时都是可能要执行的
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(()->{
            while (true){
                try {
                    // 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单 阻塞等待订单对象；拿到后调用 handleVoucherOrder()。
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常：",e);
                }
            }
        });
    }*/


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        Long userId = voucherOrder.getUserId();
        // 获取锁对象 加锁兜底
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断锁是否获取成功
        if (!isLock){
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }



    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("错误！优惠券不存在！");
        }
        // 判断是否是秒杀时间段
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之前 未开始
            return Result.fail("当前秒杀未开始！");
        }

        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之后 已结束
            return Result.fail("当前秒杀已结束！");
        }

        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行脚本 判断用户是否符合下单资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 判断脚本返回结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "不允许重复下单！");
        }
        /*// 保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);*/
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }



    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // 判断用户是否有订单，线程安全问题
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return ;
        }
        // 扣减库存
        // 确保原子操作 并发安全问题，超卖问题
        //boolean success = seckillVoucherMapper.deduct(voucherId);

        boolean success = seckillVoucherService.update().setSql("stock = stock-1").
                eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!success) {
            return;
        }
        // 创建订单

        save(voucherOrder);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("错误！优惠券不存在！");
        }
        // 判断是否是秒杀时间段
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之前 未开始
            return Result.fail("当前秒杀未开始！");
        }

        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之后 已结束
            return Result.fail("当前秒杀已结束！");
        }

        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 执行脚本 判断用户是否符合下单资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 判断脚本返回结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "不允许重复下单！");
        }
        // 保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("错误！优惠券不存在！");
        }
        // 判断是否是秒杀时间段
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之前 未开始
            return Result.fail("当前秒杀未开始！");
        }

        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之后 已结束
            return Result.fail("当前秒杀已结束！");
        }

        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            return Result.fail("当前库存不足1！");
        }
        Long userId = UserHolder.getUser().getId();
        // 获取锁
        //SimpleRedisLock lock = new SimpleRedisLock("userId:" + userId, stringRedisTemplate); //使用用户id作为key确保锁住同一用户
        //boolean isLock = lock.tryLock(600);
        RLock lock = redissonClient.getLock("userId:" + userId);  //获取锁（可重入），指定锁的名称
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 没拿到锁 失败
            return Result.fail("不允许重复下单！");
        }
        // 返回订单 如果在事务方法中加锁 会先释放锁才提交事务 出现线程安全问题
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId); // 使用代理对象 确保事务生效
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/

    /*@Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {

        // 判断用户是否有订单，线程安全问题
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("限购一次！");
        }
        // 扣减库存
        // 确保原子操作 并发安全问题，超卖问题
        boolean success = seckillVoucherMapper.deduct(voucherId);

        //boolean success = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!success) {
            return Result.fail("当前库存不足2！");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long voucherOrderId = redisIdWorker.nextId("order");

        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(voucherOrderId);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if (seckillVoucher == null) {
            return Result.fail("错误！优惠券不存在！");
        }
        // 判断是否是秒杀时间段
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之前 未开始
            return Result.fail("当前秒杀未开始！");
        }

        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 当前时间在秒杀时间段之后 已结束
            return Result.fail("当前未在秒杀时间段！");
        }

        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            return Result.fail("当前库存不足1！");
        }
        Long userId = UserHolder.getUser().getId();
        // 返回订单 如果在事务方法中加锁 会先释放锁才提交事务 出现线程安全问题
        synchronized (userId.toString().intern()) { // userId.toString().intern() 如果常量池中存在用户id拿出对应引用而不是创建新对象从而使用用户id作为锁对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId); // 使用代理对象 确保事务生效
        }
    }*/
}
