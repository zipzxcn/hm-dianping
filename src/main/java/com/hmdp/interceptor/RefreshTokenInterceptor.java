package com.hmdp.interceptor;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.获取session
        //User u = (User) request.getSession().getAttribute("user");
        // 跟据请求头获取token
        String token = request.getHeader("authorization");

        if (StrUtil.isBlank(token)) { //StrUtil.isBlank(token)检查字符串，非空且非纯空白，非制表符返回false,
            return true;
        }
        String key = RedisConstants.LOGIN_USER_KEY + token;
        // 根据token从redis中获取用户信息
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);

        if (map.isEmpty()) {
            return true;
        }
        UserDTO user = new UserDTO();
        Long id = Long.valueOf(map.get("id").toString());
        String nickName = map.get("nickName").toString();
        String icon = map.get("icon").toString();
        user.setId(id);
        user.setNickName(nickName);
        user.setIcon(icon);

        // 3.保存用户信息
        UserHolder.saveUser(user);

        // 刷新token有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);

        // 4.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        // 移除用户信息
        UserHolder.removeUser();
    }
}
