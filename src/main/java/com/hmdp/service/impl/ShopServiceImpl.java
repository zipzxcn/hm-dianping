package com.hmdp.service.impl;


import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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

    @Override
    public Result queryById(Long id) {

        // 1.根据id从redis中查找缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);  // 这里得到shopJson有三种情况，空串和空，有效对象

        // StringUtils.isBlank(shopJson) shopJson为null,""," "的情况下为true
        if (!StringUtils.isBlank(shopJson)) {
            // 1.1.缓存中存在有效对象，返回
            Shop cacheShop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(cacheShop);
        }

        // 空串""," "
        if (shopJson != null){
            return Result.fail("店铺不存在！");
        }
        // shopJson == null

        // 2.不存在,空，查询数据库
        Shop shop = this.getById(id);

        if (shop == null) {
            // 数据库中和redis缓存中不存在数据，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("商户不存在！");
        }

        // 3.存入redis,设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }

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
