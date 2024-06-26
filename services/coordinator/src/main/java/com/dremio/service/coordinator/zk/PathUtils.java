/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.coordinator.zk;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/** A convenience class used to expedite zookeeper paths manipulations. */
public final class PathUtils {

  /**
   * Returns a normalized, combined path out of the given path segments.
   *
   * @param parts path segments to combine
   * @see #normalize(String)
   */
  public static final String join(final String... parts) {
    final StringBuilder sb = new StringBuilder();
    for (final String part : parts) {
      Preconditions.checkNotNull(part, "parts cannot contain null");
      if (!Strings.isNullOrEmpty(part)) {
        sb.append(part).append("/");
      }
    }
    if (sb.length() > 0) {
      sb.deleteCharAt(sb.length() - 1);
    }
    final String path = sb.toString();
    return normalize(path);
  }

  /**
   * Normalizes the given path eliminating repeated forward slashes.
   *
   * @return normalized path
   */
  public static final String normalize(final String path) {
    if (Strings.isNullOrEmpty(Preconditions.checkNotNull(path))) {
      return path;
    }

    final StringBuilder builder = new StringBuilder();
    char last = path.charAt(0);
    builder.append(last);
    for (int i = 1; i < path.length(); i++) {
      char cur = path.charAt(i);
      if (last == '/' && cur == last) {
        continue;
      }
      builder.append(cur);
      last = cur;
    }
    return builder.toString();
  }
}
