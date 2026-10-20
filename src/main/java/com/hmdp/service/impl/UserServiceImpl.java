package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;



@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        //检验是否为无效的手机号True表示无效
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式无效");
        }
        //生成验证码
        //hutool工具类
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis                                                            时间               单位
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        System.out.println("发送验证码：" + code + "给手机号：" + phone);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验验证码和手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式无效");
        }
        if (!loginForm.getCode().toString().equals(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone()))||loginForm.getCode()==null) {
            return Result.fail("验证码错误");
        }
        /*------------------------------------------------------------------------------------------------------------------*/
        //根据手机号查询用户
        //Mybatis_Plus写法  单表查询 select * from tb_user where phone = ?
        User user = query().eq("phone", loginForm.getPhone()).one();

        //没查到用户
        if(user==null) {
            //跳转到注册
            user = createUserWithPhone(loginForm.getPhone());
        }
        /*------------------------------------------------------------------------------------------------------------------*/
        //存入redis
        //存入整个对象会包含许多无用的敏感信息，所以创建DTO来存储关键信息
        //创建随机UUID的token     此处toString的参数表示是否取出-,true为去除可以节省空间
        String token = UUID.randomUUID().toString(true);
        //值先转化为UserDTO对象再变为HashMap
        UserDTO userDTO =BeanUtil.copyProperties(user, UserDTO.class);
        //转换为Map时要把value先变为String，才能符合stringRedisTemplate规范
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //通过putAll方法将userMap中的所有键值对put到redis中，不转Hashmap直接传对象value为userDTO
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY +token, userMap);
        //添加时效
        stringRedisTemplate.expire(LOGIN_USER_KEY +token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        /*------------------------------------------------------------------------------------------------------------------*/
        return Result.ok(token);//令牌返回给前端
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + phone);
        //Mybatis_Plus写法  通过save方法直接将实体对象存入数据库  insert into tb_user (phone, nick_name) values (?, ?)
        save(user);
        return user;
    }
}
