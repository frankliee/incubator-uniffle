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

package com.tencent.rss.client.impl.grpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Empty;
import com.tencent.rss.client.api.CoordinatorClient;
import com.tencent.rss.client.request.RssAppHeartBeatRequest;
import com.tencent.rss.client.request.RssGetShuffleAssignmentsRequest;
import com.tencent.rss.client.request.RssSendHeartBeatRequest;
import com.tencent.rss.client.response.ResponseStatusCode;
import com.tencent.rss.client.response.RssAppHeartBeatResponse;
import com.tencent.rss.client.response.RssGetShuffleAssignmentsResponse;
import com.tencent.rss.client.response.RssSendHeartBeatResponse;
import com.tencent.rss.common.PartitionRange;
import com.tencent.rss.common.ShuffleServerInfo;
import com.tencent.rss.proto.CoordinatorServerGrpc;
import com.tencent.rss.proto.CoordinatorServerGrpc.CoordinatorServerBlockingStub;
import com.tencent.rss.proto.RssProtos;
import com.tencent.rss.proto.RssProtos.AppHeartBeatRequest;
import com.tencent.rss.proto.RssProtos.AppHeartBeatResponse;
import com.tencent.rss.proto.RssProtos.GetShuffleAssignmentsResponse;
import com.tencent.rss.proto.RssProtos.GetShuffleServerListResponse;
import com.tencent.rss.proto.RssProtos.PartitionRangeAssignment;
import com.tencent.rss.proto.RssProtos.ShuffleServerHeartBeatRequest;
import com.tencent.rss.proto.RssProtos.ShuffleServerHeartBeatResponse;
import com.tencent.rss.proto.RssProtos.ShuffleServerId;
import com.tencent.rss.proto.RssProtos.StatusCode;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoordinatorGrpcClient extends GrpcClient implements CoordinatorClient {

  private static final Logger LOG = LoggerFactory.getLogger(CoordinatorGrpcClient.class);
  private CoordinatorServerBlockingStub blockingStub;

  public CoordinatorGrpcClient(String host, int port) {
    this(host, port, 3);
  }

  public CoordinatorGrpcClient(String host, int port, int maxRetryAttempts) {
    this(host, port, maxRetryAttempts, true);
  }

  public CoordinatorGrpcClient(String host, int port, int maxRetryAttempts, boolean usePlaintext) {
    super(host, port, maxRetryAttempts, usePlaintext);
    blockingStub = CoordinatorServerGrpc.newBlockingStub(channel);
  }

  public CoordinatorGrpcClient(ManagedChannel channel) {
    super(channel);
    blockingStub = CoordinatorServerGrpc.newBlockingStub(channel);
  }

  @Override
  public String getDesc() {
    return "Coordinator grpc client ref to " + host + ":" + port;
  }

  public GetShuffleServerListResponse getShuffleServerList() {
    return blockingStub.getShuffleServerList(Empty.newBuilder().build());
  }

  public ShuffleServerHeartBeatResponse doSendHeartBeat(
      String id, String ip, int port, long usedMemory, long preAllocatedMemory,
      long availableMemory, int eventNumInFlush, long timeout, Set<String> tags) {
    ShuffleServerId serverId =
        ShuffleServerId.newBuilder().setId(id).setIp(ip).setPort(port).build();
    ShuffleServerHeartBeatRequest request =
        ShuffleServerHeartBeatRequest.newBuilder()
            .setServerId(serverId)
            .setUsedMemory(usedMemory)
            .setPreAllocatedMemory(preAllocatedMemory)
            .setAvailableMemory(availableMemory)
            .setEventNumInFlush(eventNumInFlush)
            .addAllTags(tags)
            .build();

    StatusCode status;
    ShuffleServerHeartBeatResponse response = null;

    try {
      response = blockingStub.withDeadlineAfter(timeout, TimeUnit.MILLISECONDS).heartbeat(request);
      status = response.getStatus();
    } catch (StatusRuntimeException e) {
      LOG.error(e.getMessage());
      status = StatusCode.TIMEOUT;
    } catch (Exception e) {
      LOG.error(e.getMessage());
      status = StatusCode.INTERNAL_ERROR;
    }

    if (response == null) {
      response = ShuffleServerHeartBeatResponse.newBuilder().setStatus(status).build();
    }

    if (status != StatusCode.SUCCESS) {
      LOG.error("Fail to send heartbeat to {}:{} {}", this.host, this.port, status);
    }

    return response;
  }

  public RssProtos.GetShuffleAssignmentsResponse doGetShuffleAssignments(
      String appId, int shuffleId, int numMaps, int partitionNumPerRange, int dataReplica, Set<String> requiredTags) {

    RssProtos.GetShuffleServerRequest getServerRequest = RssProtos.GetShuffleServerRequest.newBuilder()
        .setApplicationId(appId)
        .setShuffleId(shuffleId)
        .setPartitionNum(numMaps)
        .setPartitionNumPerRange(partitionNumPerRange)
        .setDataReplica(dataReplica)
        .addAllRequireTags(requiredTags)
        .build();

    return blockingStub.getShuffleAssignments(getServerRequest);
  }

  @Override
  public RssSendHeartBeatResponse sendHeartBeat(RssSendHeartBeatRequest request) {
    ShuffleServerHeartBeatResponse rpcResponse = doSendHeartBeat(
        request.getShuffleServerId(),
        request.getShuffleServerIp(),
        request.getShuffleServerPort(),
        request.getUsedMemory(),
        request.getPreAllocatedMemory(),
        request.getAvailableMemory(),
        request.getEventNumInFlush(),
        request.getTimeout(),
        request.getTags());

    RssSendHeartBeatResponse response;
    StatusCode statusCode = rpcResponse.getStatus();
    switch (statusCode) {
      case SUCCESS:
        response = new RssSendHeartBeatResponse(ResponseStatusCode.SUCCESS);
        break;
      case TIMEOUT:
        response = new RssSendHeartBeatResponse(ResponseStatusCode.TIMEOUT);
        break;
      default:
        response = new RssSendHeartBeatResponse(ResponseStatusCode.INTERNAL_ERROR);
    }
    return response;
  }

  @Override
  public RssAppHeartBeatResponse sendAppHeartBeat(RssAppHeartBeatRequest request) {
    AppHeartBeatRequest rpcRequest = AppHeartBeatRequest.newBuilder().setAppId(request.getAppId()).build();
    AppHeartBeatResponse rpcResponse = blockingStub
        .withDeadlineAfter(request.getTimeoutMs(), TimeUnit.MILLISECONDS).appHeartbeat(rpcRequest);

    RssAppHeartBeatResponse response;
    StatusCode statusCode = rpcResponse.getStatus();
    switch (statusCode) {
      case SUCCESS:
        response = new RssAppHeartBeatResponse(ResponseStatusCode.SUCCESS);
        break;
      default:
        response = new RssAppHeartBeatResponse(ResponseStatusCode.INTERNAL_ERROR);
    }
    return response;
  }

  @Override
  public RssGetShuffleAssignmentsResponse getShuffleAssignments(RssGetShuffleAssignmentsRequest request) {
    RssProtos.GetShuffleAssignmentsResponse rpcResponse = doGetShuffleAssignments(
        request.getAppId(),
        request.getShuffleId(),
        request.getPartitionNum(),
        request.getPartitionNumPerRange(),
        request.getDataReplica(),
        request.getRequiredTags());

    RssGetShuffleAssignmentsResponse response;
    StatusCode statusCode = rpcResponse.getStatus();
    switch (statusCode) {
      case SUCCESS:
        response = new RssGetShuffleAssignmentsResponse(ResponseStatusCode.SUCCESS);
        // get all register info according to coordinator's response
        Map<ShuffleServerInfo, List<PartitionRange>> serverToPartitionRanges = getServerToPartitionRanges(rpcResponse);
        Map<Integer, List<ShuffleServerInfo>> partitionToServers = getPartitionToServers(rpcResponse);
        response.setServerToPartitionRanges(serverToPartitionRanges);
        response.setPartitionToServers(partitionToServers);
        break;
      case TIMEOUT:
        response = new RssGetShuffleAssignmentsResponse(ResponseStatusCode.TIMEOUT);
        break;
      default:
        response = new RssGetShuffleAssignmentsResponse(ResponseStatusCode.INTERNAL_ERROR);
    }

    return response;
  }

  // transform [startPartition, endPartition] -> [server1, server2] to
  // {partition1 -> [server1, server2], partition2 - > [server1, server2]}
  @VisibleForTesting
  public Map<Integer, List<ShuffleServerInfo>> getPartitionToServers(
      GetShuffleAssignmentsResponse response) {
    Map<Integer, List<ShuffleServerInfo>> partitionToServers = Maps.newHashMap();
    List<PartitionRangeAssignment> assigns = response.getAssignmentsList();
    for (PartitionRangeAssignment partitionRangeAssignment : assigns) {
      final int startPartition = partitionRangeAssignment.getStartPartition();
      final int endPartition = partitionRangeAssignment.getEndPartition();
      final List<ShuffleServerInfo> shuffleServerInfos = partitionRangeAssignment
          .getServerList()
          .parallelStream()
          .map(ss -> new ShuffleServerInfo(ss.getId(), ss.getIp(), ss.getPort()))
          .collect(Collectors.toList());
      for (int i = startPartition; i <= endPartition; i++) {
        partitionToServers.put(i, shuffleServerInfos);
      }
    }
    if (partitionToServers.isEmpty()) {
      throw new RuntimeException("Empty assignment to Shuffle Server");
    }
    return partitionToServers;
  }

  // get all ShuffleRegisterInfo with [shuffleServer, startPartitionId, endPartitionId]
  @VisibleForTesting
  public Map<ShuffleServerInfo, List<PartitionRange>> getServerToPartitionRanges(
      GetShuffleAssignmentsResponse response) {
    Map<ShuffleServerInfo, List<PartitionRange>> serverToPartitionRanges = Maps.newHashMap();
    List<PartitionRangeAssignment> assigns = response.getAssignmentsList();
    for (PartitionRangeAssignment assign : assigns) {
      List<ShuffleServerId> shuffleServerIds = assign.getServerList();
      if (shuffleServerIds != null) {
        PartitionRange partitionRange = new PartitionRange(assign.getStartPartition(), assign.getEndPartition());
        for (ShuffleServerId ssi : shuffleServerIds) {
          ShuffleServerInfo shuffleServerInfo =
              new ShuffleServerInfo(ssi.getId(), ssi.getIp(), ssi.getPort());
          if (!serverToPartitionRanges.containsKey(shuffleServerInfo)) {
            serverToPartitionRanges.put(shuffleServerInfo, Lists.newArrayList());
          }
          serverToPartitionRanges.get(shuffleServerInfo).add(partitionRange);
        }
      }
    }
    return serverToPartitionRanges;
  }
}
