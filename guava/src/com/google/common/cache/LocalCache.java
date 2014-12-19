/*
 * Copyright (C) 2009 The Guava Authors Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the
 * License.
 */

package com.google.common.cache;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.cache.CacheBuilder.NULL_TICKER;
import static com.google.common.cache.CacheBuilder.UNSET_INT;
import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder.NullListener;
import com.google.common.cache.CacheBuilder.OneWeigher;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.CacheLoader.UnsupportedLoadingOperationException;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * 基于concurrentHashMap的LocalCache类，服务于CacheBuilder的内部缓存实现。
 * 
 * @author Charles Fry
 * @author Bob Lee ({@code com.google.common.collect.MapMaker})
 * @author Doug Lea ({@code ConcurrentHashMap})
 */
@GwtCompatible(emulated = true)
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    /**
     * 其实，LocalCache的并发策略和concurrentHashMap的并发策略是一致的，也是根据分段锁来提高并发能力,分段锁可以很好的保证 并发读的效率。因此，该map支持非阻塞读和不同段之间并发写。
     * 
     * 如果最大的大小指定了，那么基于段来执行操作是最好的。使用页面替换算法来决定当map大小超过指定值时，哪些entries需要被驱赶出去。
     * 
     * 页面替换算法的数据结构保持Map临时一致性。对一个segment写排序是一致的。对map进行更新和读不能直接 反应在数据结构上。 这些数据结构被lock保护，批量操作而避免锁争抢。在线程之间传播的批量操作导致分摊成本
     * 比不强制大小限制的操作要稍微高一点。
     * 
     * 使用LRU页面替换算法，原因是它的简单，高的命中率，以及O(1)的时间复杂度。需要说明的是， LRU算法是 基于页面而不是全局实现的，所以可能在命中率上不如全局LRU算法，但是应该基本相似。
     * 
     */

    // Constants

    /**
     * The maximum capacity, used if a higher value is implicitly specified by either of the constructors with
     * arguments. MUST be a power of two <= 1<<30 to ensure that entries are indexable using ints.
     */
    static final int MAXIMUM_CAPACITY = 1 << 30; // 最大容量

    /** The maximum number of segments to allow; used to bound constructor arguments. */
    static final int MAX_SEGMENTS = 1 << 16; // 最大段（桶）数 slightly conservative

    /** Number of (unsynchronized) retries in the containsValue method. */
    static final int CONTAINS_VALUE_RETRIES = 3; // containsValue 最大重试次数

    /**
     * 每个段可以被buffer的缓存访问操作的数量，每个段在缓存按照内容更新次序排序的最近之前。 这个通过记录读记录和延迟锁获得直到设置的阈值被超过或者一个变化发生，避免锁竞争。
     * 
     * <p>
     * This must be a (2^n)-1 as it is used as a mask.
     */
    static final int DRAIN_THRESHOLD = 0x3F;// n=8,--63

    /**
     * 在单个cleanup运行里，消耗的最大entries数量。该数值是根据经验来设置的。
     */
    // TODO(fry): empirically optimize this
    static final int DRAIN_MAX = 16;

    // Fields

    static final Logger logger = Logger.getLogger(LocalCache.class.getName());

    static final ListeningExecutorService sameThreadExecutor = MoreExecutors.sameThreadExecutor();

    /**
     * Mask value for indexing into segments. The upper bits of a key's hash code are used to choose the segment.
     */
    final int segmentMask;// 一个key的hashcode的高位表示选择的段segment

    /**
     * Shift value for indexing within segments. Helps prevent entries that end up in the same segment from also ending
     * up in the same bucket.
     */
    final int segmentShift;// 在一个段里面的偏移量

    /** The segments, each of which is a specialized hash table. */
    final Segment<K, V>[] segments;

    /** The concurrency level. */
    final int concurrencyLevel; // 并发水平

    /** Strategy for comparing keys. */
    final Equivalence<Object> keyEquivalence;// 比较key的策略

    /** Strategy for comparing values. */
    final Equivalence<Object> valueEquivalence;// 比较值的策略

    /** Strategy for referencing keys. */
    final Strength keyStrength;// key引用的强度，SoftReference，WeakReference等

    /** Strategy for referencing values. */
    final Strength valueStrength;

    /** The maximum weight of this map. UNSET_INT if there is no maximum. */
    final long maxWeight;// map的最大权重

    /** Weigher to weigh cache entries. */
    final Weigher<K, V> weigher;// 每个节点entries的权重

    final long expireAfterAccessNanos;// 在最近一次访问后，map保存该节点entry多长时间

    final long expireAfterWriteNanos;// 在最近一次写后，map保存该节点entry多长时间

    final long refreshNanos;// 成为刷新候选者的时间，在最后依次写入之后

    // TODO(fry): define a new type which creates event objects and automates the clear logic
    final Queue<RemovalNotification<K, V>> removalNotificationQueue;// 等待removal listener监听器消费的entries节点群

    /**
     * A listener that is invoked when an entry is removed due to expiration or garbage collection of soft/weak entries.
     */
    final RemovalListener<K, V> removalListener; // 当一个节点元素因为过期或者软引用的垃圾回收时，调用一个监听器。

    /** Measures time in a testable way. */
    final Ticker ticker;// 时间源

    /** Factory used to create new entries. */
    // 一枚举类，内部包含抽象方法，在每个enum元素内部，实现这个抽象方法
    final EntryFactory entryFactory; // 工厂类创建新的节点

    /**
     * Accumulates global cache statistics. Note that there are also per-segments stats counters which must be
     * aggregated to obtain a global stats view.
     */
    final StatsCounter globalStatsCounter; // 全局缓存统计

    /**
     * The default cache loader to use on loading operations.
     */
    @Nullable
    final CacheLoader<? super K, V> defaultLoader;

    /**
     * 从builder中获取相应的配置参数。 Creates a new, empty map with the specified strategy, initial capacity and concurrency level.
     */
    LocalCache(CacheBuilder<? super K, ? super V> builder, @Nullable CacheLoader<? super K, V> loader) {
        concurrencyLevel = Math.min(builder.getConcurrencyLevel(), MAX_SEGMENTS);

        keyStrength = builder.getKeyStrength();
        valueStrength = builder.getValueStrength();

        keyEquivalence = builder.getKeyEquivalence();
        valueEquivalence = builder.getValueEquivalence();

        maxWeight = builder.getMaximumWeight();
        weigher = builder.getWeigher();
        expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
        expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
        refreshNanos = builder.getRefreshNanos();

        removalListener = builder.getRemovalListener();
        removalNotificationQueue = (removalListener == NullListener.INSTANCE) ? LocalCache
                .<RemovalNotification<K, V>> discardingQueue() : new ConcurrentLinkedQueue<RemovalNotification<K, V>>();

        ticker = builder.getTicker(recordsTime());
        entryFactory = EntryFactory.getFactory(keyStrength, usesAccessEntries(), usesWriteEntries());
        globalStatsCounter = builder.getStatsCounterSupplier().get();
        defaultLoader = loader;

        int initialCapacity = Math.min(builder.getInitialCapacity(), MAXIMUM_CAPACITY);
        if (evictsBySize() && !customWeigher()) {
            initialCapacity = Math.min(initialCapacity, (int) maxWeight);
        }

        // 找到最小2的次数的段数量，
        // Find the lowest power-of-two segmentCount that exceeds concurrencyLevel, unless
        // maximumSize/Weight is specified in which case ensure that each segment gets at least 10
        // entries. The special casing for size-based eviction is only necessary because that eviction
        // happens per segment instead of globally, so too many segments compared to the maximum size
        // will result in random eviction behavior.
        int segmentShift = 0;
        int segmentCount = 1;
        while (segmentCount < concurrencyLevel && (!evictsBySize() || segmentCount * 20 <= maxWeight)) {
            ++segmentShift;
            segmentCount <<= 1;
        }
        this.segmentShift = 32 - segmentShift;
        segmentMask = segmentCount - 1;

        this.segments = newSegmentArray(segmentCount);

        int segmentCapacity = initialCapacity / segmentCount;
        if (segmentCapacity * segmentCount < initialCapacity) {
            ++segmentCapacity;
        }

        int segmentSize = 1;
        while (segmentSize < segmentCapacity) {
            segmentSize <<= 1;
        }

        if (evictsBySize()) {
            // Ensure sum of segment max weights = overall max weights
            long maxSegmentWeight = maxWeight / segmentCount + 1;
            long remainder = maxWeight % segmentCount;
            for (int i = 0; i < this.segments.length; ++i) {
                if (i == remainder) {
                    maxSegmentWeight--;
                }
                this.segments[i] = createSegment(segmentSize, maxSegmentWeight, builder.getStatsCounterSupplier().get());
            }
        } else {
            for (int i = 0; i < this.segments.length; ++i) {
                this.segments[i] = createSegment(segmentSize, UNSET_INT, builder.getStatsCounterSupplier().get());
            }
        }
    }

    boolean evictsBySize() {
        return maxWeight >= 0;
    }

    boolean customWeigher() {
        return weigher != OneWeigher.INSTANCE;
    }

    boolean expires() {
        return expiresAfterWrite() || expiresAfterAccess();
    }

    boolean expiresAfterWrite() {
        return expireAfterWriteNanos > 0;
    }

    // 表示设置了access访问超时参数设置
    boolean expiresAfterAccess() {
        return expireAfterAccessNanos > 0;
    }

    boolean refreshes() {
        return refreshNanos > 0;
    }

    boolean usesAccessQueue() {
        return expiresAfterAccess() || evictsBySize();
    }

    boolean usesWriteQueue() {
        return expiresAfterWrite();
    }

    boolean recordsWrite() {
        return expiresAfterWrite() || refreshes();
    }

    boolean recordsAccess() {
        return expiresAfterAccess();
    }

    boolean recordsTime() {
        return recordsWrite() || recordsAccess();
    }

    boolean usesWriteEntries() {
        return usesWriteQueue() || recordsWrite();
    }

    boolean usesAccessEntries() {
        return usesAccessQueue() || recordsAccess();
    }

    boolean usesKeyReferences() {
        return keyStrength != Strength.STRONG;
    }

    boolean usesValueReferences() {
        return valueStrength != Strength.STRONG;
    }

    enum Strength {
        /*
         * TODO(kevinb): If we strongly reference the value and aren't loading, we needn't wrap the value. This could
         * save ~8 bytes per entry.
         */

        STRONG {
            @Override
            <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> entry, V value,
                    int weight) {
                return (weight == 1) ? new StrongValueReference<K, V>(value) : new WeightedStrongValueReference<K, V>(
                        value, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.equals();
            }// 会判断是否相等
        },

        SOFT {
            @Override
            <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> entry, V value,
                    int weight) {
                return (weight == 1) ? new SoftValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                        : new WeightedSoftValueReference<K, V>(segment.valueReferenceQueue, value, entry, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }// 默认相等时直接判断为false
        },

        WEAK {
            @Override
            <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> entry, V value,
                    int weight) {
                return (weight == 1) ? new WeakValueReference<K, V>(segment.valueReferenceQueue, value, entry)
                        : new WeightedWeakValueReference<K, V>(segment.valueReferenceQueue, value, entry, weight);
            }

            @Override
            Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }// 默认相等时直接判断为false
        };

        /**
         * 根据给定的value 强度来创建该value 的引用
         */
        abstract <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> entry, V value,
                int weight);

        /**
         * Returns the default equivalence strategy used to compare and hash keys or values referenced at this strength.
         * This strategy will be used unless the user explicitly specifies an alternate strategy.
         */
        abstract Equivalence<Object> defaultEquivalence();
    }

    /**
     * Creates new entries.
     */
    enum EntryFactory {
        STRONG {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new StrongEntry<K, V>(key, hash, next);
            }
        },
        STRONG_ACCESS {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new StrongAccessEntry<K, V>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                return newEntry;
            }
        },
        STRONG_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new StrongWriteEntry<K, V>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },
        STRONG_ACCESS_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new StrongAccessWriteEntry<K, V>(key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },

        WEAK {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new WeakEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
            }
        },
        WEAK_ACCESS {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new WeakAccessEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                return newEntry;
            }
        },
        WEAK_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new WeakWriteEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        },
        WEAK_ACCESS_WRITE {
            @Override
            <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                    @Nullable ReferenceEntry<K, V> next) {
                return new WeakAccessWriteEntry<K, V>(segment.keyReferenceQueue, key, hash, next);
            }

            @Override
            <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                    ReferenceEntry<K, V> newNext) {
                ReferenceEntry<K, V> newEntry = super.copyEntry(segment, original, newNext);
                copyAccessEntry(original, newEntry);
                copyWriteEntry(original, newEntry);
                return newEntry;
            }
        };

        /**
         * Masks used to compute indices in the following table.
         */
        static final int ACCESS_MASK = 1;
        static final int WRITE_MASK = 2;
        static final int WEAK_MASK = 4;

        /**
         * Look-up table for factories.
         */
        static final EntryFactory[] factories = { STRONG, STRONG_ACCESS, STRONG_WRITE, STRONG_ACCESS_WRITE, WEAK,
                WEAK_ACCESS, WEAK_WRITE, WEAK_ACCESS_WRITE, };

        static EntryFactory getFactory(Strength keyStrength, boolean usesAccessQueue, boolean usesWriteQueue) {
            int flags = ((keyStrength == Strength.WEAK) ? WEAK_MASK : 0) | (usesAccessQueue ? ACCESS_MASK : 0)
                    | (usesWriteQueue ? WRITE_MASK : 0);
            return factories[flags];// 定位组合配置
        }

        /**
         * Creates a new entry.
         * 
         * @param segment to create the entry for
         * @param key of the entry
         * @param hash of the key
         * @param next entry in the same bucket
         */
        abstract <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K key, int hash,
                @Nullable ReferenceEntry<K, V> next);

        /**
         * Copies an entry, assigning it a new {@code next} entry.
         * 
         * @param original the entry to copy
         * @param newNext entry in the same bucket
         */
        @GuardedBy("Segment.this")
        <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> original,
                ReferenceEntry<K, V> newNext) {
            return newEntry(segment, original.getKey(), original.getHash(), newNext);
        }

        @GuardedBy("Segment.this")
        <K, V> void copyAccessEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
            // TODO(fry): when we link values instead of entries this method can go
            // away, as can connectAccessOrder, nullifyAccessOrder.
            newEntry.setAccessTime(original.getAccessTime());

            connectAccessOrder(original.getPreviousInAccessQueue(), newEntry);
            connectAccessOrder(newEntry, original.getNextInAccessQueue());

            nullifyAccessOrder(original);
        }

        @GuardedBy("Segment.this")
        <K, V> void copyWriteEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newEntry) {
            // TODO(fry): when we link values instead of entries this method can go
            // away, as can connectWriteOrder, nullifyWriteOrder.
            newEntry.setWriteTime(original.getWriteTime());

            connectWriteOrder(original.getPreviousInWriteQueue(), newEntry);
            connectWriteOrder(newEntry, original.getNextInWriteQueue());

            nullifyWriteOrder(original);
        }
    }

    /**
     * A reference to a value.
     */
    interface ValueReference<K, V> {
        /**
         * Returns the value. Does not block or throw exceptions.
         */
        @Nullable
        V get();

        /**
         * Waits for a value that may still be loading. Unlike get(), this method can block (in the case of
         * FutureValueReference).
         * 
         * @throws ExecutionException if the loading thread throws an exception
         * @throws ExecutionError if the loading thread throws an error
         */
        V waitForValue() throws ExecutionException;

        /**
         * Returns the weight of this entry. This is assumed to be static between calls to setValue.
         */
        int getWeight();

        /**
         * Returns the entry associated with this value reference, or {@code null} if this value reference is
         * independent of any entry.
         */
        @Nullable
        ReferenceEntry<K, V> getEntry();

        /**
         * Creates a copy of this reference for the given entry.
         * 
         * <p>
         * {@code value} may be null only for a loading reference.
         */
        ValueReference<K, V> copyFor(ReferenceQueue<V> queue, @Nullable V value, ReferenceEntry<K, V> entry);

        /**
         * Notifify pending loads that a new value was set. This is only relevant to loading value references.
         */
        void notifyNewValue(@Nullable V newValue);

        /**
         * 当一个新的value正在被加载的时候，返回true。不管是否已经有存在的值。这里加锁方法返回的值对于给定的ValueReference实例来说是常量。
         * 
         */
        boolean isLoading();

        /**
         * 返回true，如果该reference包含一个活跃的值,意味着在cache里仍然有一个值存在。活跃的值包含：cache查找返回的，等待被移除的要被驱赶的值； 非激活的包含：正在加载的值，
         */
        boolean isActive();
    }

    /**
     * Placeholder. Indicates that the value hasn't been set yet.
     */
    static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
        @Override
        public Object get() {
            return null;
        }

        @Override
        public int getWeight() {
            return 0;
        }

        @Override
        public ReferenceEntry<Object, Object> getEntry() {
            return null;
        }

        @Override
        public ValueReference<Object, Object> copyFor(ReferenceQueue<Object> queue, @Nullable Object value,
                ReferenceEntry<Object, Object> entry) {
            return this;
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public Object waitForValue() {
            return null;
        }

        @Override
        public void notifyNewValue(Object newValue) {
        }
    };

    /**
     * Singleton placeholder that indicates a value is being loaded.
     */
    @SuppressWarnings("unchecked")
    // impl never uses a parameter or returns any non-null value
    static <K, V> ValueReference<K, V> unset() {
        return (ValueReference<K, V>) UNSET;
    }

    /**
     * An entry in a reference map.
     * 
     * Entries in the map can be in the following states:
     * 
     * Valid: - Live: valid key/value are set - Loading: loading is pending
     * 
     * Invalid: - Expired: time expired (key/value may still be set) - Collected: key/value was partially collected, but
     * not yet cleaned up - Unset: marked as unset, awaiting cleanup or reuse
     */
    interface ReferenceEntry<K, V> {
        /**
         * Returns the value reference from this entry.
         */
        ValueReference<K, V> getValueReference();

        /**
         * Sets the value reference for this entry.
         */
        void setValueReference(ValueReference<K, V> valueReference);

        /**
         * Returns the next entry in the chain.
         */
        @Nullable
        ReferenceEntry<K, V> getNext();

        /**
         * Returns the entry's hash.
         */
        int getHash();

        /**
         * Returns the key for this entry.
         */
        @Nullable
        K getKey();

        /*
         * Used by entries that use access order. Access entries are maintained in a doubly-linked list. New entries are
         * added at the tail of the list at write time; stale entries are expired from the head of the list.
         */

        /**
         * Returns the time that this entry was last accessed, in ns.
         */
        long getAccessTime();

        /**
         * Sets the entry access time in ns.
         */
        void setAccessTime(long time);

        /**
         * Returns the next entry in the access queue.
         */
        ReferenceEntry<K, V> getNextInAccessQueue();

        /**
         * Sets the next entry in the access queue.
         */
        void setNextInAccessQueue(ReferenceEntry<K, V> next);

        /**
         * Returns the previous entry in the access queue.
         */
        ReferenceEntry<K, V> getPreviousInAccessQueue();

        /**
         * Sets the previous entry in the access queue.
         */
        void setPreviousInAccessQueue(ReferenceEntry<K, V> previous);

        /*
         * Implemented by entries that use write order. Write entries are maintained in a doubly-linked list. New
         * entries are added at the tail of the list at write time and stale entries are expired from the head of the
         * list.
         */

        /**
         * Returns the time that this entry was last written, in ns.
         */
        long getWriteTime();

        /**
         * Sets the entry write time in ns.
         */
        void setWriteTime(long time);

        /**
         * Returns the next entry in the write queue.
         */
        ReferenceEntry<K, V> getNextInWriteQueue();

        /**
         * Sets the next entry in the write queue.
         */
        void setNextInWriteQueue(ReferenceEntry<K, V> next);

        /**
         * Returns the previous entry in the write queue.
         */
        ReferenceEntry<K, V> getPreviousInWriteQueue();

        /**
         * Sets the previous entry in the write queue.
         */
        void setPreviousInWriteQueue(ReferenceEntry<K, V> previous);
    }

    private enum NullEntry implements ReferenceEntry<Object, Object> {
        INSTANCE;

        @Override
        public ValueReference<Object, Object> getValueReference() {
            return null;
        }

        @Override
        public void setValueReference(ValueReference<Object, Object> valueReference) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNext() {
            return null;
        }

        @Override
        public int getHash() {
            return 0;
        }

        @Override
        public Object getKey() {
            return null;
        }

        @Override
        public long getAccessTime() {
            return 0;
        }

        @Override
        public void setAccessTime(long time) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNextInAccessQueue() {
            return this;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<Object, Object> next) {
        }

        @Override
        public ReferenceEntry<Object, Object> getPreviousInAccessQueue() {
            return this;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<Object, Object> previous) {
        }

        @Override
        public long getWriteTime() {
            return 0;
        }

        @Override
        public void setWriteTime(long time) {
        }

        @Override
        public ReferenceEntry<Object, Object> getNextInWriteQueue() {
            return this;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<Object, Object> next) {
        }

        @Override
        public ReferenceEntry<Object, Object> getPreviousInWriteQueue() {
            return this;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<Object, Object> previous) {
        }
    }

    static abstract class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
        @Override
        public ValueReference<K, V> getValueReference() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getHash() {
            throw new UnsupportedOperationException();
        }

        @Override
        public K getKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setWriteTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    // impl never uses a parameter or returns any non-null value
    static <K, V> ReferenceEntry<K, V> nullEntry() {
        return (ReferenceEntry<K, V>) NullEntry.INSTANCE;
    }

    // 当我们在cache中没有使用write和access的相关特性，则使用该queue队列，当然主要是为了节约内存
    static final Queue<? extends Object> DISCARDING_QUEUE = new AbstractQueue<Object>() {
        @Override
        public boolean offer(Object o) {
            return true;
        }

        @Override
        public Object peek() {
            return null;
        }

        @Override
        public Object poll() {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Iterator<Object> iterator() {
            return ImmutableSet.of().iterator();
        }
    };

    /**
     * Queue that discards all elements.
     */
    @SuppressWarnings("unchecked")
    // impl never uses a parameter or returns any non-null value
    static <E> Queue<E> discardingQueue() {
        return (Queue) DISCARDING_QUEUE;
    }

    /*
     * Note: All of this duplicate code sucks, but it saves a lot of memory. If only Java had mixins! To maintain this
     * code, make a change for the strong reference type. Then, cut and paste, and replace "Strong" with "Soft" or
     * "Weak" within the pasted text. The primary difference is that strong entries store the key reference directly
     * while soft and weak entries delegate to their respective superclasses.
     */

    /**
     * Used for strongly-referenced keys.
     */
    static class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {

        // 这里其实就是strong引用的map entry，包括key，hash，value，和next（因为其实现是挂在segment上的一个链表）

        final K key;

        StrongEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }

        @Override
        public K getKey() {
            return this.key;
        }

        // The code below is exactly the same for each entry type.

        final int hash;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = unset();

        @Override
        public ValueReference<K, V> getValueReference() {
            return valueReference;
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            this.valueReference = valueReference;
        }

        @Override
        public int getHash() {
            return hash;
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            return next;
        }
    }

    // 在对每个节点的更新操作都会将该节点重新链到write链和access链末尾，并且更新其writeTime和accessTime字段，
    // 而没找到一个节点，都会将该节点重新链到access链末尾，并更新其accessTime字段
    static final class StrongAccessEntry<K, V> extends StrongEntry<K, V> {
        StrongAccessEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        // 既然是访问的链表，就肯定需要记录access的时间了
        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        // 双向列表，和map中不一样，这里包含pre和next
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }
    }

    static final class StrongWriteEntry<K, V> extends StrongEntry<K, V> {
        StrongWriteEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    static final class StrongAccessWriteEntry<K, V> extends StrongEntry<K, V> {
        StrongAccessWriteEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    /**
     * Used for weakly-referenced keys.
     */
    static class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
        WeakEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(key, queue);
            this.hash = hash;
            this.next = next;
        }

        @Override
        public K getKey() {
            return get();
        }

        /*
         * It'd be nice to get these for free from AbstractReferenceEntry, but we're already extending WeakReference<K>.
         */

        // null access

        @Override
        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAccessTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        // null write

        @Override
        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setWriteTime(long time) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            throw new UnsupportedOperationException();
        }

        // The code below is exactly the same for each entry type.

        final int hash;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = unset();

        @Override
        public ValueReference<K, V> getValueReference() {
            return valueReference;
        }

        @Override
        public void setValueReference(ValueReference<K, V> valueReference) {
            this.valueReference = valueReference;
        }

        @Override
        public int getHash() {
            return hash;
        }

        @Override
        public ReferenceEntry<K, V> getNext() {
            return next;
        }
    }

    static final class WeakAccessEntry<K, V> extends WeakEntry<K, V> {
        WeakAccessEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }
    }

    static final class WeakWriteEntry<K, V> extends WeakEntry<K, V> {
        WeakWriteEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    static final class WeakAccessWriteEntry<K, V> extends WeakEntry<K, V> {
        WeakAccessWriteEntry(ReferenceQueue<K> queue, K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            super(queue, key, hash, next);
        }

        // The code below is exactly the same for each access entry type.

        volatile long accessTime = Long.MAX_VALUE;

        @Override
        public long getAccessTime() {
            return accessTime;
        }

        @Override
        public void setAccessTime(long time) {
            this.accessTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return nextAccess;
        }

        @Override
        public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
            this.nextAccess = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return previousAccess;
        }

        @Override
        public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
            this.previousAccess = previous;
        }

        // The code below is exactly the same for each write entry type.

        volatile long writeTime = Long.MAX_VALUE;

        @Override
        public long getWriteTime() {
            return writeTime;
        }

        @Override
        public void setWriteTime(long time) {
            this.writeTime = time;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return nextWrite;
        }

        @Override
        public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
            this.nextWrite = next;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = nullEntry();

        @Override
        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return previousWrite;
        }

        @Override
        public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
            this.previousWrite = previous;
        }
    }

    /**
     * References a weak value.
     */
    static class WeakValueReference<K, V> extends WeakReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        WeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
            super(referent, queue);
            this.entry = entry;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return entry;
        }

        @Override
        public void notifyNewValue(V newValue) {
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeakValueReference<K, V>(queue, value, entry);
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }
    }

    /**
     * References a soft value.
     */
    static class SoftValueReference<K, V> extends SoftReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        SoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry) {
            super(referent, queue);
            this.entry = entry;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return entry;
        }

        @Override
        public void notifyNewValue(V newValue) {
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new SoftValueReference<K, V>(queue, value, entry);
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }
    }

    /**
     * References a strong value.
     */
    static class StrongValueReference<K, V> implements ValueReference<K, V> {
        final V referent;

        StrongValueReference(V referent) {
            this.referent = referent;
        }

        @Override
        public V get() {
            return referent;
        }

        @Override
        public int getWeight() {
            return 1;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return this;
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public V waitForValue() {
            return get();
        }

        @Override
        public void notifyNewValue(V newValue) {
        }
    }

    /**
     * References a weak value.
     */
    static final class WeightedWeakValueReference<K, V> extends WeakValueReference<K, V> {
        final int weight;

        WeightedWeakValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry, int weight) {
            super(queue, referent, entry);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeightedWeakValueReference<K, V>(queue, value, entry, weight);
        }
    }

    /**
     * References a soft value.
     */
    static final class WeightedSoftValueReference<K, V> extends SoftValueReference<K, V> {
        final int weight;

        WeightedSoftValueReference(ReferenceQueue<V> queue, V referent, ReferenceEntry<K, V> entry, int weight) {
            super(queue, referent, entry);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, V value, ReferenceEntry<K, V> entry) {
            return new WeightedSoftValueReference<K, V>(queue, value, entry, weight);
        }

    }

    /**
     * References a strong value.
     */
    static final class WeightedStrongValueReference<K, V> extends StrongValueReference<K, V> {
        final int weight;

        WeightedStrongValueReference(V referent, int weight) {
            super(referent);
            this.weight = weight;
        }

        @Override
        public int getWeight() {
            return weight;
        }
    }

    /**
     * Applies a supplemental hash function to a given hash code, which defends against poor quality hash functions.
     * This is critical when the concurrent hash map uses power-of-two length hash tables, that otherwise encounter
     * collisions for hash codes that do not differ in lower or upper bits.
     * 
     * @param h hash code
     */
    static int rehash(int h) {
        // Spread bits to regularize both segment and index locations,
        // using variant of single-word Wang/Jenkins hash.
        // TODO(kevinb): use Hashing/move this to Hashing?
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        return h ^ (h >>> 16);
    }

    /**
     * This method is a convenience for testing. Code should call {@link Segment#newEntry} directly.
     */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
        return segmentFor(hash).newEntry(key, hash, next);
    }

    /**
     * This method is a convenience for testing. Code should call {@link Segment#copyEntry} directly.
     */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
        int hash = original.getHash();
        return segmentFor(hash).copyEntry(original, newNext);
    }

    /**
     * This method is a convenience for testing. Code should call {@link Segment#setValue} instead.
     */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    ValueReference<K, V> newValueReference(ReferenceEntry<K, V> entry, V value, int weight) {
        int hash = entry.getHash();
        return valueStrength.referenceValue(segmentFor(hash), entry, checkNotNull(value), weight);
    }

    int hash(@Nullable Object key) {
        int h = keyEquivalence.hash(key);
        return rehash(h);
    }

    void reclaimValue(ValueReference<K, V> valueReference) {
        ReferenceEntry<K, V> entry = valueReference.getEntry();
        int hash = entry.getHash();
        segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference);
    }

    void reclaimKey(ReferenceEntry<K, V> entry) {
        int hash = entry.getHash();
        segmentFor(hash).reclaimKey(entry, hash);
    }

    /**
     * This method is a convenience for testing. Code should call {@link Segment#getLiveValue} instead.
     */
    @VisibleForTesting
    boolean isLive(ReferenceEntry<K, V> entry, long now) {
        return segmentFor(entry.getHash()).getLiveValue(entry, now) != null;
    }

    /**
     * 这个计算是获取一个entry hash属于哪个segment。根据并发水平的设置，计算2^n>concurrentLevel的n， 然后获取n个高位，计算segment 下标。
     *
     * jdk 7算法已更改，这里还是也jdk6的实现。
     * 
     * @param hash the hash code for the key
     * @return the segment
     */
    Segment<K, V> segmentFor(int hash) {
        // TODO(fry): Lazily create segments?
        return segments[(hash >>> segmentShift) & segmentMask];
    }

    Segment<K, V> createSegment(int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
        return new Segment<K, V>(this, initialCapacity, maxSegmentWeight, statsCounter);
    }

    /**
     * Gets the value from an entry. Returns null if the entry is invalid, partially-collected, loading, or expired.
     * Unlike {@link Segment#getLiveValue} this method does not attempt to cleanup stale entries. As such it should only
     * be called outside of a segment context, such as during iteration.
     */
    @Nullable
    V getLiveValue(ReferenceEntry<K, V> entry, long now) {
        if (entry.getKey() == null) {
            return null;
        }
        V value = entry.getValueReference().get();
        if (value == null) {
            return null;
        }

        if (isExpired(entry, now)) {
            return null;
        }
        return value;
    }

    // expiration

    /**
     * Returns true if the entry has expired.
     */
    boolean isExpired(ReferenceEntry<K, V> entry, long now) {
        checkNotNull(entry);
        if (expiresAfterAccess() && (now - entry.getAccessTime() >= expireAfterAccessNanos)) {
            return true;
        }
        if (expiresAfterWrite() && (now - entry.getWriteTime() >= expireAfterWriteNanos)) {
            return true;
        }
        return false;
    }

    // queues

    // guardedBy 注解只是告知 该方法被segment来保护，确保线程安全
    // 我们知道，对于segment写操作时，会确保只有一个线程进行的。因此内部可以不需要额外的线程安全代码保护
    @GuardedBy("Segment.this")
    static <K, V> void connectAccessOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInAccessQueue(next);
        next.setPreviousInAccessQueue(previous);
    }

    @GuardedBy("Segment.this")
    static <K, V> void nullifyAccessOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInAccessQueue(nullEntry);
        nulled.setPreviousInAccessQueue(nullEntry);
    }

    @GuardedBy("Segment.this")
    static <K, V> void connectWriteOrder(ReferenceEntry<K, V> previous, ReferenceEntry<K, V> next) {
        previous.setNextInWriteQueue(next);
        next.setPreviousInWriteQueue(previous);
    }

    @GuardedBy("Segment.this")
    static <K, V> void nullifyWriteOrder(ReferenceEntry<K, V> nulled) {
        ReferenceEntry<K, V> nullEntry = nullEntry();
        nulled.setNextInWriteQueue(nullEntry);
        nulled.setPreviousInWriteQueue(nullEntry);
    }

    /**
     * 通知监听器，一个entry会因为超时，驱赶或者垃圾回收等被自动移除。在每一次超时对象或者驱赶对象被调用的时候调用这个通知器方法。
     */
    void processPendingNotifications() {
        RemovalNotification<K, V> notification;
        while ((notification = removalNotificationQueue.poll()) != null) {
            try {
                removalListener.onRemoval(notification);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Exception thrown by removal listener", e);
            }
        }
    }

    /**
     * 这里创建多个segment数组，和concurrentHashMap一样，线程安全使用基于segment细粒度锁维护。
     */
    @SuppressWarnings("unchecked")
    final Segment<K, V>[] newSegmentArray(int ssize) {
        return new Segment[ssize];
    }

    // Inner Classes

    /**
     * segment是hash table的特殊版本。这个子类投机方式来继承了ReentrantLock，仅仅是为了简化一些locking，以及避免单独去构建代码。
     * 
     */
    @SuppressWarnings("serial")
    // This class is never serialized.
    static class Segment<K, V> extends ReentrantLock {

        /*
         * TODO(fry): Consider copying variables (like evictsBySize) from outer class into this class. It will require
         * more memory but will reduce indirection.
         */

        /**
         * segments 维护一个entry列表的table，确保一致性状态。所以可以不加锁去读。节点的next field是不可修改的final，因为所有list的增加操作
         * 是执行在每个容器的头部。因此，这样子很容易去检查变化，也可以快速遍历。此外，当节点被改变的时候，新的节点将被创建然后替换它们。 由于容器的list一般都比较短（平均长度小于2），所以对于hash
         * tables来说，可以工作的很好。虽然说读操作因此可以不需要锁进行，但是是依赖
         * 使用volatile确保其他线程完成写操作。对于绝大多数目的而言，count变量，跟踪元素的数量，作为一个volatile变量确保可见性（visibility）。
         * 这样一下子变得方便的很多，因为这个变量在很多读操作的时候都会被获取：所有非同步的（unsynchronized）读操作必须首先读取这个count值，并且如果count为0则不会 查找table
         * 的entries元素；所有的同步（synchronized）操作必须在结构性的改变任务bin容器之后，才会写操作这个count值。
         * 这些操作必须在并发读操作看到不一致的数据的时候，不采取任务动作。在map中读操作性质可以更容易实现这个限制。例如：没有操作可以显示出 当table
         * 增长了，但是threshold值没有更新，所以考虑读的时候不要求原子性。作为一个原则，所有危险的volatile读和写count变量都必须在代码中标记。
         */

        final LocalCache<K, V> map;

        /**
         * 该segment区域内所有存活的元素个数
         */
        volatile int count;

        /**
         * 该segment的区域内所有元素的权重和
         */
        @GuardedBy("Segment.this")
        long totalWeight;

        /**
         * 改变table大小size的更新次数。这个在批量读取方法期间保证它们可以看到一致性的快照：
         * 如果modDCount在我们遍历段加载大小或者核对containsValue期间被改变了，然后我们会看到一个不一致的状态视图，以至于必须去重试。
         * 
         * 感觉这里有点像是版本控制，比如数据库里的version字段来控制数据一致性
         */
        int modCount;

        /**
         * 阈值，默认0.75 The table is expanded when its size exceeds this threshold. (The value of this field is always
         * {@code (int) (capacity * 0.75)}.)
         */
        int threshold;

        /**
         * 每个段表，使用乐观锁的Array来保存entry The per-segment table.
         */
        volatile AtomicReferenceArray<ReferenceEntry<K, V>> table; // 这里和concurrentHashMap不一致，原因是这边元素是引用，直接使用不会线程安全

        /**
         * 对应段的最大权重。如果没有max则为UNSET_INT。
         */
        final long maxSegmentWeight;

        /**
         * referenceQueue队列包含的是一些需要被gc回收的entry，以及一些需要被内部clean up的entry
         * 
         */
        final ReferenceQueue<K> keyReferenceQueue;

        /**
         * The value reference queue contains value references whose values have been garbage collected, and which need
         * to be cleaned up internally.
         */
        final ReferenceQueue<V> valueReferenceQueue;

        /**
         * recencyQueue 被用来记录哪些entries并访问了，可以更新access 列表的排序。当超过DRAIN_THRESHOLD阈值或者在segment的写操作发生，则会批量清除该队列
         */
        final Queue<ReferenceEntry<K, V>> recencyQueue;

        /**
         * A counter of the number of reads since the last write, used to drain queues on a small fraction of read
         * operations.
         */
        final AtomicInteger readCount = new AtomicInteger();

        /**
         * A queue of elements currently in the map, ordered by write time. Elements are added to the tail of the queue
         * on write.
         */
        @GuardedBy("Segment.this")
        final Queue<ReferenceEntry<K, V>> writeQueue;

        /**
         * A queue of elements currently in the map, ordered by access time. Elements are added to the tail of the queue
         * on access (note that writes count as accesses).
         */
        @GuardedBy("Segment.this")
        final Queue<ReferenceEntry<K, V>> accessQueue;

        /** Accumulates cache statistics. */
        final StatsCounter statsCounter;

        // 构造函数
        Segment(LocalCache<K, V> map, int initialCapacity, long maxSegmentWeight, StatsCounter statsCounter) {
            this.map = map;
            this.maxSegmentWeight = maxSegmentWeight;
            this.statsCounter = checkNotNull(statsCounter);
            initTable(newEntryArray(initialCapacity));

            // 构造队列，可以看到如果在builder没有配置，则不创建queue或者为空queue
            keyReferenceQueue = map.usesKeyReferences() ? new ReferenceQueue<K>() : null;

            valueReferenceQueue = map.usesValueReferences() ? new ReferenceQueue<V>() : null;

            recencyQueue = map.usesAccessQueue() ? new ConcurrentLinkedQueue<ReferenceEntry<K, V>>() : LocalCache
                    .<ReferenceEntry<K, V>> discardingQueue();

            writeQueue = map.usesWriteQueue() ? new WriteQueue<K, V>() : LocalCache
                    .<ReferenceEntry<K, V>> discardingQueue();

            accessQueue = map.usesAccessQueue() ? new AccessQueue<K, V>() : LocalCache
                    .<ReferenceEntry<K, V>> discardingQueue();
        }

        AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int size) {
            return new AtomicReferenceArray<ReferenceEntry<K, V>>(size);
        }

        void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> newTable) {
            this.threshold = newTable.length() * 3 / 4; // 0.75
            if (!map.customWeigher() && this.threshold == maxSegmentWeight) {
                // prevent spurious expansion before eviction
                this.threshold++;
            }
            this.table = newTable;
        }

        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> newEntry(K key, int hash, @Nullable ReferenceEntry<K, V> next) {
            return map.entryFactory.newEntry(this, checkNotNull(key), hash, next);
        }

        /**
         * Copies {@code original} into a new entry chained to {@code newNext}. Returns the new entry, or {@code null}
         * if {@code original} was already garbage collected.
         */
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> original, ReferenceEntry<K, V> newNext) {
            if (original.getKey() == null) {
                // key collected
                return null;
            }

            ValueReference<K, V> valueReference = original.getValueReference();
            V value = valueReference.get();
            if ((value == null) && valueReference.isActive()) {
                // value collected
                return null;
            }

            ReferenceEntry<K, V> newEntry = map.entryFactory.copyEntry(this, original, newNext);
            newEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, value, newEntry));
            return newEntry;
        }

        /**
         * Sets a new value of an entry. Adds newly created entries at the end of the access queue.
         */
        @GuardedBy("Segment.this")
        void setValue(ReferenceEntry<K, V> entry, K key, V value, long now) {
            ValueReference<K, V> previous = entry.getValueReference();
            int weight = map.weigher.weigh(key, value); // key只是用来获取权重的吗？！
            checkState(weight >= 0, "Weights must be non-negative");

            ValueReference<K, V> valueReference = map.valueStrength.referenceValue(this, entry, value, weight);
            entry.setValueReference(valueReference);
            recordWrite(entry, weight, now); // 更新access队列和write队列
            previous.notifyNewValue(value); // 老的值被新的值替换掉
        }

        // loading

        // 获取key对应的value
        V get(K key, int hash, CacheLoader<? super K, V> loader) throws ExecutionException {
            checkNotNull(key);
            checkNotNull(loader);
            try {
                if (count != 0) { // 确保可见性， read-volatile
                    // don't call getLiveEntry, which would ignore loading values
                    ReferenceEntry<K, V> e = getEntry(key, hash);
                    if (e != null) {
                        long now = map.ticker.read();
                        V value = getLiveValue(e, now);// 获取未过期的数据
                        if (value != null) {
                            recordRead(e, now);// access队列，还有最近使用队列
                            statsCounter.recordHits(1);// 命中啦
                            return scheduleRefresh(e, key, hash, value, now, loader);// 调度refresh，如果设置了refresh时间的话
                        }
                        ValueReference<K, V> valueReference = e.getValueReference();
                        if (valueReference.isLoading()) { // 只有strong引用，其他基本上都是false
                            return waitForLoadingValue(e, key, valueReference);
                        }
                    }
                }

                // at this point e is either null or expired;
                return lockedGetOrLoad(key, hash, loader);// 重新加载数据到cache
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Error) {
                    throw new ExecutionError((Error) cause);
                } else if (cause instanceof RuntimeException) {
                    throw new UncheckedExecutionException(cause);
                }
                throw ee;
            } finally {
                postReadCleanup();
            }
        }

        /**
         * 这里开始从我们实现cacheLoader继承类中的load方法获取 key对应的值。
         * 
         * 加锁get或者load
         */
        V lockedGetOrLoad(K key, int hash, CacheLoader<? super K, V> loader) throws ExecutionException {
            ReferenceEntry<K, V> e;
            ValueReference<K, V> valueReference = null;
            LoadingValueReference<K, V> loadingValueReference = null;
            boolean createNewEntry = true;

            // 确保线程安全，使用加锁来确保加载。当然这个也是针对segment粒度来加的
            lock();
            try {
                // re-read ticker once inside the lock
                long now = map.ticker.read();
                preWriteCleanup(now);// 加锁清GC遗留引用数据和超时数据

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);// 根据hash和table长度来确定index索引
                ReferenceEntry<K, V> first = table.get(index);

                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        valueReference = e.getValueReference();
                        if (valueReference.isLoading()) {
                            createNewEntry = false;// 如果正在加载，则返回false，表示不需要新建entry
                        } else {
                            // 对value进行判断处理，
                            V value = valueReference.get();
                            if (value == null) {
                                // 相关通知操作，GC原因回收了
                                enqueueNotification(entryKey, hash, valueReference, RemovalCause.COLLECTED);
                            } else if (map.isExpired(e, now)) {
                                // This is a duplicate check, as preWriteCleanup already purged expired
                                // entries, but let's accomodate an incorrect expiration queue.
                                enqueueNotification(entryKey, hash, valueReference, RemovalCause.EXPIRED);
                            } else {
                                // cache存在value，命中缓存
                                recordLockedRead(e, now);
                                statsCounter.recordHits(1);
                                // we were concurrent with loading; don't consider refresh
                                return value;
                            }

                            // immediately reuse invalid entries
                            writeQueue.remove(e);
                            accessQueue.remove(e);
                            this.count = newCount; // write-volatile
                        }
                        break;
                    }
                }

                // 处理需要新增entry，从load方法获取的逻辑
                if (createNewEntry) {
                    loadingValueReference = new LoadingValueReference<K, V>();

                    if (e == null) {
                        e = newEntry(key, hash, first);// segment神马都没有的时候，新建一个
                        e.setValueReference(loadingValueReference);
                        table.set(index, e);
                    } else {
                        e.setValueReference(loadingValueReference);
                    }
                }
            } finally {
                unlock();
                postWriteCleanup();
            }

            // ok,上面加锁部分建完了新的entry，设置完valueReference
            if (createNewEntry) {
                try {

                    // 在entry同步，但检测到递归load则会快速失败。当entry被copy时候可能绕行，但是绝大部分时间会快速失败
                    synchronized (e) {
                        return loadSync(key, hash, loadingValueReference, loader);
                    }
                } finally {
                    statsCounter.recordMisses(1);// 处理命中率
                }
            } else {
                // 如果正在加载，则等待加载完成
                // The entry already exists. Wait for loading.
                return waitForLoadingValue(e, key, valueReference);
            }
        }

        /**
         * 该entry以及存在，等待完成，被加载进来
         * @param e
         * @param key
         * @param valueReference
         * @return
         * @throws ExecutionException
         */
        V waitForLoadingValue(ReferenceEntry<K, V> e, K key, ValueReference<K, V> valueReference)
                throws ExecutionException {
            if (!valueReference.isLoading()) {
                throw new AssertionError();
            }

            checkState(!Thread.holdsLock(e), "Recursive load of: %s", key);
            // don't consider expiration as we're concurrent with loading
            try {
                V value = valueReference.waitForValue();//获取value引用
                if (value == null) {
                    throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
                }
                // re-read ticker now that loading has completed
                long now = map.ticker.read();
                recordRead(e, now);// 处理access队列
                return value;
            } finally {
                statsCounter.recordMisses(1);
            }
        }

        // 对于给定的loadingValueReference最多只有一个调用loadSync/loadAsync
        V loadSync(K key, int hash, LoadingValueReference<K, V> loadingValueReference, CacheLoader<? super K, V> loader)
                throws ExecutionException {
            ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);

            // 这里使用同步方式来返回
            return getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
        }

        // 对于给定的loadingValueReference最多只有一个调用loadSync/loadAsync
        ListenableFuture<V> loadAsync(final K key, final int hash,
                final LoadingValueReference<K, V> loadingValueReference, CacheLoader<? super K, V> loader) {

            // 异步调用load方法，返回Future对象
            final ListenableFuture<V> loadingFuture = loadingValueReference.loadFuture(key, loader);

            // 这里使用异步Listen的方式来获取future的 value
            loadingFuture.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        //放在监听队列中异步执行
                        V newValue = getAndRecordStats(key, hash, loadingValueReference, loadingFuture);
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Exception thrown during refresh", t);
                        loadingValueReference.setException(t);
                    }
                }
            }, sameThreadExecutor);
            return loadingFuture;
        }

        /**
         * Waits uninterruptibly for {@code newValue} to be loaded, and then records loading stats.
         */
        V getAndRecordStats(K key, int hash, LoadingValueReference<K, V> loadingValueReference,
                ListenableFuture<V> newValue) throws ExecutionException {
            V value = null;
            try {
                value = getUninterruptibly(newValue);//非中断方式调用future.get方法获取值
                if (value == null) {
                    throw new InvalidCacheLoadException("CacheLoader returned null for key " + key + ".");
                }
                statsCounter.recordLoadSuccess(loadingValueReference.elapsedNanos());
                storeLoadedValue(key, hash, loadingValueReference, value);
                return value;
            } finally {
                if (value == null) {
                    statsCounter.recordLoadException(loadingValueReference.elapsedNanos());
                    removeLoadingValue(key, hash, loadingValueReference);
                }
            }
        }

        V scheduleRefresh(ReferenceEntry<K, V> entry, K key, int hash, V oldValue, long now,
                CacheLoader<? super K, V> loader) {
            if (map.refreshes() && (now - entry.getWriteTime() > map.refreshNanos)
                    && !entry.getValueReference().isLoading()) {
                V newValue = refresh(key, hash, loader, true);
                if (newValue != null) {
                    return newValue;
                }
            }
            return oldValue;
        }

        /**
         * Refreshes the value associated with {@code key}, unless another thread is already doing so. Returns the newly
         * refreshed value associated with {@code key} if it was refreshed inline, or {@code null} if another thread is
         * performing the refresh or if an error occurs during refresh.
         */
        @Nullable
        V refresh(K key, int hash, CacheLoader<? super K, V> loader, boolean checkTime) {
            final LoadingValueReference<K, V> loadingValueReference = insertLoadingValueReference(key, hash, checkTime);
            if (loadingValueReference == null) {
                return null;
            }

            ListenableFuture<V> result = loadAsync(key, hash, loadingValueReference, loader);
            if (result.isDone()) {
                try {
                    return Uninterruptibles.getUninterruptibly(result);
                } catch (Throwable t) {
                    // don't let refresh exceptions propagate; error was already logged
                }
            }
            return null;
        }

        /**
         * Returns a newly inserted {@code LoadingValueReference}, or null if the live value reference is already
         * loading.
         */
        @Nullable
        LoadingValueReference<K, V> insertLoadingValueReference(final K key, final int hash, boolean checkTime) {
            ReferenceEntry<K, V> e = null;
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        if (valueReference.isLoading() || (checkTime && (now - e.getWriteTime() < map.refreshNanos))) {
                            // refresh is a no-op if loading is pending
                            // if checkTime, we want to check *after* acquiring the lock if refresh still needs
                            // to be scheduled
                            return null;
                        }

                        // continue returning old value while loading
                        ++modCount;
                        LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<K, V>(
                                valueReference);
                        e.setValueReference(loadingValueReference);
                        return loadingValueReference;
                    }
                }

                ++modCount;
                LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<K, V>();
                e = newEntry(key, hash, first);
                e.setValueReference(loadingValueReference);
                table.set(index, e);
                return loadingValueReference;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        // reference queues, for garbage collection cleanup

        /**
         * 线程安全的清除搜集到的entries，使用lock机制。
         */
        void tryDrainReferenceQueues() {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                } finally {
                    unlock();
                }
            }
        }

        /**
         * 清除key和value的referenceQueue队列，清除内部的entries包含 GC的key或者value
         */
        @GuardedBy("Segment.this")
        void drainReferenceQueues() {
            // 非strong 引用的，都是放在引用队列中，待GC回收
            if (map.usesKeyReferences()) {
                drainKeyReferenceQueue();
            }
            if (map.usesValueReferences()) {
                drainValueReferenceQueue();
            }
        }

        @GuardedBy("Segment.this")
        void drainKeyReferenceQueue() {
            Reference<? extends K> ref;
            int i = 0;
            while ((ref = keyReferenceQueue.poll()) != null) {
                @SuppressWarnings("unchecked")
                ReferenceEntry<K, V> entry = (ReferenceEntry<K, V>) ref;
                map.reclaimKey(entry);
                if (++i == DRAIN_MAX) {
                    break;
                }
            }
        }

        @GuardedBy("Segment.this")
        void drainValueReferenceQueue() {
            Reference<? extends V> ref;
            int i = 0;
            while ((ref = valueReferenceQueue.poll()) != null) {
                @SuppressWarnings("unchecked")
                ValueReference<K, V> valueReference = (ValueReference<K, V>) ref;
                map.reclaimValue(valueReference);
                if (++i == DRAIN_MAX) {
                    break;
                }
            }
        }

        /**
         * Clears all entries from the key and value reference queues.
         */
        void clearReferenceQueues() {
            if (map.usesKeyReferences()) {
                clearKeyReferenceQueue();
            }
            if (map.usesValueReferences()) {
                clearValueReferenceQueue();
            }
        }

        void clearKeyReferenceQueue() {
            while (keyReferenceQueue.poll() != null) {
            }
        }

        void clearValueReferenceQueue() {
            while (valueReferenceQueue.poll() != null) {
            }
        }

        // recency queue, shared by expiration and eviction

        /**
         * Records the relative order in which this read was performed by adding {@code entry} to the recency queue. At
         * write-time, or when the queue is full past the threshold, the queue will be drained and the entries therein
         * processed.
         * 
         * <p>
         * Note: locked reads should use {@link #recordLockedRead}.
         */
        void recordRead(ReferenceEntry<K, V> entry, long now) {
            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            recencyQueue.add(entry);
        }

        /**
         * Updates the eviction metadata that {@code entry} was just read. This currently amounts to adding
         * {@code entry} to relevant eviction lists.
         * 
         * <p>
         * Note: this method should only be called under lock, as it directly manipulates the eviction queues. Unlocked
         * reads should use {@link #recordRead}.
         */
        @GuardedBy("Segment.this")
        void recordLockedRead(ReferenceEntry<K, V> entry, long now) {
            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            accessQueue.add(entry);
        }

        /**
         * Updates eviction metadata that {@code entry} was just written. This currently amounts to adding {@code entry}
         * to relevant eviction lists.
         */
        @GuardedBy("Segment.this")
        void recordWrite(ReferenceEntry<K, V> entry, int weight, long now) {
            // we are already under lock, so drain the recency queue immediately
            drainRecencyQueue();
            totalWeight += weight;

            if (map.recordsAccess()) {
                entry.setAccessTime(now);
            }
            if (map.recordsWrite()) {
                entry.setWriteTime(now);
            }
            accessQueue.add(entry);
            writeQueue.add(entry);
        }

        /**
         * 清除recencyQueue队列，按照指定的相关顺序来读取entries并且更新驱赶的元数据。把他们加到相关的evict列表 （这表明他们可以被移除出map中，由于被加到了recencyQueue队列中。）
         */
        @GuardedBy("Segment.this")
        void drainRecencyQueue() {
            ReferenceEntry<K, V> e;
            while ((e = recencyQueue.poll()) != null) {
                // 一个entry尽管它从map中移除，也可能在recencyQueue中。一般发生在：当entry并发读然而一个写操作尝试从segment中移除它，
                // 或者在clear操作移除所有的segment的entry。
                if (accessQueue.contains(e)) {
                    accessQueue.add(e);
                }
            }
        }

        // expiration

        /**
         * Cleanup expired entries when the lock is available.
         */
        void tryExpireEntries(long now) {
            if (tryLock()) {
                try {
                    expireEntries(now);
                } finally {
                    unlock();
                    // don't call postWriteCleanup as we're in a read
                }
            }
        }

        @GuardedBy("Segment.this")
        void expireEntries(long now) { // 过期entries，当write或者access队列过期，则移除entry
            drainRecencyQueue();

            ReferenceEntry<K, V> e;
            while ((e = writeQueue.peek()) != null && map.isExpired(e, now)) {
                if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                    throw new AssertionError();
                }
            }
            while ((e = accessQueue.peek()) != null && map.isExpired(e, now)) {
                if (!removeEntry(e, e.getHash(), RemovalCause.EXPIRED)) {
                    throw new AssertionError();
                }
            }
        }

        // eviction

        @GuardedBy("Segment.this")
        void enqueueNotification(ReferenceEntry<K, V> entry, RemovalCause cause) {
            enqueueNotification(entry.getKey(), entry.getHash(), entry.getValueReference(), cause);
        }

        @GuardedBy("Segment.this")
        void enqueueNotification(@Nullable K key, int hash, ValueReference<K, V> valueReference, RemovalCause cause) {
            totalWeight -= valueReference.getWeight();
            if (cause.wasEvicted()) {
                statsCounter.recordEviction();
            }
            if (map.removalNotificationQueue != DISCARDING_QUEUE) {
                V value = valueReference.get();
                RemovalNotification<K, V> notification = new RemovalNotification<K, V>(key, value, cause);
                map.removalNotificationQueue.offer(notification);// 插入通知到queue中
            }
        }

        /**
         * 如果segment满了，则执行evict操作。这个调用仅仅发生在增加一个新的entry并且增加了count的时候。
         */
        @GuardedBy("Segment.this")
        void evictEntries() {
            if (!map.evictsBySize()) { // 如果没有设置cache的权重，则不执行evict操作
                return;
            }

            drainRecencyQueue();
            while (totalWeight > maxSegmentWeight) { // 当总的权重大于设置的最大段权重，才会执行remove操作
                ReferenceEntry<K, V> e = getNextEvictable();
                if (!removeEntry(e, e.getHash(), RemovalCause.SIZE)) {
                    throw new AssertionError();
                }
            }
        }

        // TODO(fry): instead implement this with an eviction head
        ReferenceEntry<K, V> getNextEvictable() {
            for (ReferenceEntry<K, V> e : accessQueue) {
                int weight = e.getValueReference().getWeight();
                if (weight > 0) {// 设置权重大于0，才会
                    return e;
                }
            }
            throw new AssertionError();
        }

        /**
         * AtomicReferenceArray 可以确保原子的更新引用的元素。
         * 
         * 为给定的hash值返回第一个entry节点.
         */
        ReferenceEntry<K, V> getFirst(int hash) {

            // 复制到线程安全的数组中，形成一个快照，确保读的时候，数据一致性。只会读取这个域一次。
            // 此外，这样子可以提供读对于整个table的影响，因为全局的table并不会锁住。（猜测）
            AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
            return table.get(hash & (table.length() - 1));
        }

        // map方法的特殊实现

        @Nullable
        ReferenceEntry<K, V> getEntry(Object key, int hash) {
            for (ReferenceEntry<K, V> e = getFirst(hash); e != null; e = e.getNext()) {
                if (e.getHash() != hash) {
                    continue;
                }

                // hash值相同的，接下来找key值也相同的ReferenceEntry
                K entryKey = e.getKey();
                if (entryKey == null) {
                    tryDrainReferenceQueues();
                    continue;
                }

                if (map.keyEquivalence.equivalent(key, entryKey)) {
                    return e;
                }
            }

            return null;
        }

        @Nullable
        ReferenceEntry<K, V> getLiveEntry(Object key, int hash, long now) {
            ReferenceEntry<K, V> e = getEntry(key, hash);
            if (e == null) {
                return null;
            } else if (map.isExpired(e, now)) {
                tryExpireEntries(now);
                return null;
            }
            return e;
        }

        /**
         * Gets the value from an entry. Returns null if the entry is invalid, partially-collected, loading, or expired.
         */
        V getLiveValue(ReferenceEntry<K, V> entry, long now) {
            if (entry.getKey() == null) {
                tryDrainReferenceQueues();// 清引用queue，把key被gc的entries，移除掉
                return null;
            }
            V value = entry.getValueReference().get();
            if (value == null) {
                tryDrainReferenceQueues();
                return null;
            }

            if (map.isExpired(entry, now)) {
                tryExpireEntries(now);
                return null;
            }
            return value;
        }

        @Nullable
        V get(Object key, int hash) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                    if (e == null) {
                        return null;
                    }

                    V value = e.getValueReference().get();
                    if (value != null) {
                        recordRead(e, now);
                        return scheduleRefresh(e, e.getKey(), hash, value, now, map.defaultLoader);
                    }
                    tryDrainReferenceQueues();
                }
                return null;
            } finally {
                postReadCleanup();
            }
        }

        boolean containsKey(Object key, int hash) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    ReferenceEntry<K, V> e = getLiveEntry(key, hash, now);
                    if (e == null) {
                        return false;
                    }
                    return e.getValueReference().get() != null;
                }

                return false;
            } finally {
                postReadCleanup();
            }
        }

        /**
         * This method is a convenience for testing. Code should call {@link LocalCache#containsValue} directly.
         */
        @VisibleForTesting
        boolean containsValue(Object value) {
            try {
                if (count != 0) { // read-volatile
                    long now = map.ticker.read();
                    AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                    int length = table.length();
                    for (int i = 0; i < length; ++i) {
                        for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                            V entryValue = getLiveValue(e, now);
                            if (entryValue == null) {
                                continue;
                            }
                            if (map.valueEquivalence.equivalent(value, entryValue)) {
                                return true;
                            }
                        }
                    }
                }

                return false;
            } finally {
                postReadCleanup();
            }
        }

        @Nullable
        V put(K key, int hash, V value, boolean onlyIfAbsent) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // ensure capacity
                    expand();
                    newCount = this.count + 1;
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                // Look for an existing entry.
                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        // We found an existing entry.

                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        if (entryValue == null) {
                            ++modCount;
                            if (valueReference.isActive()) {
                                enqueueNotification(key, hash, valueReference, RemovalCause.COLLECTED);
                                setValue(e, key, value, now);
                                newCount = this.count; // count remains unchanged
                            } else {
                                setValue(e, key, value, now);
                                newCount = this.count + 1;
                            }
                            this.count = newCount; // write-volatile
                            evictEntries();
                            return null;
                        } else if (onlyIfAbsent) {
                            // Mimic
                            // "if (!map.containsKey(key)) ...
                            // else return map.get(key);
                            recordLockedRead(e, now);
                            return entryValue;
                        } else {
                            // clobber existing entry, count remains unchanged
                            ++modCount;
                            enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                            setValue(e, key, value, now);
                            evictEntries();
                            return entryValue;
                        }
                    }
                }

                // Create a new entry.
                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, value, now);
                table.set(index, newEntry);
                newCount = this.count + 1;
                this.count = newCount; // write-volatile
                evictEntries();
                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /**
         * 如果需要并且没到限制大小，则扩展表table。
         *
         */
        @GuardedBy("Segment.this")
        void expand() {
            AtomicReferenceArray<ReferenceEntry<K, V>> oldTable = table;//原子引用
            int oldCapacity = oldTable.length();
            if (oldCapacity >= MAXIMUM_CAPACITY) {// 无法扩容
                return;
            }

            /**
             * 把每个list的nodes分类到新的map中。 因为我们这里使用的是2的指数次扩容，所以在每个bin的元素，要么还是同样的index中待着，
             * 要么移到2的指数个偏移。我们排除了不必要的节点创建（可以优化场景：因为老的节点们下一个fields不会被改变，所以老的节点可以被重复使用）。
             *
             * 以默认域设置来统计，当我们双倍扩展table时，仅仅只有六分之一的节点需要clone。这些节点将会被GC掉，
             * 在他们不在被任务reader线程（这些线程可能正遍历在table的中间部分）引用的时候。
             *
             *
             * Reclassify nodes in each list to new Map. Because we are using power-of-two expansion, the elements from
             * each bin must either stay at same index, or move with a power of two offset. We eliminate unnecessary
             * node creation by catching cases where old nodes can be reused because their next fields won't change.
             * Statistically, at the default threshold, only about one-sixth of them need cloning when a table doubles.
             * The nodes they replace will be garbage collectable as soon as they are no longer referenced by any reader
             * thread that may be in the midst of traversing table right now.
             */

            int newCount = count;
            AtomicReferenceArray<ReferenceEntry<K, V>> newTable = newEntryArray(oldCapacity << 1);
            threshold = newTable.length() * 3 / 4;
            int newMask = newTable.length() - 1;
            for (int oldIndex = 0; oldIndex < oldCapacity; ++oldIndex) {
                // We need to guarantee that any existing reads of old Map can
                // proceed. So we cannot yet null out each bin.
                ReferenceEntry<K, V> head = oldTable.get(oldIndex);

                if (head != null) {
                    ReferenceEntry<K, V> next = head.getNext();
                    int headIndex = head.getHash() & newMask;

                    // Single node on list
                    if (next == null) {
                        newTable.set(headIndex, head);
                    } else {
                        // Reuse the consecutive sequence of nodes with the same target
                        // index from the end of the list. tail points to the first
                        // entry in the reusable list.
                        ReferenceEntry<K, V> tail = head;
                        int tailIndex = headIndex;
                        for (ReferenceEntry<K, V> e = next; e != null; e = e.getNext()) {
                            int newIndex = e.getHash() & newMask;
                            if (newIndex != tailIndex) {
                                // The index changed. We'll need to copy the previous entry.
                                tailIndex = newIndex;
                                tail = e;
                            }
                        }
                        newTable.set(tailIndex, tail);

                        // Clone nodes leading up to the tail.
                        for (ReferenceEntry<K, V> e = head; e != tail; e = e.getNext()) {
                            int newIndex = e.getHash() & newMask;
                            ReferenceEntry<K, V> newNext = newTable.get(newIndex);
                            ReferenceEntry<K, V> newFirst = copyEntry(e, newNext);
                            if (newFirst != null) {
                                newTable.set(newIndex, newFirst);
                            } else {
                                removeCollectedEntry(e);
                                newCount--;
                            }
                        }
                    }
                }
            }
            table = newTable;
            this.count = newCount;
        }

        boolean replace(K key, int hash, V oldValue, V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount = this.count - 1;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash,
                                        valueReference, RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return false;
                        }

                        if (map.valueEquivalence.equivalent(oldValue, entryValue)) {
                            ++modCount;
                            enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                            setValue(e, key, newValue, now);
                            evictEntries();
                            return true;
                        } else {
                            // Mimic
                            // "if (map.containsKey(key) && map.get(key).equals(oldValue))..."
                            recordLockedRead(e, now);
                            return false;
                        }
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        @Nullable
        V replace(K key, int hash, V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        if (entryValue == null) {
                            if (valueReference.isActive()) {
                                // If the value disappeared, this entry is partially collected.
                                int newCount = this.count - 1;
                                ++modCount;
                                ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash,
                                        valueReference, RemovalCause.COLLECTED);
                                newCount = this.count - 1;
                                table.set(index, newFirst);
                                this.count = newCount; // write-volatile
                            }
                            return null;
                        }

                        ++modCount;
                        enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                        setValue(e, key, newValue, now);
                        evictEntries();
                        return entryValue;
                    }
                }

                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        @Nullable
        V remove(Object key, int hash) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (entryValue != null) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return null;
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash, valueReference,
                                cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return entryValue;
                    }
                }

                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /**
         * 首先，这里是线程安全的。把key和value存放到cache中。
         */
        boolean storeLoadedValue(K key, int hash, LoadingValueReference<K, V> oldValueReference, V newValue) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);//clean工作

                int newCount = this.count + 1;
                if (newCount > this.threshold) { // 保证大小够用ensure capacity
                    expand();
                    newCount = this.count + 1;//扩容之后，count可能会变化
                }

                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();
                        // replace the old LoadingValueReference if it's live, otherwise
                        // perform a putIfAbsent
                        if (oldValueReference == valueReference || (entryValue == null && valueReference != UNSET)) {
                            ++modCount;
                            if (oldValueReference.isActive()) {
                                RemovalCause cause = (entryValue == null) ? RemovalCause.COLLECTED
                                        : RemovalCause.REPLACED;
                                enqueueNotification(key, hash, oldValueReference, cause);
                                newCount--;
                            }
                            setValue(e, key, newValue, now);
                            this.count = newCount; // write-volatile
                            evictEntries();
                            return true;
                        }

                        // the loaded value was already clobbered
                        valueReference = new WeightedStrongValueReference<K, V>(newValue, 0);
                        enqueueNotification(key, hash, valueReference, RemovalCause.REPLACED);
                        return false;
                    }
                }

                ++modCount;
                ReferenceEntry<K, V> newEntry = newEntry(key, hash, first);
                setValue(newEntry, key, newValue, now);
                table.set(index, newEntry);
                this.count = newCount; // write-volatile
                evictEntries();
                return true;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        boolean remove(Object key, int hash, Object value) {
            lock();
            try {
                long now = map.ticker.read();
                preWriteCleanup(now);

                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> valueReference = e.getValueReference();
                        V entryValue = valueReference.get();

                        RemovalCause cause;
                        if (map.valueEquivalence.equivalent(value, entryValue)) {
                            cause = RemovalCause.EXPLICIT;
                        } else if (entryValue == null && valueReference.isActive()) {
                            cause = RemovalCause.COLLECTED;
                        } else {
                            // currently loading
                            return false;
                        }

                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash, valueReference,
                                cause);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return (cause == RemovalCause.EXPLICIT);
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        void clear() {
            if (count != 0) { // read-volatile
                lock();
                try {
                    AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                    for (int i = 0; i < table.length(); ++i) {
                        for (ReferenceEntry<K, V> e = table.get(i); e != null; e = e.getNext()) {
                            // Loading references aren't actually in the map yet.
                            if (e.getValueReference().isActive()) {
                                enqueueNotification(e, RemovalCause.EXPLICIT);
                            }
                        }
                    }
                    for (int i = 0; i < table.length(); ++i) {
                        table.set(i, null);
                    }
                    clearReferenceQueues();
                    writeQueue.clear();
                    accessQueue.clear();
                    readCount.set(0);

                    ++modCount;
                    count = 0; // write-volatile
                } finally {
                    unlock();
                    postWriteCleanup();
                }
            }
        }

        @GuardedBy("Segment.this")
        @Nullable
        ReferenceEntry<K, V> removeValueFromChain(ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry,
                @Nullable K key, int hash, ValueReference<K, V> valueReference, RemovalCause cause) {
            enqueueNotification(key, hash, valueReference, cause);
            writeQueue.remove(entry);
            accessQueue.remove(entry);

            if (valueReference.isLoading()) {
                valueReference.notifyNewValue(null);
                return first;
            } else {
                return removeEntryFromChain(first, entry);
            }
        }

        @GuardedBy("Segment.this")
        @Nullable
        ReferenceEntry<K, V> removeEntryFromChain(ReferenceEntry<K, V> first, ReferenceEntry<K, V> entry) {
            int newCount = count;
            ReferenceEntry<K, V> newFirst = entry.getNext();
            for (ReferenceEntry<K, V> e = first; e != entry; e = e.getNext()) {
                ReferenceEntry<K, V> next = copyEntry(e, newFirst);
                if (next != null) {
                    newFirst = next;
                } else {
                    removeCollectedEntry(e);
                    newCount--;
                }
            }
            this.count = newCount;
            return newFirst;
        }

        @GuardedBy("Segment.this")
        void removeCollectedEntry(ReferenceEntry<K, V> entry) {
            enqueueNotification(entry, RemovalCause.COLLECTED);
            writeQueue.remove(entry);
            accessQueue.remove(entry);
        }

        /**
         * Removes an entry whose key has been garbage collected.
         */
        boolean reclaimKey(ReferenceEntry<K, V> entry, int hash) {
            lock();
            try {
                int newCount = count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    if (e == entry) {
                        ++modCount;
                        ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, e.getKey(), hash,
                                e.getValueReference(), RemovalCause.COLLECTED);
                        newCount = this.count - 1;
                        table.set(index, newFirst);
                        this.count = newCount; // write-volatile
                        return true;
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /**
         * Removes an entry whose value has been garbage collected.
         */
        boolean reclaimValue(K key, int hash, ValueReference<K, V> valueReference) {
            lock();
            try {
                int newCount = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            ++modCount;
                            ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, entryKey, hash,
                                    valueReference, RemovalCause.COLLECTED);
                            newCount = this.count - 1;
                            table.set(index, newFirst);
                            this.count = newCount; // write-volatile
                            return true;
                        }
                        return false;
                    }
                }

                return false;
            } finally {
                unlock();
                if (!isHeldByCurrentThread()) { // don't cleanup inside of put
                    postWriteCleanup();
                }
            }
        }

        boolean removeLoadingValue(K key, int hash, LoadingValueReference<K, V> valueReference) {
            lock();
            try {
                AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
                int index = hash & (table.length() - 1);
                ReferenceEntry<K, V> first = table.get(index);

                for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                    K entryKey = e.getKey();
                    if (e.getHash() == hash && entryKey != null && map.keyEquivalence.equivalent(key, entryKey)) {
                        ValueReference<K, V> v = e.getValueReference();
                        if (v == valueReference) {
                            if (valueReference.isActive()) {
                                e.setValueReference(valueReference.getOldValue());
                            } else {
                                ReferenceEntry<K, V> newFirst = removeEntryFromChain(first, e);
                                table.set(index, newFirst);
                            }
                            return true;
                        }
                        return false;
                    }
                }

                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        @GuardedBy("Segment.this")
        boolean removeEntry(ReferenceEntry<K, V> entry, int hash, RemovalCause cause) {
            int newCount = this.count - 1;
            AtomicReferenceArray<ReferenceEntry<K, V>> table = this.table;
            int index = hash & (table.length() - 1);
            ReferenceEntry<K, V> first = table.get(index);

            for (ReferenceEntry<K, V> e = first; e != null; e = e.getNext()) {
                if (e == entry) {
                    ++modCount;
                    ReferenceEntry<K, V> newFirst = removeValueFromChain(first, e, e.getKey(), hash,
                            e.getValueReference(), cause);
                    newCount = this.count - 1;
                    table.set(index, newFirst);
                    this.count = newCount; // write-volatile
                    return true;
                }
            }

            return false;
        }

        /**
         * 在read操作之后，执行常规的清理工作。一般，在写的发送的时候清理，如果足够次读完之后没有发现清理，则尝试从read线程执行清理
         */
        void postReadCleanup() {
            if ((readCount.incrementAndGet() & DRAIN_THRESHOLD) == 0) {
                cleanUp();
            }
        }

        /**
         * 在执行write操作之前执行日常的clean工作，写线程必须有segment锁，然后在获取lock之后才能执行clean工作。
         * 
         * <p>
         * Post-condition: expireEntries has been run.
         */
        @GuardedBy("Segment.this")
        void preWriteCleanup(long now) {
            runLockedCleanup(now);
        }

        /**
         * Performs routine cleanup following a write.
         */
        void postWriteCleanup() {
            runUnlockedCleanup();
        }

        void cleanUp() {
            long now = map.ticker.read();
            runLockedCleanup(now);
            runUnlockedCleanup();
        }

        void runLockedCleanup(long now) {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                    expireEntries(now); // calls drainRecencyQueue
                    readCount.set(0);
                } finally {
                    unlock();
                }
            }
        }

        void runUnlockedCleanup() {
            // locked cleanup may generate notifications we can send unlocked
            if (!isHeldByCurrentThread()) {
                map.processPendingNotifications();
            }
        }

    }

    static class LoadingValueReference<K, V> implements ValueReference<K, V> {
        volatile ValueReference<K, V> oldValue;

        // TODO(fry): rename get, then extend AbstractFuture instead of containing SettableFuture
        final SettableFuture<V> futureValue = SettableFuture.create();
        final Stopwatch stopwatch = Stopwatch.createUnstarted();

        public LoadingValueReference() {
            this(LocalCache.<K, V> unset());
        }

        public LoadingValueReference(ValueReference<K, V> oldValue) {
            this.oldValue = oldValue;
        }

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public boolean isActive() {
            return oldValue.isActive();
        }

        @Override
        public int getWeight() {
            return oldValue.getWeight();
        }

        public boolean set(@Nullable V newValue) {
            return futureValue.set(newValue);
        }

        public boolean setException(Throwable t) {
            return futureValue.setException(t);
        }

        private ListenableFuture<V> fullyFailedFuture(Throwable t) {
            return Futures.immediateFailedFuture(t);
        }

        @Override
        public void notifyNewValue(@Nullable V newValue) {
            if (newValue != null) {
                // The pending load was clobbered by a manual write.
                // Unblock all pending gets, and have them return the new value.
                set(newValue);
            } else {
                // The pending load was removed. Delay notifications until loading completes.
                oldValue = unset();
            }

            // TODO(fry): could also cancel loading if we had a handle on its future
        }

        // future获取值
        public ListenableFuture<V> loadFuture(K key, CacheLoader<? super K, V> loader) {
            stopwatch.start();//开始计算时间
            V previousValue = oldValue.get();
            try {
                if (previousValue == null) {
                    V newValue = loader.load(key);//调用loader方法获取值
                    //immediateFuture直接根据value值调用构造函数构造ListenableFuture对象
                    return set(newValue) ? futureValue : Futures.immediateFuture(newValue);//设置future值，成功返回true；如果已经设置则返回false
                }
                ListenableFuture<V> newValue = loader.reload(key, previousValue);
                if (newValue == null) {
                    return Futures.immediateFuture(null);
                }
                // To avoid a race, make sure the refreshed value is set into loadingValueReference
                // *before* returning newValue from the cache query.
                // 这段代码值得关注。就是set值，然后返回该值。
                return Futures.transform(newValue, new Function<V, V>() {
                    @Override
                    public V apply(V newValue) {
                        LoadingValueReference.this.set(newValue);
                        return newValue;
                    }
                });
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return setException(t) ? futureValue : fullyFailedFuture(t);
            }
        }

        public long elapsedNanos() {
            return stopwatch.elapsed(NANOSECONDS);
        }

        @Override
        public V waitForValue() throws ExecutionException {
            return getUninterruptibly(futureValue);
        }

        @Override
        public V get() {
            return oldValue.get();
        }

        public ValueReference<K, V> getOldValue() {
            return oldValue;
        }

        @Override
        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        @Override
        public ValueReference<K, V> copyFor(ReferenceQueue<V> queue, @Nullable V value, ReferenceEntry<K, V> entry) {
            return this;
        }
    }

    // Queues

    /**
     * A custom queue for managing eviction order. Note that this is tightly integrated with {@code ReferenceEntry},
     * upon which it relies to perform its linking.
     * 
     * <p>
     * Note that this entire implementation makes the assumption that all elements which are in the map are also in this
     * queue, and that all elements not in the queue are not in the map.
     * 
     * <p>
     * The benefits of creating our own queue are that (1) we can replace elements in the middle of the queue as part of
     * copyWriteEntry, and (2) the contains method is highly optimized for the current model.
     */
    static final class WriteQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

            @Override
            public long getWriteTime() {
                return Long.MAX_VALUE;
            }

            @Override
            public void setWriteTime(long time) {
            }

            ReferenceEntry<K, V> nextWrite = this;

            @Override
            public ReferenceEntry<K, V> getNextInWriteQueue() {
                return nextWrite;
            }

            @Override
            public void setNextInWriteQueue(ReferenceEntry<K, V> next) {
                this.nextWrite = next;
            }

            ReferenceEntry<K, V> previousWrite = this;

            @Override
            public ReferenceEntry<K, V> getPreviousInWriteQueue() {
                return previousWrite;
            }

            @Override
            public void setPreviousInWriteQueue(ReferenceEntry<K, V> previous) {
                this.previousWrite = previous;
            }
        };

        // implements Queue

        @Override
        public boolean offer(ReferenceEntry<K, V> entry) {
            // unlink
            connectWriteOrder(entry.getPreviousInWriteQueue(), entry.getNextInWriteQueue());

            // add to tail
            connectWriteOrder(head.getPreviousInWriteQueue(), entry);
            connectWriteOrder(entry, head);

            return true;
        }

        @Override
        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> next = head.getNextInWriteQueue();
            return (next == head) ? null : next;
        }

        @Override
        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> next = head.getNextInWriteQueue();
            if (next == head) {
                return null;
            }

            remove(next);
            return next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            ReferenceEntry<K, V> previous = e.getPreviousInWriteQueue();
            ReferenceEntry<K, V> next = e.getNextInWriteQueue();
            connectWriteOrder(previous, next);
            nullifyWriteOrder(e);

            return next != NullEntry.INSTANCE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            return e.getNextInWriteQueue() != NullEntry.INSTANCE;
        }

        @Override
        public boolean isEmpty() {
            return head.getNextInWriteQueue() == head;
        }

        @Override
        public int size() {
            int size = 0;
            for (ReferenceEntry<K, V> e = head.getNextInWriteQueue(); e != head; e = e.getNextInWriteQueue()) {
                size++;
            }
            return size;
        }

        @Override
        public void clear() {
            ReferenceEntry<K, V> e = head.getNextInWriteQueue();
            while (e != head) {
                ReferenceEntry<K, V> next = e.getNextInWriteQueue();
                nullifyWriteOrder(e);
                e = next;
            }

            head.setNextInWriteQueue(head);
            head.setPreviousInWriteQueue(head);
        }

        @Override
        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                @Override
                protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                    ReferenceEntry<K, V> next = previous.getNextInWriteQueue();
                    return (next == head) ? null : next;
                }
            };
        }
    }

    /**
     * 为了管理访问排序而自定义的队列。这个队列围绕着ReferenceEntry建立的，其内部对象就是执行该Entry的链接。
     * 
     * 需要注意的是，这里假设所有的在map里的元素必须都在queue队列中，并且不在map中的元素也不应该在queue中。
     * 
     * 创建我们自己的queue好处：我们可以替换queue队列中间的元素（copyWriteEntry）； 此外，对于我们的模型，内部实现的方法都是高度优化的。（感觉目前没有什么queue实现能很好满足这里的需求）
     * 
     * 关于这里使用的queue模型：queue是一个循环双向列表，其中head的next指向queue list 首位， head的pre指向queue list的最后一个元素
     * 
     */
    static final class AccessQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {

        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {

            @Override
            public long getAccessTime() {
                return Long.MAX_VALUE;
            }

            @Override
            public void setAccessTime(long time) {
            }

            // head 的pre和next设置，这样子可以很好的检查queue是否为空
            ReferenceEntry<K, V> nextAccess = this;
            ReferenceEntry<K, V> previousAccess = this;

            // 这里维护两个队列，但是实际上，就是next的一串链接
            @Override
            public ReferenceEntry<K, V> getNextInAccessQueue() {
                return nextAccess;
            }

            @Override
            public void setNextInAccessQueue(ReferenceEntry<K, V> next) {
                this.nextAccess = next;
            }

            @Override
            public ReferenceEntry<K, V> getPreviousInAccessQueue() {
                return previousAccess;
            }

            @Override
            public void setPreviousInAccessQueue(ReferenceEntry<K, V> previous) {
                this.previousAccess = previous;
            }
        };

        // implements Queue

        // 接下来这些方法实现一个queue，offer插入一个指定entry到queue中
        @Override
        public boolean offer(ReferenceEntry<K, V> entry) {
            // unlink
            // 这里把前后两个元素链接起来，这样当前entry就自然移除queue了
            connectAccessOrder(entry.getPreviousInAccessQueue(), entry.getNextInAccessQueue());

            // add to tail
            // 移除完了之后，把这个entry放在queue的尾部
            // 在这里保证LRU策略，因此，gauva cache的LRU策略实际上是针对每个segment的。
            connectAccessOrder(head.getPreviousInAccessQueue(), entry);
            connectAccessOrder(entry, head);

            return true;
        }

        // 获取队列的首部后元素，头尾都是head，所以head结束，queue模型参考类注释
        @Override
        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> next = head.getNextInAccessQueue();
            return (next == head) ? null : next;
        }

        // 移除队列首部后第一个元素
        @Override
        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> next = head.getNextInAccessQueue();
            if (next == head) {
                return null;
            }

            remove(next);
            return next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            ReferenceEntry<K, V> previous = e.getPreviousInAccessQueue();
            ReferenceEntry<K, V> next = e.getNextInAccessQueue();
            connectAccessOrder(previous, next);
            nullifyAccessOrder(e);// 移除元素并置为null

            // next正常操作后面会有entry对象的，否则为null说明queue是不存在该entry的
            return next != NullEntry.INSTANCE;
        }

        // 同样，这边逻辑也是，单独entry的next为null，所以如果不在cache中，则不会有entry
        // 此外， 等号也需要注意，这边根据reference强度来决定使用的==代码逻辑
        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            ReferenceEntry<K, V> e = (ReferenceEntry) o;
            return e.getNextInAccessQueue() != NullEntry.INSTANCE;
        }

        @Override
        public boolean isEmpty() {
            return head.getNextInAccessQueue() == head;
        }

        @Override
        public int size() {
            int size = 0;
            for (ReferenceEntry<K, V> e = head.getNextInAccessQueue(); e != head; e = e.getNextInAccessQueue()) {
                size++;
            }
            return size;
        }

        // 清queue数据
        @Override
        public void clear() {
            ReferenceEntry<K, V> e = head.getNextInAccessQueue();
            while (e != head) {
                ReferenceEntry<K, V> next = e.getNextInAccessQueue();
                nullifyAccessOrder(e);// 置为null，便于gc
                e = next;
            }

            head.setNextInAccessQueue(head);
            head.setPreviousInAccessQueue(head);
        }

        @Override
        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                @Override
                protected ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> previous) {
                    ReferenceEntry<K, V> next = previous.getNextInAccessQueue();
                    return (next == head) ? null : next;
                }
            };
        }
    }

    // Cache support

    public void cleanUp() {
        for (Segment<?, ?> segment : segments) {
            segment.cleanUp();
        }
    }

    // ConcurrentMap methods

    @Override
    public boolean isEmpty() {
        /*
         * Sum per-segment modCounts to avoid mis-reporting when elements are concurrently added and removed in one
         * segment while checking another, in which case the table was never actually empty at any point. (The sum
         * ensures accuracy up through at least 1<<31 per-segment modifications before recheck.) Method containsValue()
         * uses similar constructions for stability checks.
         */
        long sum = 0L;
        Segment<K, V>[] segments = this.segments;
        for (int i = 0; i < segments.length; ++i) {
            if (segments[i].count != 0) {
                return false;
            }
            sum += segments[i].modCount;
        }

        if (sum != 0L) { // recheck unless no modifications
            for (int i = 0; i < segments.length; ++i) {
                if (segments[i].count != 0) {
                    return false;
                }
                sum -= segments[i].modCount;
            }
            if (sum != 0L) {
                return false;
            }
        }
        return true;
    }

    long longSize() {
        Segment<K, V>[] segments = this.segments;
        long sum = 0;
        for (int i = 0; i < segments.length; ++i) {
            sum += segments[i].count;
        }
        return sum;
    }

    @Override
    public int size() {
        return Ints.saturatedCast(longSize());
    }

    @Override
    @Nullable
    public V get(@Nullable Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).get(key, hash);
    }

    @Nullable
    public V getIfPresent(Object key) {
        int hash = hash(checkNotNull(key));
        V value = segmentFor(hash).get(key, hash);
        if (value == null) {
            globalStatsCounter.recordMisses(1);
        } else {
            globalStatsCounter.recordHits(1);
        }
        return value;
    }

    V get(K key, CacheLoader<? super K, V> loader) throws ExecutionException {
        int hash = hash(checkNotNull(key));
        return segmentFor(hash).get(key, hash, loader);
    }

    V getOrLoad(K key) throws ExecutionException {
        return get(key, defaultLoader);
    }

    ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
        int hits = 0;
        int misses = 0;

        Map<K, V> result = Maps.newLinkedHashMap();
        for (Object key : keys) {
            V value = get(key);
            if (value == null) {
                misses++;
            } else {
                // TODO(fry): store entry key instead of query key
                @SuppressWarnings("unchecked")
                K castKey = (K) key;
                result.put(castKey, value);
                hits++;
            }
        }
        globalStatsCounter.recordHits(hits);
        globalStatsCounter.recordMisses(misses);
        return ImmutableMap.copyOf(result);
    }

    ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
        int hits = 0;
        int misses = 0;

        Map<K, V> result = Maps.newLinkedHashMap();
        Set<K> keysToLoad = Sets.newLinkedHashSet();
        for (K key : keys) {
            V value = get(key);
            if (!result.containsKey(key)) {
                result.put(key, value);
                if (value == null) {
                    misses++;
                    keysToLoad.add(key);
                } else {
                    hits++;
                }
            }
        }

        try {
            if (!keysToLoad.isEmpty()) {
                try {
                    Map<K, V> newEntries = loadAll(keysToLoad, defaultLoader);
                    for (K key : keysToLoad) {
                        V value = newEntries.get(key);
                        if (value == null) {
                            throw new InvalidCacheLoadException("loadAll failed to return a value for " + key);
                        }
                        result.put(key, value);
                    }
                } catch (UnsupportedLoadingOperationException e) {
                    // loadAll not implemented, fallback to load
                    for (K key : keysToLoad) {
                        misses--; // get will count this miss
                        result.put(key, get(key, defaultLoader));
                    }
                }
            }
            return ImmutableMap.copyOf(result);
        } finally {
            globalStatsCounter.recordHits(hits);
            globalStatsCounter.recordMisses(misses);
        }
    }

    /**
     * Returns the result of calling {@link CacheLoader#loadAll}, or null if {@code loader} doesn't implement
     * {@code loadAll}.
     */
    @Nullable
    Map<K, V> loadAll(Set<? extends K> keys, CacheLoader<? super K, V> loader) throws ExecutionException {
        checkNotNull(loader);
        checkNotNull(keys);
        Stopwatch stopwatch = Stopwatch.createStarted();
        Map<K, V> result;
        boolean success = false;
        try {
            @SuppressWarnings("unchecked")
            // safe since all keys extend K
            Map<K, V> map = (Map<K, V>) loader.loadAll(keys);
            result = map;
            success = true;
        } catch (UnsupportedLoadingOperationException e) {
            success = true;
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(e);
        } catch (RuntimeException e) {
            throw new UncheckedExecutionException(e);
        } catch (Exception e) {
            throw new ExecutionException(e);
        } catch (Error e) {
            throw new ExecutionError(e);
        } finally {
            if (!success) {
                globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            }
        }

        if (result == null) {
            globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            throw new InvalidCacheLoadException(loader + " returned null map from loadAll");
        }

        stopwatch.stop();
        // TODO(fry): batch by segment
        boolean nullsPresent = false;
        for (Map.Entry<K, V> entry : result.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            if (key == null || value == null) {
                // delay failure until non-null entries are stored
                nullsPresent = true;
            } else {
                put(key, value);
            }
        }

        if (nullsPresent) {
            globalStatsCounter.recordLoadException(stopwatch.elapsed(NANOSECONDS));
            throw new InvalidCacheLoadException(loader + " returned null keys or values from loadAll");
        }

        // TODO(fry): record count of loaded entries
        globalStatsCounter.recordLoadSuccess(stopwatch.elapsed(NANOSECONDS));
        return result;
    }

    /**
     * Returns the internal entry for the specified key. The entry may be loading, expired, or partially collected.
     */
    ReferenceEntry<K, V> getEntry(@Nullable Object key) {
        // does not impact recency ordering
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).getEntry(key, hash);
    }

    void refresh(K key) {
        int hash = hash(checkNotNull(key));
        segmentFor(hash).refresh(key, hash, defaultLoader, false);
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        // does not impact recency ordering
        if (key == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).containsKey(key, hash);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        // does not impact recency ordering
        if (value == null) {
            return false;
        }

        // This implementation is patterned after ConcurrentHashMap, but without the locking. The only
        // way for it to return a false negative would be for the target value to jump around in the map
        // such that none of the subsequent iterations observed it, despite the fact that at every point
        // in time it was present somewhere int the map. This becomes increasingly unlikely as
        // CONTAINS_VALUE_RETRIES increases, though without locking it is theoretically possible.
        long now = ticker.read();
        final Segment<K, V>[] segments = this.segments;
        long last = -1L;
        for (int i = 0; i < CONTAINS_VALUE_RETRIES; i++) {
            long sum = 0L;
            for (Segment<K, V> segment : segments) {
                // ensure visibility of most recent completed write
                @SuppressWarnings({ "UnusedDeclaration", "unused" })
                int c = segment.count; // read-volatile 这条语句存在的唯一目的就是保证segments[i].modCount读取到几乎最新的值

                AtomicReferenceArray<ReferenceEntry<K, V>> table = segment.table;
                for (int j = 0; j < table.length(); j++) {
                    for (ReferenceEntry<K, V> e = table.get(j); e != null; e = e.getNext()) {
                        V v = segment.getLiveValue(e, now);
                        if (v != null && valueEquivalence.equivalent(value, v)) {
                            return true;
                        }
                    }
                }
                sum += segment.modCount;
            }
            if (sum == last) {
                break;
            }
            last = sum;
        }
        return false;
    }

    @Override
    public V put(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).put(key, hash, value, false);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).put(key, hash, value, true);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public V remove(@Nullable Object key) {
        if (key == null) {
            return null;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash);
    }

    @Override
    public boolean remove(@Nullable Object key, @Nullable Object value) {
        if (key == null || value == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).remove(key, hash, value);
    }

    @Override
    public boolean replace(K key, @Nullable V oldValue, V newValue) {
        checkNotNull(key);
        checkNotNull(newValue);
        if (oldValue == null) {
            return false;
        }
        int hash = hash(key);
        return segmentFor(hash).replace(key, hash, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        checkNotNull(key);
        checkNotNull(value);
        int hash = hash(key);
        return segmentFor(hash).replace(key, hash, value);
    }

    @Override
    public void clear() {
        for (Segment<K, V> segment : segments) {
            segment.clear();
        }
    }

    void invalidateAll(Iterable<?> keys) {
        // TODO(fry): batch by segment
        for (Object key : keys) {
            remove(key);
        }
    }

    Set<K> keySet;

    @Override
    public Set<K> keySet() {
        // does not impact recency ordering
        Set<K> ks = keySet;
        return (ks != null) ? ks : (keySet = new KeySet(this));
    }

    Collection<V> values;

    @Override
    public Collection<V> values() {
        // does not impact recency ordering
        Collection<V> vs = values;
        return (vs != null) ? vs : (values = new Values(this));
    }

    Set<Entry<K, V>> entrySet;

    @Override
    @GwtIncompatible("Not supported.")
    public Set<Entry<K, V>> entrySet() {
        // does not impact recency ordering
        Set<Entry<K, V>> es = entrySet;
        return (es != null) ? es : (entrySet = new EntrySet(this));
    }

    // Iterator Support

    abstract class HashIterator<T> implements Iterator<T> {

        int nextSegmentIndex;
        int nextTableIndex;
        Segment<K, V> currentSegment;
        AtomicReferenceArray<ReferenceEntry<K, V>> currentTable;
        ReferenceEntry<K, V> nextEntry;
        WriteThroughEntry nextExternal;
        WriteThroughEntry lastReturned;

        HashIterator() {
            nextSegmentIndex = segments.length - 1;
            nextTableIndex = -1;
            advance();
        }

        @Override
        public abstract T next();

        final void advance() {
            nextExternal = null;

            if (nextInChain()) {
                return;
            }

            if (nextInTable()) {
                return;
            }

            while (nextSegmentIndex >= 0) {
                currentSegment = segments[nextSegmentIndex--];
                if (currentSegment.count != 0) {
                    currentTable = currentSegment.table;
                    nextTableIndex = currentTable.length() - 1;
                    if (nextInTable()) {
                        return;
                    }
                }
            }
        }

        /**
         * Finds the next entry in the current chain. Returns true if an entry was found.
         */
        boolean nextInChain() {
            if (nextEntry != null) {
                for (nextEntry = nextEntry.getNext(); nextEntry != null; nextEntry = nextEntry.getNext()) {
                    if (advanceTo(nextEntry)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Finds the next entry in the current table. Returns true if an entry was found.
         */
        boolean nextInTable() {
            while (nextTableIndex >= 0) {
                if ((nextEntry = currentTable.get(nextTableIndex--)) != null) {
                    if (advanceTo(nextEntry) || nextInChain()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Advances to the given entry. Returns true if the entry was valid, false if it should be skipped.
         */
        boolean advanceTo(ReferenceEntry<K, V> entry) {
            try {
                long now = ticker.read();
                K key = entry.getKey();
                V value = getLiveValue(entry, now);
                if (value != null) {
                    nextExternal = new WriteThroughEntry(key, value);
                    return true;
                } else {
                    // Skip stale entry.
                    return false;
                }
            } finally {
                currentSegment.postReadCleanup();
            }
        }

        @Override
        public boolean hasNext() {
            return nextExternal != null;
        }

        WriteThroughEntry nextEntry() {
            if (nextExternal == null) {
                throw new NoSuchElementException();
            }
            lastReturned = nextExternal;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            checkState(lastReturned != null);
            LocalCache.this.remove(lastReturned.getKey());
            lastReturned = null;
        }
    }

    final class KeyIterator extends HashIterator<K> {

        @Override
        public K next() {
            return nextEntry().getKey();
        }
    }

    final class ValueIterator extends HashIterator<V> {

        @Override
        public V next() {
            return nextEntry().getValue();
        }
    }

    /**
     * EntryIterator.next() 使用的自定义Entry类，将使用setValue转换为基础的map Custom Entry class used by EntryIterator.next(), that
     * relays setValue changes to the underlying map.
     */
    final class WriteThroughEntry implements Entry<K, V> {
        final K key; // non-null
        V value; // non-null

        WriteThroughEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public boolean equals(@Nullable Object object) {
            // Cannot use key and value equivalence
            if (object instanceof Entry) {
                Entry<?, ?> that = (Entry<?, ?>) object;
                return key.equals(that.getKey()) && value.equals(that.getValue());
            }
            return false;
        }

        @Override
        public int hashCode() {
            // Cannot use key and value equivalence
            return key.hashCode() ^ value.hashCode();
        }

        @Override
        public V setValue(V newValue) {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns a string representation of the form <code>{key}={value}</code>.
         */
        @Override
        public String toString() {
            return getKey() + "=" + getValue();
        }
    }

    final class EntryIterator extends HashIterator<Entry<K, V>> {

        @Override
        public Entry<K, V> next() {
            return nextEntry();
        }
    }

    abstract class AbstractCacheSet<T> extends AbstractSet<T> {
        final ConcurrentMap<?, ?> map;

        AbstractCacheSet(ConcurrentMap<?, ?> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }
    }

    final class KeySet extends AbstractCacheSet<K> {

        KeySet(ConcurrentMap<?, ?> map) {
            super(map);
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return map.remove(o) != null;
        }
    }

    final class Values extends AbstractCollection<V> {
        private final ConcurrentMap<?, ?> map;

        Values(ConcurrentMap<?, ?> map) {
            this.map = map;
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(Object o) {
            return map.containsValue(o);
        }
    }

    final class EntrySet extends AbstractCacheSet<Entry<K, V>> {

        EntrySet(ConcurrentMap<?, ?> map) {
            super(map);
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry<?, ?> e = (Entry<?, ?>) o;
            Object key = e.getKey();
            if (key == null) {
                return false;
            }
            V v = LocalCache.this.get(key);

            return v != null && valueEquivalence.equivalent(e.getValue(), v);
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry<?, ?> e = (Entry<?, ?>) o;
            Object key = e.getKey();
            return key != null && LocalCache.this.remove(key, e.getValue());
        }
    }

    // Serialization Support

    /**
     * Serializes the configuration of a LocalCache, reconsitituting it as a Cache using CacheBuilder upon
     * deserialization. An instance of this class is fit for use by the writeReplace of LocalManualCache.
     * 
     * Unfortunately, readResolve() doesn't get called when a circular dependency is present, so the proxy must be able
     * to behave as the cache itself.
     */
    static class ManualSerializationProxy<K, V> extends ForwardingCache<K, V> implements Serializable {
        private static final long serialVersionUID = 1;

        final Strength keyStrength;
        final Strength valueStrength;
        final Equivalence<Object> keyEquivalence;
        final Equivalence<Object> valueEquivalence;
        final long expireAfterWriteNanos;
        final long expireAfterAccessNanos;
        final long maxWeight;
        final Weigher<K, V> weigher;
        final int concurrencyLevel;
        final RemovalListener<? super K, ? super V> removalListener;
        final Ticker ticker;
        final CacheLoader<? super K, V> loader;

        transient Cache<K, V> delegate;

        ManualSerializationProxy(LocalCache<K, V> cache) {
            this(cache.keyStrength, cache.valueStrength, cache.keyEquivalence, cache.valueEquivalence,
                    cache.expireAfterWriteNanos, cache.expireAfterAccessNanos, cache.maxWeight, cache.weigher,
                    cache.concurrencyLevel, cache.removalListener, cache.ticker, cache.defaultLoader);
        }

        private ManualSerializationProxy(Strength keyStrength, Strength valueStrength,
                Equivalence<Object> keyEquivalence, Equivalence<Object> valueEquivalence, long expireAfterWriteNanos,
                long expireAfterAccessNanos, long maxWeight, Weigher<K, V> weigher, int concurrencyLevel,
                RemovalListener<? super K, ? super V> removalListener, Ticker ticker, CacheLoader<? super K, V> loader) {
            this.keyStrength = keyStrength;
            this.valueStrength = valueStrength;
            this.keyEquivalence = keyEquivalence;
            this.valueEquivalence = valueEquivalence;
            this.expireAfterWriteNanos = expireAfterWriteNanos;
            this.expireAfterAccessNanos = expireAfterAccessNanos;
            this.maxWeight = maxWeight;
            this.weigher = weigher;
            this.concurrencyLevel = concurrencyLevel;
            this.removalListener = removalListener;
            this.ticker = (ticker == Ticker.systemTicker() || ticker == NULL_TICKER) ? null : ticker;
            this.loader = loader;
        }

        CacheBuilder<K, V> recreateCacheBuilder() {
            CacheBuilder<K, V> builder = CacheBuilder.newBuilder().setKeyStrength(keyStrength)
                    .setValueStrength(valueStrength).keyEquivalence(keyEquivalence).valueEquivalence(valueEquivalence)
                    .concurrencyLevel(concurrencyLevel).removalListener(removalListener);
            builder.strictParsing = false;
            if (expireAfterWriteNanos > 0) {
                builder.expireAfterWrite(expireAfterWriteNanos, TimeUnit.NANOSECONDS);
            }
            if (expireAfterAccessNanos > 0) {
                builder.expireAfterAccess(expireAfterAccessNanos, TimeUnit.NANOSECONDS);
            }
            if (weigher != OneWeigher.INSTANCE) {
                builder.weigher(weigher);
                if (maxWeight != UNSET_INT) {
                    builder.maximumWeight(maxWeight);
                }
            } else {
                if (maxWeight != UNSET_INT) {
                    builder.maximumSize(maxWeight);
                }
            }
            if (ticker != null) {
                builder.ticker(ticker);
            }
            return builder;
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            CacheBuilder<K, V> builder = recreateCacheBuilder();
            this.delegate = builder.build();
        }

        private Object readResolve() {
            return delegate;
        }

        @Override
        protected Cache<K, V> delegate() {
            return delegate;
        }
    }

    /**
     * Serializes the configuration of a LocalCache, reconsitituting it as an LoadingCache using CacheBuilder upon
     * deserialization. An instance of this class is fit for use by the writeReplace of LocalLoadingCache.
     * 
     * Unfortunately, readResolve() doesn't get called when a circular dependency is present, so the proxy must be able
     * to behave as the cache itself.
     */
    static final class LoadingSerializationProxy<K, V> extends ManualSerializationProxy<K, V> implements
            LoadingCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        transient LoadingCache<K, V> autoDelegate;

        LoadingSerializationProxy(LocalCache<K, V> cache) {
            super(cache);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            CacheBuilder<K, V> builder = recreateCacheBuilder();
            this.autoDelegate = builder.build(loader);
        }

        @Override
        public V get(K key) throws ExecutionException {
            return autoDelegate.get(key);
        }

        @Override
        public V getUnchecked(K key) {
            return autoDelegate.getUnchecked(key);
        }

        @Override
        public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
            return autoDelegate.getAll(keys);
        }

        @Override
        public final V apply(K key) {
            return autoDelegate.apply(key);
        }

        @Override
        public void refresh(K key) {
            autoDelegate.refresh(key);
        }

        private Object readResolve() {
            return autoDelegate;
        }
    }

    static class LocalManualCache<K, V> implements Cache<K, V>, Serializable {
        final LocalCache<K, V> localCache;

        LocalManualCache(CacheBuilder<? super K, ? super V> builder) {
            this(new LocalCache<K, V>(builder, null));
        }

        private LocalManualCache(LocalCache<K, V> localCache) {
            this.localCache = localCache;
        }

        // Cache methods

        @Override
        @Nullable
        public V getIfPresent(Object key) {
            return localCache.getIfPresent(key);
        }

        @Override
        public V get(K key, final Callable<? extends V> valueLoader) throws ExecutionException {
            checkNotNull(valueLoader);
            return localCache.get(key, new CacheLoader<Object, V>() {
                @Override
                public V load(Object key) throws Exception {
                    return valueLoader.call();
                }
            });
        }

        @Override
        public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
            return localCache.getAllPresent(keys);
        }

        @Override
        public void put(K key, V value) {
            localCache.put(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            localCache.putAll(m);
        }

        @Override
        public void invalidate(Object key) {
            checkNotNull(key);
            localCache.remove(key);
        }

        @Override
        public void invalidateAll(Iterable<?> keys) {
            localCache.invalidateAll(keys);
        }

        @Override
        public void invalidateAll() {
            localCache.clear();
        }

        @Override
        public long size() {
            return localCache.longSize();
        }

        @Override
        public ConcurrentMap<K, V> asMap() {
            return localCache;
        }

        @Override
        public CacheStats stats() {
            SimpleStatsCounter aggregator = new SimpleStatsCounter();
            aggregator.incrementBy(localCache.globalStatsCounter);
            for (Segment<K, V> segment : localCache.segments) {
                aggregator.incrementBy(segment.statsCounter);
            }
            return aggregator.snapshot();
        }

        @Override
        public void cleanUp() {
            localCache.cleanUp();
        }

        // Serialization Support

        private static final long serialVersionUID = 1;

        Object writeReplace() {
            return new ManualSerializationProxy<K, V>(localCache);
        }
    }

    static class LocalLoadingCache<K, V> extends LocalManualCache<K, V> implements LoadingCache<K, V> {

        LocalLoadingCache(CacheBuilder<? super K, ? super V> builder, CacheLoader<? super K, V> loader) {
            super(new LocalCache<K, V>(builder, checkNotNull(loader)));
        }

        // LoadingCache methods

        @Override
        public V get(K key) throws ExecutionException {
            return localCache.getOrLoad(key);
        }

        @Override
        public V getUnchecked(K key) {
            try {
                return get(key);
            } catch (ExecutionException e) {
                throw new UncheckedExecutionException(e.getCause());
            }
        }

        @Override
        public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
            return localCache.getAll(keys);
        }

        @Override
        public void refresh(K key) {
            localCache.refresh(key);
        }

        @Override
        public final V apply(K key) {
            return getUnchecked(key);
        }

        // Serialization Support

        private static final long serialVersionUID = 1;

        @Override
        Object writeReplace() {
            return new LoadingSerializationProxy<K, V>(localCache);
        }
    }
}
