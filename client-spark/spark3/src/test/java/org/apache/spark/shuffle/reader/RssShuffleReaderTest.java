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

package org.apache.spark.shuffle.reader;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.collect.Maps;
import com.tencent.rss.storage.handler.impl.HdfsShuffleWriteHandler;
import com.tencent.rss.storage.util.StorageType;
import java.util.Map;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.executor.ShuffleReadMetrics;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.shuffle.RssShuffleHandle;
import org.junit.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import scala.Option;

public class RssShuffleReaderTest extends AbstractRssReaderTest {

  private static final Serializer KRYO_SERIALIZER = new KryoSerializer(new SparkConf(false));

  @Test
  public void readTest() throws Exception {

    String basePath = HDFS_URI + "readTest1";
    HdfsShuffleWriteHandler writeHandler =
        new HdfsShuffleWriteHandler("appId", 0, 0, 0, basePath, "test", conf);
    HdfsShuffleWriteHandler writeHandler1 =
        new HdfsShuffleWriteHandler("appId", 0, 1, 1, basePath, "test", conf);

    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap1 = Roaring64NavigableMap.bitmapOf();
    writeTestData(writeHandler, 2, 5, expectedData,
        blockIdBitmap, "key", KRYO_SERIALIZER, 0);



    TaskContext contextMock = mock(TaskContext.class);
    RssShuffleHandle handleMock = mock(RssShuffleHandle.class);
    ShuffleDependency dependencyMock = mock(ShuffleDependency.class);
    when(handleMock.getAppId()).thenReturn("appId");
    when(handleMock.getDependency()).thenReturn(dependencyMock);
    when(handleMock.getShuffleId()).thenReturn(1);
    when(dependencyMock.serializer()).thenReturn(KRYO_SERIALIZER);
    when(contextMock.attemptNumber()).thenReturn(1);
    when(contextMock.taskAttemptId()).thenReturn(1L);
    when(contextMock.taskMetrics()).thenReturn(new TaskMetrics());
    doNothing().when(contextMock).killTaskIfInterrupted();
    when(dependencyMock.aggregator()).thenReturn(Option.empty());
    when(dependencyMock.keyOrdering()).thenReturn(Option.empty());
    when(dependencyMock.mapSideCombine()).thenReturn(false);

    Map<Integer, Roaring64NavigableMap> partitionToExpectBlocks = Maps.newHashMap();
    partitionToExpectBlocks.put(0, blockIdBitmap);
    RssShuffleReader rssShuffleReaderSpy = spy(new RssShuffleReader<String, String>(
        0,
        1,
        0,
        Integer.MAX_VALUE,
        contextMock,
        handleMock,
        basePath,
        1000,
        conf,
        StorageType.HDFS.name(),
        1000,
        1,
        partitionToExpectBlocks,
        taskIdBitmap,
        new ShuffleReadMetrics()));
    validateResult(rssShuffleReaderSpy.read(), expectedData, 10);

    writeTestData(writeHandler1, 2, 4, expectedData,
        blockIdBitmap1, "another_key", KRYO_SERIALIZER, 1);
    partitionToExpectBlocks.put(1, blockIdBitmap1);
    RssShuffleReader rssShuffleReaderSpy1 = spy(new RssShuffleReader<String, String>(
        0,
        2,
        0,
        Integer.MAX_VALUE,
        contextMock,
        handleMock,
        basePath,
        1000,
        conf,
        StorageType.HDFS.name(),
        1000,
        2,
        partitionToExpectBlocks,
        taskIdBitmap,
        new ShuffleReadMetrics()));
    validateResult(rssShuffleReaderSpy1.read(), expectedData, 18);

    RssShuffleReader rssShuffleReaderSpy2 = spy(new RssShuffleReader<String, String>(
        0,
        2,
        0,
        Integer.MAX_VALUE,
        contextMock,
        handleMock,
        basePath,
        1000,
        conf,
        StorageType.HDFS.name(),
        1000,
        2,
        partitionToExpectBlocks,
        Roaring64NavigableMap.bitmapOf(),
        new ShuffleReadMetrics()));
    validateResult(rssShuffleReaderSpy2.read(), Maps.newHashMap(), 0);
  }

}
