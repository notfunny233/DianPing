package com.hmdp.Interceptor;
import com.hmdp.utils.UserHolder;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;



public class LoginInterceptor implements HandlerInterceptor {
    //登录拦截器
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从Threadlocal中获得用户,判断用户是否存在
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        //放行
        return true;
    }
    //token刷新拦截器
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
