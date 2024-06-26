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
package com.dremio.exec.client;

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.client.QuerySubmitter.Format;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.proto.UserBitShared.QueryData;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.proto.UserBitShared.QueryResult.QueryState;
import com.dremio.exec.record.RecordBatchLoader;
import com.dremio.exec.rpc.ConnectionThrottle;
import com.dremio.exec.util.VectorUtil;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.dremio.sabot.rpc.user.UserResultsListener;
import com.google.common.base.Stopwatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocatorFactory;

public class PrintingResultsListener implements UserResultsListener {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(PrintingResultsListener.class);

  private final AtomicInteger count = new AtomicInteger();
  private final Stopwatch w = Stopwatch.createUnstarted();
  private final RecordBatchLoader loader;
  private final Format format;
  private final int columnWidth;
  private final BufferAllocator allocator;

  public PrintingResultsListener(SabotConfig config, Format format, int columnWidth) {
    this.allocator = RootAllocatorFactory.newRoot(config);
    this.loader = new RecordBatchLoader(allocator);
    this.format = format;
    this.columnWidth = columnWidth;
  }

  @Override
  public void submissionFailed(UserException ex) {
    System.out.println(
        "Exception (no rows returned): "
            + ex
            + ".  Returned in "
            + w.elapsed(TimeUnit.MILLISECONDS)
            + "ms.");
  }

  @Override
  public void queryCompleted(QueryState state) {
    AutoCloseables.closeNoChecked(allocator);
    System.out.println(
        "Total rows returned : "
            + count.get()
            + ".  Returned in "
            + w.elapsed(TimeUnit.MILLISECONDS)
            + "ms.");
  }

  @Override
  public void dataArrived(QueryDataBatch result, ConnectionThrottle throttle) {
    final QueryData header = result.getHeader();
    final ArrowBuf data = result.getData();

    if (data != null) {
      count.addAndGet(header.getRowCount());
      try {
        loader.load(header.getDef(), data);
        // TODO:  Clean:  DRILL-2933:  That load(...) no longer throws
        // SchemaChangeException, so check/clean catch clause below.
      } catch (SchemaChangeException e) {
        submissionFailed(UserException.systemError(e).build(logger));
      }

      switch (format) {
        case TABLE:
          VectorUtil.showVectorAccessibleContent(loader, columnWidth);
          break;
        case TSV:
          VectorUtil.showVectorAccessibleContent(loader, "\t");
          break;
        case CSV:
          VectorUtil.showVectorAccessibleContent(loader, ",");
          break;
      }
      loader.clear();
    }

    result.release();
  }

  @Override
  public void queryIdArrived(QueryId queryId) {
    w.start();
  }
}
