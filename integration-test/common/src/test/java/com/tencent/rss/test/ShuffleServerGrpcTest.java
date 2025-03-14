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

package com.tencent.rss.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tencent.rss.client.api.ShuffleWriteClient;
import com.tencent.rss.client.factory.ShuffleClientFactory;
import com.tencent.rss.client.impl.grpc.ShuffleServerGrpcClient;
import com.tencent.rss.client.request.RssGetShuffleResultRequest;
import com.tencent.rss.client.request.RssRegisterShuffleRequest;
import com.tencent.rss.client.request.RssReportShuffleResultRequest;
import com.tencent.rss.client.request.RssSendShuffleDataRequest;
import com.tencent.rss.client.response.ResponseStatusCode;
import com.tencent.rss.client.response.RssGetShuffleResultResponse;
import com.tencent.rss.client.response.RssReportShuffleResultResponse;
import com.tencent.rss.client.util.ClientUtils;
import com.tencent.rss.common.PartitionRange;
import com.tencent.rss.common.ShuffleBlockInfo;
import com.tencent.rss.common.ShuffleServerInfo;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.coordinator.CoordinatorConf;
import com.tencent.rss.server.ShuffleServerConf;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

public class ShuffleServerGrpcTest extends IntegrationTestBase {

  private ShuffleServerGrpcClient shuffleServerClient;
  private AtomicInteger atomicInteger = new AtomicInteger(0);

  @BeforeClass
  public static void setupServers() throws Exception {
    CoordinatorConf coordinatorConf = getCoordinatorConf();
    coordinatorConf.setLong("rss.coordinator.app.expired", 2000);
    createCoordinatorServer(coordinatorConf);
    ShuffleServerConf shuffleServerConf = getShuffleServerConf();
    shuffleServerConf.setLong("rss.server.app.expired.withoutHeartbeat", 2000L);
    shuffleServerConf.setLong("rss.server.preAllocation.expired", 5000L);
    createShuffleServer(shuffleServerConf);
    startServers();
  }

  @Before
  public void createClient() {
    shuffleServerClient = new ShuffleServerGrpcClient(LOCALHOST, SHUFFLE_SERVER_PORT);
  }

  @Test
  public void clearResourceTest() throws Exception {
    final ShuffleWriteClient shuffleWriteClient =
        ShuffleClientFactory.getInstance().createShuffleWriteClient(
            "GRPC", 2, 10000L, 4);
    shuffleWriteClient.registerCoordinators("127.0.0.1:19999");
    shuffleWriteClient.registerShuffle(
        new ShuffleServerInfo("127.0.0.1-20001", "127.0.0.1", 20001),
        "clearResourceTest1",
        0,
        Lists.newArrayList(new PartitionRange(0, 1)));

    shuffleWriteClient.sendAppHeartbeat("clearResourceTest1", 1000L);
    shuffleWriteClient.sendAppHeartbeat("clearResourceTest2", 1000L);

    RssRegisterShuffleRequest rrsr = new RssRegisterShuffleRequest("clearResourceTest1", 0,
        Lists.newArrayList(new PartitionRange(0, 1)));
    shuffleServerClient.registerShuffle(rrsr);
    rrsr = new RssRegisterShuffleRequest("clearResourceTest2", 0,
        Lists.newArrayList(new PartitionRange(0, 1)));
    shuffleServerClient.registerShuffle(rrsr);
    assertEquals(Sets.newHashSet("clearResourceTest1", "clearResourceTest2"),
        shuffleServers.get(0).getShuffleTaskManager().getAppIds().keySet());

    // Thread will keep refresh clearResourceTest1 in coordinator
    Thread t = new Thread(() -> {
      int i = 0;
      while (i < 20) {
        shuffleWriteClient.sendAppHeartbeat("clearResourceTest1", 1000L);
        i++;
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    t.start();

    // Heartbeat is sent to coordinator too]
    Thread.sleep(3000);
    shuffleServerClient.registerShuffle(new RssRegisterShuffleRequest("clearResourceTest1", 0,
        Lists.newArrayList(new PartitionRange(0, 1))));
    assertEquals(Sets.newHashSet("clearResourceTest1"),
        coordinators.get(0).getApplicationManager().getAppIds());
    // clearResourceTest2 will be removed because of rss.server.app.expired.withoutHeartbeat
    Thread.sleep(2000);
    assertEquals(Sets.newHashSet("clearResourceTest1"),
        shuffleServers.get(0).getShuffleTaskManager().getAppIds().keySet());

    // clearResourceTest1 will be removed because of rss.server.app.expired.withoutHeartbeat
    t.interrupt();
    Thread.sleep(8000);
    assertEquals(0, shuffleServers.get(0).getShuffleTaskManager().getAppIds().size());

  }

  @Test
  public void shuffleResultTest() throws Exception {
    Map<Integer, List<Long>> partitionToBlockIds = Maps.newHashMap();
    List<Long> blockIds1 = getBlockIdList(1, 3);
    List<Long> blockIds2 = getBlockIdList(2, 2);
    List<Long> blockIds3 = getBlockIdList(3, 1);
    partitionToBlockIds.put(1, blockIds1);
    partitionToBlockIds.put(2, blockIds2);
    partitionToBlockIds.put(3, blockIds3);

    RssReportShuffleResultRequest request =
        new RssReportShuffleResultRequest("shuffleResultTest", 0, 0L, partitionToBlockIds, 1);
    try {
      shuffleServerClient.reportShuffleResult(request);
      fail("Exception should be thrown");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("error happened when report shuffle result"));
    }

    RssGetShuffleResultRequest req = new RssGetShuffleResultRequest("shuffleResultTest", 1, 1);
    try {
      shuffleServerClient.getShuffleResult(req);
      fail("Exception should be thrown");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("can't find shuffle data"));
    }

    RssRegisterShuffleRequest rrsr = new RssRegisterShuffleRequest("shuffleResultTest", 100,
        Lists.newArrayList(new PartitionRange(0, 1)));
    shuffleServerClient.registerShuffle(rrsr);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 1);
    RssGetShuffleResultResponse result = shuffleServerClient.getShuffleResult(req);
    Roaring64NavigableMap blockIdBitmap = result.getBlockIdBitmap();
    assertEquals(Roaring64NavigableMap.bitmapOf(), blockIdBitmap);

    request =
        new RssReportShuffleResultRequest("shuffleResultTest", 0, 0L, partitionToBlockIds, 1);
    RssReportShuffleResultResponse response = shuffleServerClient.reportShuffleResult(request);
    assertEquals(ResponseStatusCode.SUCCESS, response.getStatusCode());
    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 1);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    Roaring64NavigableMap expectedP1 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP1, blockIds1);
    assertEquals(expectedP1, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 2);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    Roaring64NavigableMap expectedP2 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP2, blockIds2);
    assertEquals(expectedP2, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 3);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    Roaring64NavigableMap expectedP3 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP3, blockIds3);
    assertEquals(expectedP3, blockIdBitmap);

    partitionToBlockIds = Maps.newHashMap();
    blockIds1 = getBlockIdList(1, 3);
    blockIds2 = getBlockIdList(2, 2);
    blockIds3 = getBlockIdList(3, 1);
    partitionToBlockIds.put(1, blockIds1);
    partitionToBlockIds.put(2, blockIds2);
    partitionToBlockIds.put(3, blockIds3);

    request =
        new RssReportShuffleResultRequest("shuffleResultTest", 0, 1L, partitionToBlockIds, 1);
    shuffleServerClient.reportShuffleResult(request);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 1);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    addExpectedBlockIds(expectedP1, blockIds1);
    assertEquals(expectedP1, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 2);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    addExpectedBlockIds(expectedP2, blockIds2);
    assertEquals(expectedP2, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 0, 3);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    addExpectedBlockIds(expectedP3, blockIds3);
    assertEquals(expectedP3, blockIdBitmap);

    request =
        new RssReportShuffleResultRequest("shuffleResultTest", 1, 1L, Maps.newHashMap(), 1);
    shuffleServerClient.reportShuffleResult(request);
    req = new RssGetShuffleResultRequest("shuffleResultTest", 1, 1);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    assertEquals(Roaring64NavigableMap.bitmapOf(), blockIdBitmap);

    // test with bitmapNum > 1
    partitionToBlockIds = Maps.newHashMap();
    blockIds1 = getBlockIdList(1, 3);
    blockIds2 = getBlockIdList(2, 2);
    blockIds3 = getBlockIdList(3, 1);
    partitionToBlockIds.put(1, blockIds1);
    partitionToBlockIds.put(2, blockIds2);
    partitionToBlockIds.put(3, blockIds3);
    request =
        new RssReportShuffleResultRequest("shuffleResultTest", 2, 1L, partitionToBlockIds, 3);
    shuffleServerClient.reportShuffleResult(request);
    // validate bitmap in shuffleTaskManager
    Roaring64NavigableMap[] bitmaps = shuffleServers.get(0).getShuffleTaskManager()
        .getPartitionsToBlockIds().get("shuffleResultTest").get(2);
    assertEquals(3, bitmaps.length);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 2, 1);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP1 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP1, blockIds1);
    assertEquals(expectedP1, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 2, 2);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP2 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP2, blockIds2);
    assertEquals(expectedP2, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 2, 3);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP3 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP3, blockIds3);
    assertEquals(expectedP3, blockIdBitmap);

    partitionToBlockIds = Maps.newHashMap();
    blockIds1 = getBlockIdList((int) Constants.MAX_PARTITION_ID, 3);
    blockIds2 = getBlockIdList(2, 2);
    blockIds3 = getBlockIdList(3, 1);
    partitionToBlockIds.put((int) Constants.MAX_PARTITION_ID, blockIds1);
    partitionToBlockIds.put(2, blockIds2);
    partitionToBlockIds.put(3, blockIds3);
    // bimapNum = 2
    request =
        new RssReportShuffleResultRequest("shuffleResultTest", 4, 1L, partitionToBlockIds, 2);
    shuffleServerClient.reportShuffleResult(request);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 4, (int) Constants.MAX_PARTITION_ID);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP1 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP1, blockIds1);
    assertEquals(expectedP1, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 4, 2);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP2 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP2, blockIds2);
    assertEquals(expectedP2, blockIdBitmap);

    req = new RssGetShuffleResultRequest("shuffleResultTest", 4, 3);
    result = shuffleServerClient.getShuffleResult(req);
    blockIdBitmap = result.getBlockIdBitmap();
    expectedP3 = Roaring64NavigableMap.bitmapOf();
    addExpectedBlockIds(expectedP3, blockIds3);
    assertEquals(expectedP3, blockIdBitmap);

    // wait resources are deleted
    Thread.sleep(12000);
    req = new RssGetShuffleResultRequest("shuffleResultTest", 1, 1);
    try {
      shuffleServerClient.getShuffleResult(req);
      fail("Exception should be thrown");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("can't find shuffle data"));
    }
  }

  @Test
  public void registerTest() {
    shuffleServerClient.registerShuffle(new RssRegisterShuffleRequest("registerTest", 0,
        Lists.newArrayList(new PartitionRange(0, 1))));
    RssGetShuffleResultRequest req = new RssGetShuffleResultRequest("registerTest", 0, 0);
    // no exception with getShuffleResult means register successfully
    shuffleServerClient.getShuffleResult(req);
    req = new RssGetShuffleResultRequest("registerTest", 0, 1);
    shuffleServerClient.getShuffleResult(req);
    shuffleServerClient.registerShuffle(new RssRegisterShuffleRequest("registerTest", 1,
        Lists.newArrayList(new PartitionRange(0, 0), new PartitionRange(1, 1), new PartitionRange(2, 2))));
    req = new RssGetShuffleResultRequest("registerTest", 1, 0);
    shuffleServerClient.getShuffleResult(req);
    req = new RssGetShuffleResultRequest("registerTest", 1, 1);
    shuffleServerClient.getShuffleResult(req);
    req = new RssGetShuffleResultRequest("registerTest", 1, 2);
    shuffleServerClient.getShuffleResult(req);
  }

  @Test
  public void sendDataWithoutRegisterTest() throws Exception {
    List<ShuffleBlockInfo> blockInfos = Lists.newArrayList(new ShuffleBlockInfo(0, 0, 0, 100, 0,
        new byte[]{}, Lists.newArrayList(), 0, 100, 0));
    Map<Integer, List<ShuffleBlockInfo>> partitionToBlocks = Maps.newHashMap();
    partitionToBlocks.put(0, blockInfos);
    Map<Integer, Map<Integer, List<ShuffleBlockInfo>>> shuffleToBlocks = Maps.newHashMap();
    shuffleToBlocks.put(0, partitionToBlocks);

    RssSendShuffleDataRequest rssdr = new RssSendShuffleDataRequest(
        "sendDataWithoutRegisterTest", 3, 1000, shuffleToBlocks);
    shuffleServerClient.sendShuffleData(rssdr);
    assertEquals(132, shuffleServers.get(0).getPreAllocatedMemory());
    Thread.sleep(10000);
    assertEquals(0, shuffleServers.get(0).getPreAllocatedMemory());
  }

  @Test
  public void multipleShuffleResultTest() throws Exception {
    Set<Long> expectedBlockIds = Sets.newConcurrentHashSet();
    RssRegisterShuffleRequest rrsr = new RssRegisterShuffleRequest("multipleShuffleResultTest", 100,
        Lists.newArrayList(new PartitionRange(0, 1)));
    shuffleServerClient.registerShuffle(rrsr);

    Runnable r1 = () -> {
      for (int i = 0; i < 100; i++) {
        Map<Integer, List<Long>> ptbs = Maps.newHashMap();
        List<Long> blockIds = Lists.newArrayList();
        Long blockId = ClientUtils.getBlockId(1, 0, i);
        expectedBlockIds.add(blockId);
        blockIds.add(blockId);
        ptbs.put(1, blockIds);
        RssReportShuffleResultRequest req1 =
            new RssReportShuffleResultRequest("multipleShuffleResultTest", 1, 0, ptbs, 1);
        shuffleServerClient.reportShuffleResult(req1);
      }
    };
    Runnable r2 = () -> {
      for (int i = 100; i < 200; i++) {
        Map<Integer, List<Long>> ptbs = Maps.newHashMap();
        List<Long> blockIds = Lists.newArrayList();
        Long blockId = ClientUtils.getBlockId(1, 1, i);
        expectedBlockIds.add(blockId);
        blockIds.add(blockId);
        ptbs.put(1, blockIds);
        RssReportShuffleResultRequest req1 =
            new RssReportShuffleResultRequest("multipleShuffleResultTest", 1, 1, ptbs, 1);
        shuffleServerClient.reportShuffleResult(req1);
      }
    };
    Runnable r3 = () -> {
      for (int i = 200; i < 300; i++) {
        Map<Integer, List<Long>> ptbs = Maps.newHashMap();
        List<Long> blockIds = Lists.newArrayList();
        Long blockId = ClientUtils.getBlockId(1, 2, i);
        expectedBlockIds.add(blockId);
        blockIds.add(blockId);
        ptbs.put(1, blockIds);
        RssReportShuffleResultRequest req1 =
            new RssReportShuffleResultRequest("multipleShuffleResultTest", 1, 2, ptbs, 1);
        shuffleServerClient.reportShuffleResult(req1);
      }
    };
    Thread t1 = new Thread(r1);
    Thread t2 = new Thread(r2);
    Thread t3 = new Thread(r3);
    t1.start();
    t2.start();
    t3.start();
    t1.join();
    t2.join();
    t3.join();

    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    for (Long blockId : expectedBlockIds) {
      blockIdBitmap.addLong(blockId);
    }

    RssGetShuffleResultRequest req = new RssGetShuffleResultRequest(
        "multipleShuffleResultTest", 1, 1);
    RssGetShuffleResultResponse result = shuffleServerClient.getShuffleResult(req);
    Roaring64NavigableMap actualBlockIdBitmap = result.getBlockIdBitmap();
    assertEquals(blockIdBitmap, actualBlockIdBitmap);
  }

  private List<Long> getBlockIdList(int partitionId, int blockNum) {
    List<Long> blockIds = Lists.newArrayList();
    for (int i = 0; i < blockNum; i++) {
      blockIds.add(ClientUtils.getBlockId(partitionId, 0, atomicInteger.getAndIncrement()));
    }
    return blockIds;
  }

  private void addExpectedBlockIds(Roaring64NavigableMap bitmap, List<Long> blockIds) {
    for (int i = 0; i < blockIds.size(); i++) {
      bitmap.addLong(blockIds.get(i));
    }
  }
}
