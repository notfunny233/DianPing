package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result SelectShopByID(Long id) {
        //纯缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁应对策略
        //Shop shop = queryWithMutex(id);
        //逻辑过期应对策略
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //使用工具类
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);


        if(shop == null) return Result.fail("店铺不存在");

        //返回商铺信息
        return Result.ok(shop);
    }
    @Override
    @Transactional
    public Result saveshop(Shop shop) {
        Long id = shop.getId();
        if(id == null) return Result.fail("店铺ID不能为空");
        //更新数据库
        save(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok("保存成功");
    }
    @Override
    @Transactional
    public Result updateshop(Shop shop) {
        Long id = shop.getId();
        if(id == null) return Result.fail("店铺ID不能为空");
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        //返回成功
        return Result.ok("更新成功");

    }

















    //---------------------------------------------------------------------------------------


/*    public Shop queryWithLogicalExpire(Long id){
        //从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否命中缓存
        if (StrUtil.isBlank(shopJson)) {
            //未命中直接返回
            return null;
        }
        //命中先把json反序列化为对象
        RedisDate<Shop> redisDate = JSONUtil.toBean(shopJson, RedisDate.class);
        Shop shop = redisDate.getData();
        LocalDateTime expireTime = redisDate.getExpireTime();
        //判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期返回商铺信息
            return shop;
        }
        //过期重建缓存
        //获取互斥锁
        boolean isLocked = TryLock(LOCK_SHOP_KEY + id);
        if(isLocked) {
            //抢到锁之后，重新查Redis，拿最新数据再校验一次！
            String latestJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            RedisDate<Shop> latestRedisData = JSONUtil.toBean(latestJson, RedisDate.class);
            LocalDateTime latestExpire = latestRedisData.getExpireTime();
            //只有最新缓存依旧过期，才重建
            if(!latestExpire.isAfter(LocalDateTime.now())){
            //开启独立线程创建缓存（调用线程池）
                CACHE_MAINTAIN_EXECUTOR.submit(() -> {
                try {
                    //这里的this表示调用本类的方法可写可不写
                    this.saveshop2Redis(id, LOCK_SHOP_TTL);
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
        return shop;
    }*/


   /* public Shop queryWithMutex(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);//从Redis查询缓存
        //判断是否命中缓存
        if (StrUtil.isNotBlank(shopJson)) {*//*isBlank = 是否为空（null/空串/全空白字符） isNotBlank = 不是空白，存在有效字符*//*
            //命中：
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是否为空值返回商铺信息
        if(shopJson != null){
            return null;
        }
        Shop shop = null;
        try {
            //未命中
            boolean isLocked = TryLock(LOCK_SHOP_KEY + id);//尝试获取互斥锁
            while (!isLocked) {//获取失败
                Thread.sleep(200); // 休眠200毫秒
                isLocked = TryLock(LOCK_SHOP_KEY + id);//重新访问
            }
            // 拿到锁之后，先再查一次缓存
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                //缓存已经被别的线程写入，直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if(shopJson != null){
                return null;
            }
            //重建缓存
            //根据ID查询
            shop = getById(id);
            if (shop == null) {
                //reids加入空值对象
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在
            //写入缓存
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(LOCK_SHOP_KEY + id);
        }
        return shop;
    }*/

    //带逻辑过期的缓存重建方法
    /*public void saveshop2Redis(Long id,Long time){
        //查询店铺
        Shop shop = getById(id);
        //封装信息
        RedisDate redisDate = new RedisDate();
        redisDate.setData(shop);
        redisDate.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisDate));
    }*/


}
