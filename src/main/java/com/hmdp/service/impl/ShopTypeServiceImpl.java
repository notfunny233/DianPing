package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result TypeList() {
        String typeKey= CACHE_TYPE_KEY;
        //从redis中查询元素个数
        String typeJson = stringRedisTemplate.opsForValue().get(typeKey);
        //redis存在数据
        if (typeJson!=null){
            // JSON数组 -> List<ShopType>，数组元素是ShopType对象
            List<ShopType> typeJsonList = JSONUtil.toList(typeJson, ShopType.class);
            //提前
            return Result.ok(typeJsonList);
        }
        //redis不存在数据 查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList.isEmpty()){
            // 数据库无数据，缓存空数组，防止缓存穿透
            stringRedisTemplate.opsForValue().set(typeKey, "[]", 5, TimeUnit.MINUTES);
            //数据库不存在数据
            return Result.fail("发生错误");
        }
        //数据库存在数据 写入redis
        String JsonArray = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(CACHE_TYPE_KEY, JsonArray,30, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
