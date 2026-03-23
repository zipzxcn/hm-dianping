package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1.查询redis

        String key = "shopType";
        // 1.1.获取List长度
        Long length = stringRedisTemplate.opsForList().size(key);
        if (length != null && length > 0) {
            // 1.2.key存在，从redis中返回数据
            List<String> shopTypes = stringRedisTemplate.opsForList().range(key, 0, length);
            return Result.ok(shopTypes);
        }

        // 2.key不存在，查询数据库
        List<ShopType> shopTypeList = lambdaQuery().orderByAsc(ShopType::getSort).list();

        List<String> stringList = shopTypeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        // 3.将list存入redis
        stringRedisTemplate.opsForList().leftPushAll(key, stringList);

        return Result.ok(shopTypeList);
    }
}
