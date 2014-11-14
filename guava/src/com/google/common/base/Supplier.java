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

package com.google.common.base;

import com.google.common.annotations.GwtCompatible;

/**
 * 这个类可以提供单个类型的对象s.理论上，这可以是一个工厂，生成器（generatoy），构造器（builder），闭包（closure）
 * 或者其他相似的东西。这个接口没有隐式保证任何东西。
 *
 * @author Harry Heymann
 * @since 2.0 (imported from Google Collections Library)
 */
@GwtCompatible
public interface Supplier<T> {
  /**
   * 获取一个相应类型的对象。返回的对象可以使也可以不是一个新的实例，依赖各自的实现。
   * 
   * @return an instance of the appropriate type
   */
  T get();
}
