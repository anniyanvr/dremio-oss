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
package com.dremio.service.jobs;

import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchHolder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Set of {@link RecordBatchHolder}s. */
public final class RecordBatches {
  private final List<RecordBatchHolder> batches;
  private final BatchSchema schema;
  private final int size;

  public RecordBatches(final List<RecordBatchHolder> batches) {
    Preconditions.checkArgument(batches != null && batches.size() >= 1);
    this.batches = ImmutableList.copyOf(batches);

    int size = 0;
    if (batches != null) {
      for (RecordBatchHolder batch : batches) {
        size += batch.size();
      }
    }
    this.size = size;
    this.schema = batches.get(0).getData().getSchema();
  }

  /**
   * @return list of data batches.
   */
  public List<RecordBatchHolder> getBatches() {
    return batches;
  }

  /**
   * Get the schema of the records in batches.
   *
   * @return
   */
  public BatchSchema getSchema() {
    return schema;
  }

  /**
   * Return the total number of records represented in all batches.
   *
   * @return
   */
  public int getSize() {
    return size;
  }
}
