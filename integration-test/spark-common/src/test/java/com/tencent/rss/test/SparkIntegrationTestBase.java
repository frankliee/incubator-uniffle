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

import java.util.Map;
import org.apache.spark.SparkConf;
import org.apache.spark.shuffle.RssClientConfig;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SparkIntegrationTestBase extends IntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(SparkIntegrationTestBase.class);

  abstract Map runTest(SparkSession spark, String fileName) throws Exception;

  public String generateTestFile() throws Exception {
    return null;
  }

  public void updateSparkConfCustomer(SparkConf sparkConf) {
  }

  public void run() throws Exception {

    String fileName = generateTestFile();
    SparkConf sparkConf = createSparkConf();

    long start = System.currentTimeMillis();
    updateCommonSparkConf(sparkConf);
    Map resultWithoutRss = runSparkApp(sparkConf, fileName);
    long durationWithoutRss = System.currentTimeMillis() - start;

    updateSparkConfWithRss(sparkConf);
    updateSparkConfCustomer(sparkConf);
    start = System.currentTimeMillis();
    Map resultWithRss = runSparkApp(sparkConf, fileName);
    long durationWithRss = System.currentTimeMillis() - start;

    verifyTestResult(resultWithoutRss, resultWithRss);

    LOG.info("Test: durationWithoutRss[" + durationWithoutRss
        + "], durationWithRss[" + durationWithRss + "]");
  }

  public void updateCommonSparkConf(SparkConf sparkConf) {

  }

  protected Map runSparkApp(SparkConf sparkConf, String testFileName) throws Exception {
    SparkSession spark = SparkSession.builder().config(sparkConf).getOrCreate();
    Map resultWithRss = runTest(spark, testFileName);
    spark.stop();
    return resultWithRss;
  }

  protected SparkConf createSparkConf() {
    return new SparkConf()
        .setAppName(this.getClass().getSimpleName())
        .setMaster("local[4]");
  }

  public void updateSparkConfWithRss(SparkConf sparkConf) {
    sparkConf.set("spark.shuffle.manager", "org.apache.spark.shuffle.RssShuffleManager");
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
    sparkConf.set("spark.rss.partitions.per.range", "2");
    sparkConf.set(RssClientConfig.RSS_WRITER_BUFFER_SIZE, "4m");
    sparkConf.set(RssClientConfig.RSS_WRITER_BUFFER_SPILL_SIZE, "32m");
    sparkConf.set(RssClientConfig.RSS_CLIENT_READ_BUFFER_SIZE, "2m");
    sparkConf.set(RssClientConfig.RSS_WRITER_SERIALIZER_BUFFER_SIZE, "128k");
    sparkConf.set(RssClientConfig.RSS_WRITER_BUFFER_SEGMENT_SIZE, "256k");
    sparkConf.set(RssClientConfig.RSS_COORDINATOR_QUORUM, COORDINATOR_QUORUM);
    sparkConf.set(RssClientConfig.RSS_WRITER_SEND_CHECK_TIMEOUT, "30000");
    sparkConf.set(RssClientConfig.RSS_WRITER_SEND_CHECK_INTERVAL, "1000");
    sparkConf.set(RssClientConfig.RSS_CLIENT_RETRY_INTERVAL_MAX, "1000");
    sparkConf.set(RssClientConfig.RSS_INDEX_READ_LIMIT, "100");
    sparkConf.set(RssClientConfig.RSS_CLIENT_READ_BUFFER_SIZE, "1m");
    sparkConf.set(RssClientConfig.RSS_HEARTBEAT_INTERVAL, "2000");
  }

  private void verifyTestResult(Map expected, Map actual) {
    assertEquals(expected.size(), actual.size());
    for (Object expectedKey : expected.keySet()) {
      assertEquals(expected.get(expectedKey), actual.get(expectedKey));
    }
  }
}
