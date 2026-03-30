package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;

    private final RedisIdWorker redisIdWorker;

    private final SeckillVoucherMapper seckillVoucherMapper;
    private final StringRedisTemplate stringRedisTemplate;

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
            return Result.fail("当前未在秒杀时间段！");
        }

        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            return Result.fail("当前库存不足1！");
        }
        Long userId = UserHolder.getUser().getId();
        // 获取锁
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("userId:"+userId, stringRedisTemplate); //使用用户id作为key确保锁住同一用户
        boolean isLock = simpleRedisLock.tryLock(1000);
        if (!isLock) {
            // 没拿到锁 失败
            return Result.fail("不允许重复下单！");
        }
        // 返回订单 如果在事务方法中加锁 会先释放锁才提交事务 出现线程安全问题
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId); // 使用代理对象 确保事务生效
        } finally {
            // 释放锁
            simpleRedisLock.unlock();
        }

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

    @Transactional
    public Result createVoucherOrder(Long voucherId,Long userId){

        // 判断用户是否有订单，线程安全问题
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("限购一次！");
        }
        // 扣减库存
        // 确保原子操作 并发安全问题，超卖问题
        boolean success = seckillVoucherMapper.deduct(voucherId);

        /*seckillVoucherService.update().setSql("stock = stock-1").
                eq("voucher_id",voucherId).gt("stock",0).update();*/
        /*
        扣减发生线程安全问题
        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SeckillVoucher::getVoucherId, voucherId).set(SeckillVoucher::getStock, seckillVoucher.getStock()-1);
        boolean success = seckillVoucherService.update(wrapper);*/

        // boolean success = seckillVoucherService.update().setSql("stock= stock -1").eq("voucher_id", voucherId).update();

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
    }
}
