/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.balancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.IOException;

import java.net.URI;
import java.util.Collection;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HAUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.MiniDFSNNTopology.NNConf;
import org.apache.hadoop.hdfs.NameNodeProxies;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.ha.HATestUtil;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorageReport;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.ipc.ActiveDenyOfServiceException;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Test balancer with HA NameNodes
 */
public class TestBalancerWithHANameNodes {
  private MiniDFSCluster cluster;
  ClientProtocol client;

  static {
    TestBalancer.initTestSetup();
  }

  /**
   * Test a cluster with even distribution, then a new empty node is added to
   * the cluster. Test start a cluster with specified number of nodes, and fills
   * it to be 30% full (with a single file replicated identically to all
   * datanodes); It then adds one new empty node and starts balancing.
   */
  @Test(timeout = 60000)
  public void testBalancerWithHANameNodes() throws Exception {
    Configuration conf = new HdfsConfiguration();
    //这个功能还没打进去，不移动小块的性能优化
    //conf.setLong(DFSConfigKeys.DFS_BALANCER_GETBLOCKS_MIN_BLOCK_SIZE_KEY, 1L);
    conf.setLong(DFSConfigKeys.DFS_HA_TAILEDITS_PERIOD_KEY, 1);
    conf.setBoolean(DFSConfigKeys.DFS_HA_BALANCER_REQUEST_STANDBY_KEY, true);
    HAUtil.setAllowStandbyReads(conf, true);
    TestBalancer.initConf(conf);
    long newNodeCapacity = TestBalancer.CAPACITY; // new node's capacity
    String newNodeRack = TestBalancer.RACK2; // new node's rack
    // array of racks for original nodes in cluster
    String[] racks = new String[] { TestBalancer.RACK0, TestBalancer.RACK1 };
    // array of capacities of original nodes in cluster
    long[] capacities = new long[] { TestBalancer.CAPACITY,
        TestBalancer.CAPACITY };
    assertEquals(capacities.length, racks.length);
    int numOfDatanodes = capacities.length;
    NNConf nn1Conf = new MiniDFSNNTopology.NNConf("nn1");
    nn1Conf.setIpcPort(NameNode.DEFAULT_PORT);
    Configuration copiedConf = new Configuration(conf);
    cluster = new MiniDFSCluster.Builder(copiedConf)
        .nnTopology(MiniDFSNNTopology.simpleHATopology())
        .numDataNodes(capacities.length)
        .racks(racks)
        .simulatedCapacities(capacities)
        .build();
    HATestUtil.setFailoverConfigurations(cluster, conf);
    try {
      cluster.waitActive();
      cluster.transitionToActive(0);
      Thread.sleep(500);
      client = NameNodeProxies.createProxy(conf, FileSystem.getDefaultUri(conf),
          ClientProtocol.class).getProxy();
      long totalCapacity = TestBalancer.sum(capacities);
      // fill up the cluster to be 30% full
      long totalUsedSpace = totalCapacity * 3 / 10;
      TestBalancer.createFile(cluster, TestBalancer.filePath, totalUsedSpace
          / numOfDatanodes, (short) numOfDatanodes, 0);
      HATestUtil.waitForStandbyToCatchUp(cluster.getNameNode(0), cluster.getNameNode(1));

      // start up an empty node with the same capacity and on the same rack
      cluster.startDataNodes(conf, 1, true, null, new String[] { newNodeRack },
          new long[] { newNodeCapacity });
      totalCapacity += newNodeCapacity;
      TestBalancer.waitForHeartBeat(totalUsedSpace, totalCapacity, client,
          cluster);
      Collection<URI> namenodes = DFSUtil.getInternalNsRpcUris(conf);
      assertEquals(1, namenodes.size());
      assertTrue(namenodes.contains(HATestUtil.getLogicalUri(cluster)));
      final int r = Balancer.run(namenodes, Balancer.Parameters.DEFAULT, conf);
      assertEquals(ExitStatus.SUCCESS.getExitCode(), r);
      TestBalancer.waitForBalancer(totalUsedSpace, totalCapacity, client,
          cluster, Balancer.Parameters.DEFAULT);
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout = 60000)
  public void testBalancerRequestStandby() throws Exception {
    Configuration conf = new HdfsConfiguration();
    //conf.setLong(DFSConfigKeys.DFS_BALANCER_GETBLOCKS_MIN_BLOCK_SIZE_KEY, 1L);
    conf.setLong(DFSConfigKeys.DFS_HA_TAILEDITS_PERIOD_KEY, 1);
    conf.setBoolean(DFSConfigKeys.DFS_HA_BALANCER_REQUEST_STANDBY_KEY, true);
    TestBalancer.initConf(conf);
    long newNodeCapacity = TestBalancer.CAPACITY; // new node's capacity
    String newNodeRack = TestBalancer.RACK2; // new node's rack
    // array of racks for original nodes in cluster
    String[] racks = new String[] { TestBalancer.RACK0, TestBalancer.RACK1 };
    // array of capacities of original nodes in cluster
    long[] capacities = new long[] { TestBalancer.CAPACITY,
            TestBalancer.CAPACITY };
    int numOfDatanodes = capacities.length;
    Configuration copiedConf = new Configuration(conf);
    cluster = new MiniDFSCluster.Builder(copiedConf)
            .nnTopology(MiniDFSNNTopology.simpleHATopology())
            .numDataNodes(capacities.length)
            .racks(racks)
            .simulatedCapacities(capacities)
            .build();
    HATestUtil.setFailoverConfigurations(cluster, conf);
    try {
      cluster.waitActive();
      cluster.transitionToActive(0);
      Thread.sleep(500);
      client = NameNodeProxies.createProxy(conf, FileSystem.getDefaultUri(conf),
              ClientProtocol.class).getProxy();
      long totalCapacity = TestBalancer.sum(capacities);
      // fill up the cluster to be 30% full
      long totalUsedSpace = totalCapacity * 3 / 10;
      TestBalancer.createFile(cluster, TestBalancer.filePath, totalUsedSpace
              / numOfDatanodes, (short) numOfDatanodes, 0);
      HATestUtil.waitForStandbyToCatchUp(cluster.getNameNode(0),
              cluster.getNameNode(1));
      NamenodeProtocols nn = cluster.getNameNodeRpc(0);
      DatanodeStorageReport[] dns = nn.getDatanodeStorageReport(
              HdfsConstants.DatanodeReportType.LIVE);
      assertEquals(numOfDatanodes, dns.length);
      try {
        nn.getBlocks(dns[0].getDatanodeInfo(), 100);
        fail("Operation category getBlocks is not supported in state Active.");
      } catch (IOException ioe) {
        assertTrue(ioe instanceof ActiveDenyOfServiceException);
      }
    } finally {
      cluster.shutdown();
    }

  }

}
