/*
 * Copyright (c) 2014 Qunar.com. All Rights Reserved.
 */
package com.google.common.cache;

import javax.annotation.Nullable;

/**
 * @author tao.ke Date: 14-11-25 Time: 下午5:25
 * @version \$Id$
 */
public class TimeLocalCache<K, V> extends LocalCache<K, V> {

    final long expiredTimeNano;

    /**
     * 从builder中获取相应的配置参数。 Creates a new, empty map with the specified strategy, initial capacity and concurrency level.
     * 
     * @param builder
     * @param loader
     */

    TimeLocalCache(CacheBuilder<? super K, ? super V> builder, @Nullable CacheLoader<? super K, V> loader,
            long expiredTimeNano) {
        super(builder, loader);

        this.expiredTimeNano = expiredTimeNano;
    }

    @Override
    boolean isExpired(ReferenceEntry<K, V> entry, long now) {
        return now > expiredTimeNano;
    }
}
