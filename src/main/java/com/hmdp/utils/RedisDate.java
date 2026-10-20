package com.hmdp.utils;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisDate<T> {
    private LocalDateTime expireTime;
    private T data;
}
