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
package com.dremio.exec.store.parquet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.dremio.BaseTestQuery;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.WritePartition;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.test.AllocatorRule;

public class TestParquetRecordWriter extends BaseTestQuery {

  @Rule
  public final AllocatorRule allocatorRule = AllocatorRule.defaultAllocator();

  private ParquetRecordWriter mockParquetRecordWriter(Configuration hadoopConf, Path targetPath, int minorFragmentId, BufferAllocator ALLOCATOR, Long targetBlockSize) throws Exception {
    OptionManager optionManager = mock(OptionManager.class);
    when(optionManager.getOption(ExecConstants.PARQUET_WRITER_COMPRESSION_TYPE_VALIDATOR)).thenReturn("none"); //compression shouldn't matter
    when(optionManager.getOption(ExecConstants.PARQUET_PAGE_SIZE_VALIDATOR)).thenReturn(256L);
    when(optionManager.getOption(ExecConstants.PARQUET_MAXIMUM_PARTITIONS_VALIDATOR)).thenReturn(1L);
    when(optionManager.getOption(ExecConstants.PARQUET_DICT_PAGE_SIZE_VALIDATOR)).thenReturn(4096L);
    when(optionManager.getOption(ExecConstants.PARQUET_BLOCK_SIZE_VALIDATOR)).thenReturn(256 * 1024 * 1024L);

    OperatorStats operatorStats = mock(OperatorStats.class);

    OperatorContext opContext = mock(OperatorContext.class);
    when(opContext.getFragmentHandle()).thenReturn(ExecProtos.FragmentHandle.newBuilder().setMajorFragmentId(2323).setMinorFragmentId(minorFragmentId).build());
    when(opContext.getAllocator()).thenReturn(ALLOCATOR);
    when(opContext.getOptions()).thenReturn(optionManager);
    when(opContext.getStats()).thenReturn(operatorStats);

    ParquetWriter writerConf = mock(ParquetWriter.class, Mockito.RETURNS_DEEP_STUBS);
    when(writerConf.getLocation()).thenReturn(targetPath.toUri().toString());
    OpProps props = mock(OpProps.class);
    when(writerConf.getProps()).thenReturn(props);
    when(writerConf.getProps().getUserName()).thenReturn("testuser");
    when(writerConf.getOptions().getTableFormatOptions().getTargetFileSize()).thenReturn(targetBlockSize);

    FileSystemPlugin fsPlugin = BaseTestQuery.getMockedFileSystemPlugin();
    when(fsPlugin.createFS((String) notNull(), (String) notNull(), (OperatorContext) notNull())).thenReturn(HadoopFileSystem.getLocal(hadoopConf));
    when(fsPlugin.getFsConfCopy()).thenReturn(hadoopConf);
    when(writerConf.getPlugin()).thenReturn(fsPlugin);

    return new ParquetRecordWriter(opContext, writerConf, new ParquetFormatConfig());
  }

  @Test
  public void testFileSize() throws Exception {
    final Path tmpSchemaPath = new Path(getDfsTestTmpSchemaLocation());
    final Path targetPath = new Path(tmpSchemaPath, "testFileSize");

    final Configuration hadoopConf = new Configuration();
    final FileSystem newFs = targetPath.getFileSystem(hadoopConf);
    assertTrue(newFs.mkdirs(targetPath));

    final BufferAllocator ALLOCATOR = allocatorRule.newAllocator("test-parquet-writer", 0, Long.MAX_VALUE);

    ParquetRecordWriter writer = mockParquetRecordWriter(hadoopConf, targetPath, 234234, ALLOCATOR, null);

    RecordWriter.OutputEntryListener outputEntryListener = mock(RecordWriter.OutputEntryListener.class);
    RecordWriter.WriteStatsListener writeStatsListener = mock(RecordWriter.WriteStatsListener.class);
    ArgumentCaptor<Long> recordWrittenCaptor = ArgumentCaptor.forClass(long.class);
    ArgumentCaptor<Long> fileSizeCaptor = ArgumentCaptor.forClass(long.class);
    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<byte[]> metadataCaptor = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<Integer> partitionCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<byte[]> icebergMetadataCaptor = ArgumentCaptor.forClass(byte[].class);

    BigIntVector bigIntVector = new BigIntVector("key", ALLOCATOR);
    bigIntVector.allocateNew(2);
    bigIntVector.set(0, 52459253098448904L);
    bigIntVector.set(1, 1116675951L);

    VectorContainer container = new VectorContainer();
    container.add(bigIntVector);
    container.setRecordCount(2);
    container.buildSchema(BatchSchema.SelectionVectorMode.NONE);

    writer.setup(container, outputEntryListener, writeStatsListener);
    writer.startPartition(WritePartition.NONE);
    writer.writeBatch(0, container.getRecordCount());

    container.clear();
    writer.close();

    verify(outputEntryListener, times(1)).recordsWritten(recordWrittenCaptor.capture(),
      fileSizeCaptor.capture(), pathCaptor.capture(), metadataCaptor.capture(),
      partitionCaptor.capture(), icebergMetadataCaptor.capture(), any(), any(), any());

    for (FileStatus file : newFs.listStatus(targetPath)) {
      if (file.getPath().toString().endsWith(".parquet")) { //complex243_json is in here for some reason?
        assertEquals(Long.valueOf(fileSizeCaptor.getValue()), Long.valueOf(file.getLen()));
        break;
      }
    }

    container.close();
    ALLOCATOR.close();
  }

  @Test
  public void testBlockSizeWithTarget() throws Exception {
    final Path tmpSchemaPath = new Path(getDfsTestTmpSchemaLocation());
    final Path targetPath = new Path(tmpSchemaPath, "testFileSizeWithTarget");

    final Configuration hadoopConf = new Configuration();
    final FileSystem newFs = targetPath.getFileSystem(hadoopConf);
    assertTrue(newFs.mkdirs(targetPath));

    final BufferAllocator ALLOCATOR = allocatorRule.newAllocator("test-parquet-writer", 0, Long.MAX_VALUE);

    ParquetRecordWriter writer = mockParquetRecordWriter(hadoopConf, targetPath, 234236, ALLOCATOR, 100 * 1024 * 1024L);

    assertTrue(writer.getBlockSize() == 100 * 1024 * 1024L);

    writer.close();
    ALLOCATOR.close();
  }

  @Test
  public void testBlockSizeWithNullTarget() throws Exception {
    final Path tmpSchemaPath = new Path(getDfsTestTmpSchemaLocation());
    final Path targetPath = new Path(tmpSchemaPath, "testFileSizeWithTarget");

    final Configuration hadoopConf = new Configuration();
    final FileSystem newFs = targetPath.getFileSystem(hadoopConf);
    assertTrue(newFs.mkdirs(targetPath));

    final BufferAllocator ALLOCATOR = allocatorRule.newAllocator("test-parquet-writer", 0, Long.MAX_VALUE);

    ParquetRecordWriter writer = mockParquetRecordWriter(hadoopConf, targetPath, 234236, ALLOCATOR, null);

    assertTrue(writer.getBlockSize() == 256 * 1024 * 1024L);

    writer.close();
    ALLOCATOR.close();
  }

  @Test
  public void testBlockSizeWithInvalidTarget() throws Exception {
    final Path tmpSchemaPath = new Path(getDfsTestTmpSchemaLocation());
    final Path targetPath = new Path(tmpSchemaPath, "testFileSizeWithInvalidTarget");

    final Configuration hadoopConf = new Configuration();
    final FileSystem newFs = targetPath.getFileSystem(hadoopConf);
    assertTrue(newFs.mkdirs(targetPath));

    final BufferAllocator ALLOCATOR = allocatorRule.newAllocator("test-parquet-writer", 0, Long.MAX_VALUE);

    ParquetRecordWriter writer = mockParquetRecordWriter(hadoopConf, targetPath, 234236, ALLOCATOR, 0L);

    assertTrue(writer.getBlockSize() == 256 * 1024 * 1024L);

    writer.close();
    ALLOCATOR.close();
  }

  @Test
  public void testOutOfMemory() throws Exception {
    final Path tmpSchemaPath = new Path(getDfsTestTmpSchemaLocation());
    final Path targetPath = new Path(tmpSchemaPath, "testOutOfMemory");

    final Configuration hadoopConf = new Configuration();
    final FileSystem newFs = targetPath.getFileSystem(hadoopConf);
    assertTrue(newFs.mkdirs(targetPath));

    final BufferAllocator ALLOCATOR = allocatorRule.newAllocator("test-parquet-writer", 0, 128);


    ParquetRecordWriter writer = mockParquetRecordWriter(hadoopConf, targetPath, 234235, ALLOCATOR, null);

    RecordWriter.OutputEntryListener outputEntryListener = mock(RecordWriter.OutputEntryListener.class);
    RecordWriter.WriteStatsListener writeStatsListener = mock(RecordWriter.WriteStatsListener.class);

    BigIntVector bigIntVector = new BigIntVector("key", ALLOCATOR);
    bigIntVector.allocateNew(2);
    bigIntVector.set(0, 52459253098448904L);
    bigIntVector.set(1, 1116675951L);

    VectorContainer container = new VectorContainer();
    container.add(bigIntVector);
    container.setRecordCount(2);
    container.buildSchema(BatchSchema.SelectionVectorMode.NONE);

    writer.setup(container, outputEntryListener, writeStatsListener);
    writer.startPartition(WritePartition.NONE);
    writer.writeBatch(0, container.getRecordCount());

    container.clear();
    try {
      writer.close();
    } catch (Exception e) {
      // ignore any exception in close(), but all the buffers should be released.
    }

    container.close();
    ALLOCATOR.close();
  }
}
