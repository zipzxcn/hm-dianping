package com.hmdp;


import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import io.reactivex.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

    // 导入店铺到redis geo
    @Test
    void loadShopData() {
        // 查询店铺信息
        List<Shop> list = shopService.list();
        // 把店铺分组，按照typeId一直的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取id类型
            Long typeId = entry.getKey();
            String key= RedisConstants.SHOP_GEO_KEY+typeId;
            // 获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            // 写入redis GEOADD key 经度 维度 member
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    // 保存优惠券
    @Test
    public void addSeckillVoucher() {
        Voucher voucher = getVoucher();

        // 保存优惠券
        voucherService.save(voucher);
        // 保存秒杀信息
        Long voucherId = voucher.getId();
        Integer voucherStock = voucher.getStock();
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucherId);
        seckillVoucher.setStock(voucherStock);
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 将优惠券库存信息保存到redis中
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + voucherId, voucherStock.toString());

    }

    // 创建优惠券
    @NonNull
    private static Voucher getVoucher() {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("100元代金券");
        voucher.setSubTitle("周一至周五均可使用");
        voucher.setRules("全场通用\\n无需预约\\n可无限叠加\\不兑现、不找零\\n仅限堂食");
        voucher.setPayValue(8000L);
        voucher.setActualValue(10000L);
        voucher.setType(1);
        voucher.setStock(50);
        LocalDateTime begin = LocalDateTime.parse("2026-01-26T10:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-05-26T23:00:00");
        voucher.setBeginTime(begin);
        voucher.setEndTime(end
        );
        return voucher;
    }
}
