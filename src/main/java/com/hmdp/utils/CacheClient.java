package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意JAVA对象序列化为json存入redis中的String类型的key中，并设置TTL时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意JAVA对象序列化为json存入redis中的String类型的key中，并设置逻辑过期时间，用于处理缓存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogical(String key,Object value,Long time, TimeUnit unit){
        //利用RedisData设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存控制的方法解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,
                                         Long time,TimeUnit unit){
        //1.从redis查询缓存
        String key=keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)){
            //3.存在，直接返回
            return JSONUtil.toBean(Json,type);
        }
        //判断是否是空值，不是空的话，则表示这是一个缓存空对象
        if (Json!=null) {
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r==null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            //返回错误信息
            return null;
        }
        //6.存在写入redis
        this.set(key,r,time,unit);
        return r;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,
                                           Long time,TimeUnit unit){
        //1.从redis查询缓存
        String key=keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(Json)){
            //3.未命中，直接返回
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期，直接返回
            return r;
        }
        //5.2.已过期，需要缓存重建
        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean islock = trylock(lockKey);
        //6.2.判断是否获取锁成功
        if (islock) {
            //6.3.成功，开启线程，实现缓存重建
            CACHE_REBUILD_EXCUTOR.submit(()->{
                try {
                    //重建缓存,先查数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogical(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4.返回过期的信息
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXCUTOR= Executors.newFixedThreadPool(10);

    private boolean trylock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
