package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;

import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;

import java.util.UUID;
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
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，拦截
            return Result.fail("手机号格式不正确！");
        }
        // 2.创建验证码
        String code = RandomUtil.randomNumbers(6);

        // 3.将验证码存redis,有效期2分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4.发送验证码 TODO
        log.debug("验证码:{}，{}分钟内有效", code, RedisConstants.LOGIN_CODE_TTL);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，拦截
            return Result.fail("手机号格式不正确！");
        }
        // 2.校验验证码 TODO
        // 2.1.从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        String loginCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(loginCode)) {
            return Result.fail("验证码错误！");
        }
        /*Object o = session.getAttribute("phone");

        if (o == null || !String.valueOf(o).equals(loginCode)) {
            return Result.fail("验证码错误！");
        }*/
        // 3.根据手机号获取用户，判断是否为新用户
        User user = lambdaQuery().eq(User::getPhone, phone).one();

        if (user == null) {
            //保存用户
            user = createUserWithPhone(phone);
        }
        // 3.1.将User对象转换为UserDTO对象 TODO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 4.保存用户信息 TODO
        // session.setAttribute("user", user);
        // 4.1.将用户转为hashmap
        HashMap<String, Object> map = new HashMap<>();
        String id = String.valueOf(userDTO.getId());
        map.put("id", id);
        map.put("nickName", userDTO.getNickName());
        map.put("icon", userDTO.getIcon());
        // 4.2.生成token
        String token = UUID.randomUUID().toString();
        String key = RedisConstants.LOGIN_USER_KEY + token;
        // 4.3.将用户信息存入redis
        stringRedisTemplate.opsForHash().putAll(key, map);
        // 4.4.设置key的过期时间
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 4.5 使验证码失效
        String loginKey = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.delete(loginKey);
        // 5.将token返回前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}
