/*
 * Tencent is pleased to support the open source community by making
 * Firestorm-Spark remote shuffle server available. 
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.rss.storage.handler.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.tencent.rss.common.BufferSegment;
import com.tencent.rss.common.ShuffleDataResult;
import com.tencent.rss.storage.HdfsTestBase;
import com.tencent.rss.storage.common.FileBasedShuffleSegment;
import org.apache.hadoop.fs.Path;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.fail;

public class MultiStorageHdfsClientReadHandlerTest extends HdfsTestBase {

  @Test
  public void handlerReadUnCombinedDataTest() {
    try {
      String basePath = HDFS_URI + "test_backup_read";
      Path combinePath = new Path(basePath + "/app1/0/combine");
      fs.mkdirs(combinePath);
      Path partitionPath = new Path(basePath + "/app1/0/1");
      fs.mkdirs(partitionPath);

      Path dataPath = new Path(basePath + "/app1/0/1/2.data");
      HdfsFileWriter writer = new HdfsFileWriter(dataPath, conf);
      byte[] data = writeData(writer);
      writer.close();

      writeIndexData(basePath, "/app1/0/1/2.index", Lists.newArrayList());
      List<Long> dataSizes;

      Path combineDataPath = new Path(basePath + "/app1/0/combine/1.data");
      Path combineIndexPath = new Path(basePath + "/app1/0/combine/1.index");
      HdfsFileWriter combineWriter = new HdfsFileWriter(combineDataPath, conf);
      HdfsFileWriter combineIndexWriter = new HdfsFileWriter(combineIndexPath, conf);

      writeData(combineWriter);
      writeData(combineWriter);

      writePartition2(combineWriter, combineIndexWriter);
      combineIndexWriter.close();
      combineWriter.close();

      Set<Long> expects = Sets.newHashSet();
      expects.add(1L);
      List<byte[]> expectData = Lists.newArrayList();
      expectData.add(data);
      compareDataAndIndex("app1", 0, 1, basePath, expectData, 2);
    } catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  @Test
  public void handlerReadCombinedDataTest() {
    try {
      String basePath = HDFS_URI + "test_backup_read";
      Path combinePath = new Path(basePath + "/app2/0/combine");
      fs.mkdirs(combinePath);
      Path partitionPath = new Path(basePath + "/app2/0/1");
      fs.mkdirs(partitionPath);
      Path dataPath = new Path(basePath + "/app2/0/1/1.data");

      HdfsFileWriter writer = new HdfsFileWriter(dataPath, conf);
      writeData(writer);
      writer.close();
      List<Long> dataSizes = Lists.newArrayList();
      writeIndexData(basePath,  "/app2/0/1/1.index", dataSizes);

      Path combineDataPath = new Path(basePath + "/app2/0/combine/1.data");
      Path combineIndexPath = new Path(basePath + "/app2/0/combine/1.index");
      HdfsFileWriter combineWriter = new HdfsFileWriter(combineDataPath, conf);
      HdfsFileWriter combineIndexWriter = new HdfsFileWriter(combineIndexPath, conf);

      byte[] data1 = writeData(combineWriter);

      writeData(combineWriter);
      writePartition(dataSizes, combineIndexWriter);
      combineIndexWriter.close();
      combineWriter.close();

      Set<Long> expects = Sets.newHashSet();
      expects.add(2L);
      List<byte[]> expectData = Lists.newArrayList();
      expectData.add(data1);
      compareDataAndIndex("app2", 0, 2, basePath, expectData, 2);
    } catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }


  @Test
  public void handlerReadTwoKindsDataTest() {
    try {
      String basePath = HDFS_URI + "test_backup_read";
      Path combinePath = new Path(basePath + "/app4/0/combine");
      fs.mkdirs(combinePath);
      Path partitionPath = new Path(basePath + "/app4/0/2");
      fs.mkdirs(partitionPath);
      Path dataPath = new Path(basePath + "/app4/0/2/2.data");

      HdfsFileWriter writer = new HdfsFileWriter(dataPath, conf);
      byte[] data = writeData(writer);
      writer.close();
      Path indexPath = new Path(basePath + "/app4/0/2/2.index");
      HdfsFileWriter iWriter = new HdfsFileWriter(indexPath, conf);
      List<Integer> somePartitions = Lists.newArrayList();
      somePartitions.add(2);
      List<Long> someSizes = Lists.newArrayList();
      someSizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
      List<Long> dataSizes = Lists.newArrayList();
      dataSizes.add(256L);
      iWriter.writeHeader(somePartitions, someSizes, dataSizes);
      FileBasedShuffleSegment segment = new FileBasedShuffleSegment(
          1, 0, 256, 256, 1, 1);
      iWriter.writeIndex(segment);
      iWriter.close();

      Path combineDataPath = new Path(basePath + "/app4/0/combine/1.data");
      Path combineIndexPath = new Path(basePath + "/app4/0/combine/1.index");
      HdfsFileWriter combineWriter = new HdfsFileWriter(combineDataPath, conf);
      HdfsFileWriter combineIndexWriter = new HdfsFileWriter(combineIndexPath, conf);

      byte[] data1 = writeData(combineWriter);
      writeData(combineWriter);
      writePartition(dataSizes, combineIndexWriter);
      combineIndexWriter.close();
      combineWriter.close();

      Set<Long> expects = Sets.newHashSet();
      expects.add(2L);
      List<byte[]> expectData = Lists.newArrayList();
      expectData.add(data1);
      expectData.add(data);
      compareDataAndIndex("app4", 0, 2, basePath, expectData, 2);
    } catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  @Test
  public void readWithDifferentLimitTest() {
    try {
      List<byte[]> expectData = Lists.newArrayList();
      String basePath = HDFS_URI + "test_limit_read";
      Path partitionPath = new Path(basePath + "/app3/0/combine");
      fs.mkdirs(partitionPath);
      Path dataPath = new Path(basePath + "/app3/0/combine/1.data");
      HdfsFileWriter writer = new HdfsFileWriter(dataPath, conf);

      byte[] data = writeData(writer);
      ByteBuffer buffer;
      for (int i = 0; i < 5; i++) {
        new Random().nextBytes(data);
        buffer = ByteBuffer.allocate(data.length);
        buffer.put(data);
        buffer.flip();
        expectData.add(data.clone());
        writer.writeData(buffer);
      }
      buffer = ByteBuffer.allocate(data.length);
      new Random().nextBytes(data);
      buffer.put(data);
      buffer.flip();
      writer.writeData(buffer);
      writer.close();

      Path indexPath = new Path(basePath + "/app3/0/combine/1.index");
      HdfsFileWriter iWriter = new HdfsFileWriter(indexPath, conf);
      writePartition3(iWriter);
      iWriter.close();
      compareDataAndIndex("app3", 0, 1, basePath, expectData, 1);
      compareDataAndIndex("app3", 0, 1, basePath, expectData, 2);
      compareDataAndIndex("app3", 0, 1, basePath, expectData, 3);
    } catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  private void writeIndexData(
      String basePath,
      String file,
      List<Long> dataSizes) throws IOException {
    Path indexPath = new Path(basePath + file);
    HdfsFileWriter iWriter = new HdfsFileWriter(indexPath, conf);
    List<Integer> somePartitions = Lists.newArrayList();
    somePartitions.add(1);
    List<Long> someSizes = Lists.newArrayList();
    someSizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
    dataSizes.clear();
    dataSizes.add(256L);
    iWriter.writeHeader(somePartitions, someSizes, dataSizes);
    FileBasedShuffleSegment segment = new FileBasedShuffleSegment(
        1, 0, 256, 256, 1, 1);
    iWriter.writeIndex(segment);
    iWriter.close();
  }

  private void writePartition(
      List<Long> dataSizes,
      HdfsFileWriter combineIndexWriter) throws IOException {
    List<Integer> partitions = Lists.newArrayList();
    partitions.add(2);
    partitions.add(3);
    List<Long> sizes = Lists.newArrayList();
    sizes.add((long) FileBasedShuffleSegment.SEGMENT_SIZE);
    sizes.add((long) FileBasedShuffleSegment.SEGMENT_SIZE);
    dataSizes.add(256L);
    dataSizes.add(256L);
    combineIndexWriter.writeHeader(partitions, sizes, dataSizes);
    FileBasedShuffleSegment segment1 = new FileBasedShuffleSegment(
        2, 0, 256, 256, 2, 1);
    combineIndexWriter.writeIndex(segment1);
    FileBasedShuffleSegment segment2 = new FileBasedShuffleSegment(
        3, 256, 256, 256, 3, 1);
    combineIndexWriter.writeIndex(segment2);
  }

  private void writePartition2(HdfsFileWriter combineWriter, HdfsFileWriter combineIndexWriter) throws IOException {
    List<Long> dataSizes;
    List<Integer> partitions = Lists.newArrayList();
    partitions.add(2);
    partitions.add(3);
    List<Long> sizes = Lists.newArrayList();
    sizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
    sizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
    dataSizes = Lists.newArrayList();
    dataSizes.add(256L);
    dataSizes.add(256L);
    combineIndexWriter.writeHeader(partitions, sizes, dataSizes);
    FileBasedShuffleSegment segment1 = new FileBasedShuffleSegment(
        2, 0, 256, 256, 1, 1);
    combineIndexWriter.writeIndex(segment1);
    FileBasedShuffleSegment segment2 = new FileBasedShuffleSegment(
        3, 256, 256, 256, 1, 1);
    combineIndexWriter.writeIndex(segment2);
  }

  private void writePartition3(HdfsFileWriter iWriter) throws IOException {
    List<Integer> somePartitions = Lists.newArrayList();
    somePartitions.add(0);
    somePartitions.add(1);
    somePartitions.add(2);
    List<Long> someSizes = Lists.newArrayList();
    someSizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
    someSizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE * 5);
    someSizes.add((long)FileBasedShuffleSegment.SEGMENT_SIZE);
    List<Long> dataSizes = Lists.newArrayList();
    dataSizes.add(256L);
    dataSizes.add(256L * 5);
    dataSizes.add(256L);
    iWriter.writeHeader(somePartitions, someSizes, dataSizes);
    FileBasedShuffleSegment segment = new FileBasedShuffleSegment(1, 0, 256, 256, 1, 1L);
    iWriter.writeIndex(segment);
    for (int i = 0; i < 5; i++) {
      segment = new FileBasedShuffleSegment(i + 2, i * 256, 256, 256, 1, 1L);
      iWriter.writeIndex(segment);
    }
    segment = new FileBasedShuffleSegment(7, 0, 256, 256, 1, 1L);
    iWriter.writeIndex(segment);
  }

  private void compareDataAndIndex(
      String appId,
      int shuffleId,
      int partitionId,
      String basePath,
      List<byte[]> expectedData,
      int limit) throws IllegalStateException {
    // read directly and compare
    MultiStorageHdfsClientReadHandler handler = new MultiStorageHdfsClientReadHandler(appId,
        shuffleId, partitionId, limit, 1, 3, 1024,
        basePath, conf);
    try {
      List<ByteBuffer> actual = readData(handler);
      compareBytes(expectedData, actual);
    } finally {
      handler.close();
    }
  }

  private byte[] writeData(HdfsFileWriter combineWriter) throws IOException {
    byte[] data = new byte[256];
    new Random().nextBytes(data);
    ByteBuffer buffer = ByteBuffer.allocate(data.length);
    buffer.put(data);
    buffer.flip();
    combineWriter.writeData(buffer);
    return data;
  }

  private List<ByteBuffer> readData(MultiStorageHdfsClientReadHandler handler) throws IllegalStateException {
    ShuffleDataResult sdr;
    List<ByteBuffer> result = Lists.newArrayList();
    int index = 0;
    do {
      sdr = handler.readShuffleData(index);
      if (sdr == null || sdr.getData() == null) {
        break;
      }
      List<BufferSegment> bufferSegments = sdr.getBufferSegments();
      for (BufferSegment bs : bufferSegments) {
        byte[] data = new byte[bs.getLength()];
        System.arraycopy(sdr.getData(), bs.getOffset(), data, 0, bs.getLength());
        result.add(ByteBuffer.wrap(data));
      }
      index++;
    } while(sdr.getData() != null);
    return result;
  }
}