/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.cache;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtCompatible;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * 一个半持久化的从key到value映射。缓存entry使用get(Object, Callable)或者put(Object, Object)来手动添加，
 * 然后存储在缓存中，直到被驱赶出来或者手动置为无效。
 * 
 *接口的实现期待是线程安全的，并且可以被多线程安全地访问。
 *
 * 需要注意的是虽然这个类上的注解为beta，但是从使用者的角度来讲，它是不会变化的。换句话说，就是这些方法都是非beta
 * ，当然,新的方法会在任何时候加入到类中。
 *
 * @author Charles Fry
 * @since 10.0
 */
@Beta
@GwtCompatible
public interface Cache<K, V> {

  /**
   * 返回在cache和key关联的值。如果没有对应的值，则返回null。
   *
   * @since 11.0
   */
  @Nullable
  V getIfPresent(Object key);

  /**
   * 从缓存中返回对应的值，如果需要则从valueLoader中获取该值。和缓存关联的相状态直到加载完成才会观察到改变。
   * 这个方法提供了一个传统实现的简单替换：“如果缓存命中，则返回；否则创建，缓存然后返回”模式。
   * 
   * 需要注意的是，CacheLoader#load和 valueLoader 一定不可以返回null，它可以返回个非null值或者抛出异常。
   *
   * @throws ExecutionException if a checked exception was thrown while loading the value
   * @throws UncheckedExecutionException if an unchecked exception was thrown while loading the
   *     value
   * @throws ExecutionError if an error was thrown while loading the value
   *
   * @since 11.0
   */
  V get(K key, Callable<? extends V> valueLoader) throws ExecutionException;

  /**
   * 返回指定的keys在缓存中对应的values的map映射。这个返回map只会返回在缓存中已经存在的entrie..
   *
   * @since 11.0
   */
  ImmutableMap<K, V> getAllPresent(Iterable<?> keys);

  /**
   * Associates {@code value} with {@code key} in this cache. If the cache previously contained a
   * value associated with {@code key}, the old value is replaced by {@code value}.
   *
   * <p>Prefer {@link #get(Object, Callable)} when using the conventional "if cached, return;
   * otherwise create, cache and return" pattern.
   *
   * @since 11.0
   */
  void put(K key, V value);

  /**
   * Copies all of the mappings from the specified map to the cache. The effect of this call is
   * equivalent to that of calling {@code put(k, v)} on this map once for each mapping from key
   * {@code k} to value {@code v} in the specified map. The behavior of this operation is undefined
   * if the specified map is modified while the operation is in progress.
   *
   * @since 12.0
   */
  void putAll(Map<? extends K,? extends V> m);

  /**
   * Discards any cached value for key {@code key}.
   */
  void invalidate(Object key);

  /**
   * 丢弃指定keys对应的饿所有缓存值。
   *
   * @since 11.0
   */
  void invalidateAll(Iterable<?> keys);

  /**
   * Discards all entries in the cache.
   */
  void invalidateAll();

  /**
   * 返回缓存中entry的大概的数量。
   */
  long size();

  /**
   * 返回当前缓存累积统计的快照。所有统计被初始化为0，然后在缓生命周期中单调递增。
   *
   */
  CacheStats stats();

  /**
   * Returns a view of the entries stored in this cache as a thread-safe map. Modifications made to
   * the map directly affect the cache.
   *
   * <p>Iterators from the returned map are at least <i>weakly consistent</i>: they are safe for
   * concurrent use, but if the cache is modified (including by eviction) after the iterator is
   * created, it is undefined which of the changes (if any) will be reflected in that iterator.
   */
  ConcurrentMap<K, V> asMap();

  /**
   * 
   * Performs any pending maintenance operations needed by the cache. Exactly which activities are
   * performed -- if any -- is implementation-dependent.
   */
  void cleanUp();
}
