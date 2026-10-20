package com.hmdp.Interceptor;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class AllInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public AllInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的token，并基于token到redis中获取用户
        String token = request.getHeader("authorization");
        if (token == null) return true;
        Map user =stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);
        //判断用户是否存在
        if(user.isEmpty()) return true;
        //因为login中将对象转为Map后存储，所以取出数据还需要进行还原
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        //如果存在，存入Threadlocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }
}

