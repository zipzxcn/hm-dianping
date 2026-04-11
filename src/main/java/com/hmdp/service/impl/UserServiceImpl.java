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
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;

import java.util.List;
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

        // 4.发送验证码
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
        // 2.校验验证码
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
        // 3.1.将User对象转换为UserDTO对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 4.保存用户信息
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
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.DAYS); // TODO token有效期修改
        // 4.5 使验证码失效
        String loginKey = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.delete(loginKey);
        // 5.将token返回前端
        return Result.ok(token);
    }

    /**
     * 用户签到
     *
     * @return ok
     */
    @Override
    public Result sign() {
        // 获取当前签到用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDate now = LocalDate.now();
        String dateFormat = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 获取当月天数
        int dayOfMonth = now.getDayOfMonth();
        // 生成Key
        String key = RedisConstants.USER_SIGN_KEY + userId + dateFormat;
        // 使用存入Redis的BitMap
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 签到统计
     *
     * @return int
     */
    @Override
    public Result signCount() {
        // 获取当前签到用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDate now = LocalDate.now();
        String dateFormat = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 获取当月天数
        int dayOfMonth = now.getDayOfMonth();
        // 生成Key
        String key = RedisConstants.USER_SIGN_KEY + userId + dateFormat;
        // 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202604 GET u7 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands
                .create()
                .get(BitFieldSubCommands
                        .BitFieldType.unsigned(dayOfMonth))
                .valueAt(0));
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        // 计数器
        int count = 0;
        // 循环遍历查找签到
        // 签到中断 跳出循环
        while ((num & 1) != 0) {
            count++;
            // 将num右移一位
            num >>>= 1;  // 等价于num = num >> 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        user.setCreateTime(LocalDateTime.now());
        this.save(user);
        return user;
    }


}
