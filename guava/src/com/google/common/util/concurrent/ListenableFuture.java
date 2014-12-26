/*
 * Copyright (C) 2007 The Guava Authors
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

package com.google.common.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * 参考链接：http://ifeve.com/google-guava-listenablefuture/
 *
 * Future尝试去实现一些监听器listener。每个监听器有一个关联的executor，
 * 然后一旦future的计算完成之后则使用这个executor调用执行listener。如果在listener加入的时候，计算已经完成了，
 * 那么这个listener会立刻执行。
 *
 * ListenableFuture继承自Future。其许多方法如果没有listener支持则不能实现功能。
 *
 * 可能会有直接调用addListener方法，但是这种方式不常见因为Runnable接口不提供直接访问Future结果。
 * 然而，直接调用addListener有时候还是有用的。比如：
 * <pre>   {@code
 *   final String name = ...;
 *   inFlight.add(name);
 *   ListenableFuture<Result> future = service.query(name);
 *   future.addListener(new Runnable() {
 *     public void run() {
 *       processedCount.incrementAndGet();
 *       inFlight.remove(name);
 *       lastProcessed.set(name);
 *       logger.info("Done with {0}", name);
 *     }
 *   }, executor);}</pre>
 *
 * <h3>How to get an instance</h3>
 *
 * 鼓励开发者从他们的方法返回ListenableFuture，这样用户可以利用公共创建类。比如，我们利用当前
 * 已经创建的Future实例来创建ListenableFuture。
 *
 * 从 ExecutorService 转换为ListeningExecutorService，使用MoreExecutors#listeningDecorator(ExecutorService)；
 * 如果调用FutureTask#set或者更简单的方法来填充，则创建SettableFuture（更复杂的是实现AbstractFuture）
 *
 * 直接创建ListenableFuture更高效和可靠，比JdkFutureAdapters而言。
 *
 * @author Sven Mawson
 * @author Nishant Thakkar
 * @since 1.0
 */
public interface ListenableFuture<V> extends Future<V> {
  /**
   * 注册一个listener成为Runnable来在给定的executor上执行。
   *
   * Registers a listener to be {@linkplain Executor#execute(Runnable) run} on
   * the given executor.  The listener will run when the {@code Future}'s
   * computation is {@linkplain Future#isDone() complete} or, if the computation
   * is already complete, immediately.
   *
   * <p>There is no guaranteed ordering of execution of listeners, but any
   * listener added through this method is guaranteed to be called once the
   * computation is complete.
   *
   * <p>Exceptions thrown by a listener will be propagated up to the executor.
   * Any exception thrown during {@code Executor.execute} (e.g., a {@code
   * RejectedExecutionException} or an exception thrown by {@linkplain
   * MoreExecutors#sameThreadExecutor inline execution}) will be caught and
   * logged.
   *
   * <p>Note: For fast, lightweight listeners that would be safe to execute in
   * any thread, consider {@link MoreExecutors#sameThreadExecutor}. For heavier
   * listeners, {@code sameThreadExecutor()} carries some caveats.  For
   * example, the listener may run on an unpredictable or undesirable thread:
   *
   * <ul>
   * <li>If this {@code Future} is done at the time {@code addListener} is
   * called, {@code addListener} will execute the listener inline.
   * <li>If this {@code Future} is not yet done, {@code addListener} will
   * schedule the listener to be run by the thread that completes this {@code
   * Future}, which may be an internal system thread such as an RPC network
   * thread.
   * </ul>
   *
   * <p>Also note that, regardless of which thread executes the
   * {@code sameThreadExecutor()} listener, all other registered but unexecuted
   * listeners are prevented from running during its execution, even if those
   * listeners are to run in other executors.
   *
   * <p>This is the most general listener interface. For common operations
   * performed using listeners, see {@link
   * com.google.common.util.concurrent.Futures}. For a simplified but general
   * listener interface, see {@link
   * com.google.common.util.concurrent.Futures#addCallback addCallback()}.
   *
   * @param listener the listener to run when the computation is complete
   * @param executor the executor to run the listener in
   * @throws NullPointerException if the executor or listener was null
   * @throws RejectedExecutionException if we tried to execute the listener
   *         immediately but the executor rejected it.
   */
  void addListener(Runnable listener, Executor executor);
}
