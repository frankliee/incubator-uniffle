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

import com.google.common.io.Files;
import com.tencent.rss.coordinator.CoordinatorConf;
import com.tencent.rss.server.ShuffleServerConf;
import com.tencent.rss.storage.util.StorageType;
import java.io.File;
import org.apache.spark.SparkConf;
import org.apache.spark.shuffle.RssClientConfig;
import org.junit.BeforeClass;

public class SparkSQLMultiStorageRssTest extends SparkSQLTest {
  private static String basePath;

  @BeforeClass
  public static void setupServers() throws Exception {
    CoordinatorConf coordinatorConf = getCoordinatorConf();
    coordinatorConf.setLong("rss.coordinator.app.expired", 5000);
    createCoordinatorServer(coordinatorConf);

    ShuffleServerConf shuffleServerConf = getShuffleServerConf();
    shuffleServerConf.setLong("rss.server.heartbeat.interval", 5000);
    shuffleServerConf.setLong("rss.server.app.expired.withoutHeartbeat", 4000);
    shuffleServerConf.setBoolean(ShuffleServerConf.RSS_USE_MULTI_STORAGE, true);

    // local storage config
    File tmpDir = Files.createTempDir();
    tmpDir.deleteOnExit();
    File dataDir1 = new File(tmpDir, "data1");
    File dataDir2 = new File(tmpDir, "data2");
    basePath = dataDir1.getAbsolutePath() + "," + dataDir2.getAbsolutePath();
    shuffleServerConf.setString("rss.storage.type", StorageType.LOCALFILE_AND_HDFS.name());
    shuffleServerConf.setString("rss.storage.basePath", basePath);

    // uploader and remote storage config
    shuffleServerConf.setBoolean("rss.server.uploader.enable", true);
    shuffleServerConf.setLong("rss.server.uploader.combine.threshold.MB", 32);
    shuffleServerConf.setLong("rss.server.uploader.references.speed.mbps", 128);
    shuffleServerConf.setString("rss.server.uploader.remote.storage.type", StorageType.HDFS.name());
    shuffleServerConf.setString("rss.server.uploader.base.path", HDFS_URI + "rss/test");
    shuffleServerConf.setLong("rss.server.uploader.interval.ms", 10);
    shuffleServerConf.setInteger("rss.server.uploader.thread.number", 4);

    createShuffleServer(shuffleServerConf);
    startServers();
  }

  @Override
  public void updateRssStorage(SparkConf sparkConf) {
    sparkConf.set(RssClientConfig.RSS_STORAGE_TYPE, "LOCALFILE_AND_HDFS");
  }

  @Override
  public void checkShuffleData() throws Exception {
    Thread.sleep(12000);
    String[] paths = basePath.split(",");
    for (String path : paths) {
      File f = new File(path);
      assertEquals(0, f.list().length);
    }
  }
}
