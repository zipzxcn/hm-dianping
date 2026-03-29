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
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
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


    @Transactional
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
        // 扣减库存
        // 确保原子操作 并发安全问题，超卖问题
        boolean success = seckillVoucherMapper.updateById(voucherId);
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
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 返回订单
        return Result.ok(voucherOrderId);
    }
}
