package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;


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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，拦截
            return Result.fail("手机号格式不正确！");
        }
        // 2.创建验证码
        String code = RandomUtil.randomNumbers(6);

        // 3.将验证码存入session
        session.setAttribute("phone", code);
        // 4.发送验证码 TODO
        log.info("验证码发送成功:{}", code);
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
        Object o = session.getAttribute("phone");
        if (o == null || !String.valueOf(o).equals(loginForm.getCode())) {
            return Result.fail("验证码错误！");
        }
        // 3.根据手机号获取用户，判断是否为新用户
        User user = lambdaQuery().eq(User::getPhone, phone).one();

        if (user == null) {
            //保存用户
            user = createUserWithPhone(phone);
        }
        // 4.保存用户信息 TODO
        session.setAttribute("user", user);

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}
