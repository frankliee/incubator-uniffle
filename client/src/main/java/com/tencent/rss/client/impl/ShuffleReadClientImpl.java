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

package com.tencent.rss.client.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.tencent.rss.client.api.ShuffleReadClient;
import com.tencent.rss.client.response.CompressedShuffleBlock;
import com.tencent.rss.common.BufferSegment;
import com.tencent.rss.common.ShuffleDataResult;
import com.tencent.rss.common.ShuffleServerInfo;
import com.tencent.rss.common.util.ChecksumUtils;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.common.util.RssUtils;
import com.tencent.rss.storage.factory.ShuffleHandlerFactory;
import com.tencent.rss.storage.handler.api.ClientReadHandler;
import com.tencent.rss.storage.request.CreateShuffleReadHandlerRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import com.tencent.rss.storage.util.StorageType;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShuffleReadClientImpl implements ShuffleReadClient {

  private static final Logger LOG = LoggerFactory.getLogger(ShuffleReadClientImpl.class);

  private int shuffleId;
  private int partitionId;
  private byte[] readBuffer;
  private Roaring64NavigableMap blockIdBitmap;
  private Roaring64NavigableMap taskIdBitmap;
  private Roaring64NavigableMap processedBlockIds = Roaring64NavigableMap.bitmapOf();
  private Queue<BufferSegment> bufferSegmentQueue = Queues.newLinkedBlockingQueue();
  private AtomicLong readDataTime = new AtomicLong(0);
  private AtomicLong copyTime = new AtomicLong(0);
  private AtomicLong crcCheckTime = new AtomicLong(0);
  private ClientReadHandler clientReadHandler;
  private int segmentIndex = 0;

  public ShuffleReadClientImpl(
      String storageType,
      String appId,
      int shuffleId,
      int partitionId,
      int indexReadLimit,
      int partitionNumPerRange,
      int partitionNum,
      int readBufferSize,
      String storageBasePath,
      Roaring64NavigableMap blockIdBitmap,
      Roaring64NavigableMap taskIdBitmap,
      List<ShuffleServerInfo> shuffleServerInfoList,
      Configuration hadoopConf) {
    this.shuffleId = shuffleId;
    this.partitionId = partitionId;
    this.blockIdBitmap = blockIdBitmap;
    this.taskIdBitmap = taskIdBitmap;
    CreateShuffleReadHandlerRequest request = new CreateShuffleReadHandlerRequest();
    request.setStorageType(storageType);
    request.setAppId(appId);
    request.setShuffleId(shuffleId);
    request.setPartitionId(partitionId);
    request.setIndexReadLimit(indexReadLimit);
    request.setPartitionNumPerRange(partitionNumPerRange);
    request.setPartitionNum(partitionNum);
    request.setReadBufferSize(readBufferSize);
    request.setStorageBasePath(storageBasePath);
    request.setShuffleServerInfoList(shuffleServerInfoList);
    request.setHadoopConf(hadoopConf);
    request.setExpectBlockIds(blockIdBitmap);
    request.setProcessBlockIds(processedBlockIds);
    List<Long> removeBlockIds = Lists.newArrayList();
    blockIdBitmap.forEach(bid -> {
        if (!taskIdBitmap.contains(bid & Constants.MAX_TASK_ATTEMPT_ID)) {
          removeBlockIds.add(bid);
        }
      }
    );
    for (long rid : removeBlockIds) {
      blockIdBitmap.removeLong(rid);
    }
    clientReadHandler = ShuffleHandlerFactory.getInstance().createShuffleReadHandler(request);
  }

  @Override
  public CompressedShuffleBlock readShuffleBlockData() {
    // empty data expected, just return null
    if (blockIdBitmap.isEmpty()) {
      return null;
    }

    // if need request new data from shuffle server
    if (bufferSegmentQueue.isEmpty()) {
      if (read() <= 0) {
        return null;
      }
    }
    // get next buffer segment
    BufferSegment bs = null;

    // blocks in bufferSegmentQueue may be from different partition in range partition mode,
    // or may be from speculation task, filter them and just read the necessary block
    while (true) {
      bs = bufferSegmentQueue.poll();
      if (bs == null) {
        break;
      }
      // check 1: if blockId is processed
      // check 2: if blockId is required for current partition
      // check 3: if blockId is generated by required task
      if (!processedBlockIds.contains(bs.getBlockId())
          && blockIdBitmap.contains(bs.getBlockId())
          && taskIdBitmap.contains(bs.getTaskAttemptId())) {
        // mark block as processed
        processedBlockIds.addLong(bs.getBlockId());
        break;
      }
      // mark block as processed
      processedBlockIds.addLong(bs.getBlockId());
    }

    byte[] data = null;
    if (bs != null) {
      data = new byte[bs.getLength()];
      long expectedCrc = -1;
      long actualCrc = -1;
      try {
        long start = System.currentTimeMillis();
        System.arraycopy(readBuffer, bs.getOffset(), data, 0, bs.getLength());
        copyTime.addAndGet(System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        expectedCrc = bs.getCrc();
        actualCrc = ChecksumUtils.getCrc32(data);
        crcCheckTime.addAndGet(System.currentTimeMillis() - start);
      } catch (Exception e) {
        LOG.warn("Can't read data for blockId[" + bs.getBlockId() + "]", e);
      }
      if (expectedCrc != actualCrc) {
        throw new RuntimeException("Unexpected crc value for blockId[" + bs.getBlockId()
            + "], expected:" + expectedCrc + ", actual:" + actualCrc);
      }
      return new CompressedShuffleBlock(ByteBuffer.wrap(data), bs.getUncompressLength());
    }
    // current segment hasn't data, try next segment
    return readShuffleBlockData();
  }

  @VisibleForTesting
  protected Roaring64NavigableMap getProcessedBlockIds() {
    return processedBlockIds;
  }

  private int read() {
    long start = System.currentTimeMillis();
    ShuffleDataResult sdr = clientReadHandler.readShuffleData(segmentIndex);
    segmentIndex++;
    readDataTime.addAndGet(System.currentTimeMillis() - start);
    if (sdr == null) {
      return 0;
    }
    readBuffer = sdr.getData();
    if (readBuffer == null || readBuffer.length == 0) {
      return 0;
    }
    bufferSegmentQueue.addAll(sdr.getBufferSegments());
    return sdr.getBufferSegments().size();
  }

  @Override
  public void checkProcessedBlockIds() {
    Roaring64NavigableMap cloneBitmap;
    try {
      cloneBitmap = RssUtils.deserializeBitMap(RssUtils.serializeBitMap(blockIdBitmap));
    } catch (IOException ioe) {
      throw new RuntimeException("Can't validate processed blockIds.", ioe);
    }
    cloneBitmap.and(processedBlockIds);
    if (!blockIdBitmap.equals(cloneBitmap)) {
      throw new RuntimeException("Blocks read inconsistent: expected " + blockIdBitmap.getLongCardinality()
          + " blocks, actual " + cloneBitmap.getLongCardinality() + " blocks");
    }
  }

  @Override
  public void close() {
    if (clientReadHandler != null) {
      clientReadHandler.close();
    }
  }

  @Override
  public void logStatics() {
    LOG.info("Metrics for shuffleId[" + shuffleId + "], partitionId[" + partitionId + "]"
        + ", read data cost " + readDataTime + " ms, copy data cost " + copyTime
        + " ms, crc check cost " + crcCheckTime + " ms");
  }
}
