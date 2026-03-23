package com.hmdp.interceptor;
import com.hmdp.dto.UserDTO;

import com.hmdp.utils.UserHolder;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;



public class LoginInterceptor implements HandlerInterceptor {



    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 跟据请求头获取token
        UserDTO user = UserHolder.getUser();

        if (user == null ) {
            response.setStatus(401);
            return false;
        }

        return true;

    }
}
