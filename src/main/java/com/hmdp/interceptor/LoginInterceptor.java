package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;

import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.获取session
        User u = (User) request.getSession().getAttribute("user");

        // 2.判断user
        if (u == null) {
            response.setStatus(401);
            return false;
        }
        UserDTO user = new UserDTO();
        BeanUtil.copyProperties(u,user);
        // 3.保存用户信息
        UserHolder.saveUser(user);

        // 4.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        // 移除用户信息
        UserHolder.removeUser();
    }
}
