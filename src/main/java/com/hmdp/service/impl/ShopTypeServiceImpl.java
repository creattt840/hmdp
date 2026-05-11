package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询和缓存商户类型信息
     * @return
     */
    @Override
    public Result querytypes() {
        //1.从redis中查找商户类型信息
        String key= RedisConstants.CACHE_SHOPTYPE_KEY;
        List<String> shoptypes = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.判断是否存在
        if(!shoptypes.isEmpty()) {
            //3.若存在，则直接返回
            List<ShopType> list= new ArrayList<>();
            for (String shoptype : shoptypes) {
                ShopType bean = JSONUtil.toBean(shoptype,ShopType.class);
                list.add(bean);
            }
            return Result.ok(list);
        }
        //4.若不存在，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //5.不存在，返回错误
        if (typeList.isEmpty()) {
            return Result.fail("商户类型查询错误！");
        }
        //6.存在写入redis
        List<String> jsonList = new ArrayList<>();
        for (ShopType shopType : typeList) {
            jsonList.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().rightPushAll(key,jsonList);
        //7.返回结果
        return Result.ok(typeList);
    }
}
