package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * <p>
 *  服务实现类
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
        String key = "cache:shop:" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.isBlank(shopJson)){
            // 1.1.缓存中存在，返回
            log.debug("从缓存获取到数据: {}", shopJson);
            Shop cacheShop = JSONUtil.toBean(shopJson, Shop.class);
            log.debug("转换后的对象: {}", cacheShop);
            return Result.ok(cacheShop);
        }

        // 2.不存在,查询数据库
        Shop shop = this.getById(id);

        if (shop==null){
            return Result.fail("商户不存在！");
        }

        // 3.存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));

        return Result.ok(shop);
    }
}
