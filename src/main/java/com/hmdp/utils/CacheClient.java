package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;


@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_MAINTAIN_EXECUTOR = Executors.newFixedThreadPool(10);
    //缓存空值用的标记，库里查不到时写这个，挡住后续重复查库
    private static final String NULL_VALUE = "null";

    //构造器注入StringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //普通set方法：给key设置value + 真实TTL（Redis自带过期）
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //逻辑过期存入Redis，不给key设置Redis原生过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisDate redisData = new RedisDate();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //正确：JSONUtil.toJsonStr(redisData)
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //纯缓存穿透应对策略
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //从Redis查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中缓存
        if (StrUtil.isNotBlank(Json)) {
            //命中：
            return JSONUtil.toBean(Json, type);
        }
        //判断是否为空值返回商铺信息
        if(Json != null){
            return null;
        }
        //未命中
        //根据ID查询
        R r =dbFallback.apply(id);
        if (r == null) {
            //reids加入空值对象
            set( key , "", time, unit);
            return null;
        }
        //存在
        //写入缓存
        set(key, r, time, unit);
        return r;
    }
    //缓存击穿应对策略
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id ,Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //从Redis查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //缓存里没数据（比如刚被删掉），直接查库写回去
        //否则删一次缓存，这家店就永远查不到了
        if (StrUtil.isBlank(Json)) {
            return loadFromDb(key, id, dbFallback, time, unit);
        }
        //这是之前写进去的空值标记，说明库里也没有
        if (NULL_VALUE.equals(Json)) {
            return null;
        }
        //命中先把json反序列化为对象
        //注意：不能写成 toBean(Json, RedisDate.class) 就完事。
        //RedisDate 的泛型在运行时会被擦除，data 只能解析成 JSONObject，
        //再当作 Shop 用就抛 ClassCastException。所以先取出 Object，二次转换成目标类型。
        RedisDate<?> redisDate = JSONUtil.toBean(Json, RedisDate.class);
        R r = toTarget(redisDate.getData(), type);
        LocalDateTime expireTime = redisDate.getExpireTime();
        if (expireTime == null) {
            //缓存里不是逻辑过期结构，当成没命中，回源覆盖掉
            return loadFromDb(key, id, dbFallback, time, unit);
        }
        //判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期返回商铺信息
            return r;
        }
        //过期重建缓存
        //获取互斥锁
        boolean isLocked = TryLock(LOCK_SHOP_KEY + id);
        if(isLocked) {
            //抢到锁之后，重新查Redis，拿最新数据再校验一次！
            String latestJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(latestJson) || NULL_VALUE.equals(latestJson)) {
                //缓存已经被删掉了（比如刚更新过），不用重建，把锁还回去
                unLock(LOCK_SHOP_KEY + id);
                return r;
            }
            RedisDate<?> latestRedisData = JSONUtil.toBean(latestJson, RedisDate.class);
            LocalDateTime latestExpire = latestRedisData.getExpireTime();
            //只有最新缓存依旧过期，才重建
            if(latestExpire == null || !latestExpire.isAfter(LocalDateTime.now())){
                //开启独立线程创建缓存（调用线程池）
                CACHE_MAINTAIN_EXECUTOR.submit(() -> {
                    try {
                        //查询数据库
                        R r1 = dbFallback.apply(id);
                        //写入Redis
                        setWithLogicalExpire(key, r1, time, unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unLock(LOCK_SHOP_KEY + id);
                    }
                });
            }
        }
        //返回过期信息
        return r;
    }
    //缓存没数据时的兜底：查库，按逻辑过期格式写回去
    private <R, ID> R loadFromDb(String key, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        R db = dbFallback.apply(id);
        if (db == null) {
            //库里也没有，写空值标记，过一会儿自动消失
            stringRedisTemplate.opsForValue().set(key, NULL_VALUE, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写回缓存，同时带上逻辑过期时间
        setWithLogicalExpire(key, db, time, unit);
        return db;
    }

    //把反序列化出来的 data 再转成调用方要的类型
    //data 此时是 JSONObject，用 type 告诉工具类该转成什么
    private <R> R toTarget(Object data, Class<R> type) {
        if (data == null) {
            return null;
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
    }

    //获取互斥锁
    private boolean TryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    //释放互斥锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }



}
