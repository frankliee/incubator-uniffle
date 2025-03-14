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

import com.google.common.annotations.VisibleForTesting;
import com.tencent.rss.common.PartitionRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicAssignmentStrategy implements AssignmentStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(BasicAssignmentStrategy.class);

  private ClusterManager clusterManager;

  public BasicAssignmentStrategy(ClusterManager clusterManager) {
    this.clusterManager = clusterManager;
  }

  @Override
  public PartitionRangeAssignment assign(int totalPartitionNum, int partitionNumPerRange,
      int replica, Set<String> requiredTags) {
    List<PartitionRange> ranges = generateRanges(totalPartitionNum, partitionNumPerRange);
    int shuffleNodesMax = clusterManager.getShuffleNodesMax();
    List<ServerNode> servers = getRequiredServers(requiredTags, shuffleNodesMax);

    if (servers.isEmpty() || servers.size() < replica) {
      return new PartitionRangeAssignment(null);
    }

    SortedMap<PartitionRange, List<ServerNode>> assignments = new TreeMap<>();
    int idx = 0;
    int size = servers.size();

    for (PartitionRange range : ranges) {
      List<ServerNode> nodes = new LinkedList<>();
      for (int i = 0; i < replica; ++i) {
        ServerNode node = servers.get(idx);
        nodes.add(node);
        idx = nextIdx(idx, size);
      }

      assignments.put(range, nodes);
    }

    return new PartitionRangeAssignment(assignments);
  }

  private List<ServerNode> getRequiredServers(Set<String> requiredTags, int expectedNum) {
    List<ServerNode> servers = clusterManager.getServerList(requiredTags);
    // shuffle server update the status according to heartbeat, if every server is in initial status,
    // random the order of list to avoid always pick same nodes
    Collections.shuffle(servers);
    Collections.sort(servers);
    if (expectedNum > servers.size()) {
      LOG.warn("Can't get expected servers [" + expectedNum + "] and found only [" + servers.size() + "]");
      return servers;
    }
    return servers.subList(0, expectedNum);
  }

  @VisibleForTesting
  int nextIdx(int idx, int size) {
    ++idx;
    if (idx >= size) {
      idx = 0;
    }
    return idx;
  }

  @VisibleForTesting
  List<PartitionRange> generateRanges(int totalPartitionNum, int partitionNumPerRange) {
    List<PartitionRange> ranges = new ArrayList<>();
    if (totalPartitionNum <= 0 || partitionNumPerRange <= 0) {
      return ranges;
    }

    for (int start = 0; start < totalPartitionNum; start += partitionNumPerRange) {
      int end = start + partitionNumPerRange - 1;
      PartitionRange range = new PartitionRange(start, end);
      ranges.add(range);
    }

    return ranges;
  }
}
