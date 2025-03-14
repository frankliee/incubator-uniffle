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

package com.tencent.rss.coordinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Sets;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class SimpleClusterManagerTest {

  private Set<String> testTags = Sets.newHashSet("test");

  @Before
  public void setUp() {
    CoordinatorMetrics.register();
  }

  @Test
  public void getServerListTest() {
    CoordinatorConf ssc = new CoordinatorConf();
    ssc.setLong(CoordinatorConf.COORDINATOR_HEARTBEAT_TIMEOUT, 30 * 1000L);
    SimpleClusterManager clusterManager = new SimpleClusterManager(ssc);
    ServerNode sn1 = new ServerNode("sn1", "ip", 0, 100L, 50L, 20,
        10, testTags);
    ServerNode sn2 = new ServerNode("sn2", "ip", 0, 100L, 50L, 21,
        10, testTags);
    ServerNode sn3 = new ServerNode("sn3", "ip", 0, 100L, 50L, 20,
        11, testTags);
    clusterManager.add(sn1);
    clusterManager.add(sn2);
    clusterManager.add(sn3);
    List<ServerNode> serverNodes = clusterManager.getServerList(testTags);
    assertEquals(3, serverNodes.size());
    Set<String> expectedIds = Sets.newHashSet("sn1", "sn2", "sn3");
    for (ServerNode node : serverNodes) {
      expectedIds.remove(node.getId());
    }
    assertEquals(0, expectedIds.size());

    // tag changes
    sn1 = new ServerNode("sn1", "ip", 0, 100L, 50L, 20,
        10, Sets.newHashSet("new_tag"));
    sn2 = new ServerNode("sn2", "ip", 0, 100L, 50L, 21,
        10, Sets.newHashSet("test", "new_tag"));
    ServerNode sn4 = new ServerNode("sn4", "ip", 0, 100L, 51L, 20,
        10, testTags);
    clusterManager.add(sn1);
    clusterManager.add(sn2);
    clusterManager.add(sn4);
    serverNodes = clusterManager.getServerList(testTags);
    assertEquals(3, serverNodes.size());
    assertTrue(serverNodes.contains(sn2));
    assertTrue(serverNodes.contains(sn3));
    assertTrue(serverNodes.contains(sn4));

    Map<String, Set<ServerNode>> tagToNodes = clusterManager.getTagToNodes();
    assertEquals(2, tagToNodes.size());

    Set<ServerNode> newTagNodes = tagToNodes.get("new_tag");
    assertEquals(2, newTagNodes.size());
    assertTrue(newTagNodes.contains(sn1));
    assertTrue(newTagNodes.contains(sn2));

    Set<ServerNode> testTagNodes = tagToNodes.get("test");
    assertEquals(3, testTagNodes.size());
    assertTrue(testTagNodes.contains(sn2));
    assertTrue(testTagNodes.contains(sn3));
    assertTrue(testTagNodes.contains(sn4));
  }

  @Test
  public void heartbeatTimeoutTest() throws Exception {
    CoordinatorConf ssc = new CoordinatorConf();
    ssc.setLong(CoordinatorConf.COORDINATOR_HEARTBEAT_TIMEOUT, 300L);
    SimpleClusterManager clusterManager = new SimpleClusterManager(ssc);
    Thread t = new Thread(() -> {
      for (int i = 0; i < 3; i++) {
        if (i == 2) {
          try {
            Thread.sleep(800);
          } catch (Exception e) {

          }
        }

        String sn = "sn" + i;
        long availableMemory = 30 - i;
        ServerNode node = new ServerNode(sn, "ip", 0, 100L, 50L, availableMemory,
            10, testTags);
        System.out.println("Add node " + node.getId() + " " + node.getTimestamp());
        clusterManager.add(node);
      }
    });
    t.start();

    Thread.sleep(10);
    List<ServerNode> serverNodes = clusterManager.getServerList(testTags);
    assertEquals(2, serverNodes.size());
    Set<String> expectedIds = Sets.newHashSet("sn0", "sn1");
    for (ServerNode node : serverNodes) {
      expectedIds.remove(node.getId());
    }
    assertEquals(0, expectedIds.size());
    Thread.sleep(1000);
    serverNodes = clusterManager.getServerList(testTags);
    assertEquals(1, serverNodes.size());
    assertEquals("sn2", serverNodes.get(0).getId());
    Thread.sleep(500);
    serverNodes = clusterManager.getServerList(testTags);
    assertEquals(0, serverNodes.size());
  }

  @Test
  public void updateExcludeNodesTest() throws Exception {
    String excludeNodesFolder = (new File(ClassLoader.getSystemResource("empty").getFile())).getParent();
    String excludeNodesPath = excludeNodesFolder + "/excludeNodes";
    CoordinatorConf ssc = new CoordinatorConf();
    ssc.setString(CoordinatorConf.COORDINATOR_EXCLUDE_NODES_FILE_PATH, excludeNodesPath);
    ssc.setLong(CoordinatorConf.COORDINATOR_EXCLUDE_NODES_CHECK_INTERVAL, 2000);

    Set<String> nodes = Sets.newHashSet("node1-1999", "node2-1999");
    writeExcludeHosts(excludeNodesPath, nodes);

    SimpleClusterManager scm = new SimpleClusterManager(ssc);
    scm.add(new ServerNode("node1-1999", "ip", 0, 100L, 50L, 20,
        10, testTags));
    scm.add(new ServerNode("node2-1999", "ip", 0, 100L, 50L, 20,
        10, testTags));
    scm.add(new ServerNode("node3-1999", "ip", 0, 100L, 50L, 20,
        10, testTags));
    scm.add(new ServerNode("node4-1999", "ip", 0, 100L, 50L, 20,
        10, testTags));
    assertEquals(0, scm.getExcludeNodes().size());
    Thread.sleep(3000);
    assertEquals(nodes, scm.getExcludeNodes());
    List<ServerNode> availableNodes = scm.getServerList(testTags);
    assertEquals(2, availableNodes.size());
    Set<String> remainNodes = Sets.newHashSet("node3-1999", "node4-1999");
    for (ServerNode node : availableNodes) {
      remainNodes.remove(node.getId());
    }
    assertEquals(0, remainNodes.size());

    nodes = Sets.newHashSet("node3-1999", "node4-1999");
    writeExcludeHosts(excludeNodesPath, nodes);
    Thread.sleep(3000);
    assertEquals(nodes, scm.getExcludeNodes());

    Set<String> excludeNodes = scm.getExcludeNodes();
    Thread.sleep(3000);
    // excludeNodes shouldn't be update if file has no change
    assertTrue(excludeNodes == scm.getExcludeNodes());

    writeExcludeHosts(excludeNodesPath, Sets.newHashSet());
    Thread.sleep(3000);
    // excludeNodes is an empty file, set should be empty
    assertEquals(0, scm.getExcludeNodes().size());

    nodes = Sets.newHashSet("node1-1999");
    writeExcludeHosts(excludeNodesPath, nodes);
    Thread.sleep(3000);

    File blacklistFile = new File(excludeNodesPath);
    blacklistFile.delete();
    Thread.sleep(3000);
    // excludeNodes is deleted, set should be empty
    assertEquals(0, scm.getExcludeNodes().size());

    remainNodes = Sets.newHashSet("node1-1999", "node2-1999", "node3-1999", "node4-1999");
    availableNodes = scm.getServerList(testTags);
    for (ServerNode node : availableNodes) {
      remainNodes.remove(node.getId());
    }
    assertEquals(0, remainNodes.size());
  }

  private void writeExcludeHosts(String path, Set<String> values) throws Exception {
    try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
      // have empty line as value
      pw.write("\n");
      for (String value : values) {
        pw.write(value + "\n");
      }
    }
  }
}
