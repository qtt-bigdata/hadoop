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
package org.apache.hadoop.hdfs.server.blockmanagement;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.ha.HAServiceProtocol;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs.BlockReportReplica;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager.AccessMode;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction.ReplicaUnderConstruction;
import org.apache.hadoop.hdfs.server.blockmanagement.CorruptReplicasMap.Reason;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeStorageInfo.AddBlockResult;
import org.apache.hadoop.hdfs.server.blockmanagement.PendingDataNodeMessages.ReportedBlockInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSClusterStats;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNode.OperationCategory;
import org.apache.hadoop.hdfs.server.namenode.Namesystem;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeMetrics;
import org.apache.hadoop.hdfs.server.protocol.*;
import org.apache.hadoop.hdfs.server.protocol.BlocksWithLocations.BlockWithLocations;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage.State;
import org.apache.hadoop.hdfs.util.FoldedTreeSet;
import org.apache.hadoop.hdfs.util.LightWeightLinkedSet;
import org.apache.hadoop.metrics2.util.MBeans;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.util.LightWeightGSet;
import org.apache.hadoop.util.Time;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.hadoop.util.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.hadoop.util.ExitUtil.terminate;

/**
 * Keeps information related to the blocks stored in the Hadoop cluster.
 * For block state management, it tries to maintain the  safety
 * property of "# of live replicas == # of expected redundancy" under
 * any events such as decommission, namenode failover, datanode failure.
 *
 * The motivation of maintenance mode is to allow admins quickly repair nodes
 * without paying the cost of decommission. Thus with maintenance mode,
 * # of live replicas doesn't have to be equal to # of expected redundancy.
 * If any of the replica is in maintenance mode, the safety property
 * is extended as follows. These property still apply for the case of zero
 * maintenance replicas, thus we can use these safe property for all scenarios.
 * a. # of live replicas >= # of min replication for maintenance.
 * b. # of live replicas <= # of expected redundancy.
 * c. # of live replicas and maintenance replicas >= # of expected redundancy.
 *
 * For regular replication, # of min live replicas for maintenance is determined
 * by DFS_NAMENODE_MAINTENANCE_REPLICATION_MIN_KEY. This number has to <=
 * DFS_NAMENODE_REPLICATION_MIN_KEY.
 * For erasure encoding, # of min live replicas for maintenance is
 * BlockInfoStriped#getRealDataBlockNum.
 *
 * Another safety property is to satisfy the block placement policy. While the
 * policy is configurable, the replicas the policy is applied to are the live
 * replicas + maintenance replicas.
 */
@InterfaceAudience.Private
public class BlockManager implements BlockStatsMXBean {

  public static final Logger LOG = LoggerFactory.getLogger(BlockManager.class);
  public static final Logger blockLog = NameNode.blockStateChangeLog;

  private static final String QUEUE_REASON_CORRUPT_STATE =
    "it has the wrong state or generation stamp";

  private static final String QUEUE_REASON_FUTURE_GENSTAMP =
    "generation stamp is in the future";

  private static final long BLOCK_RECOVERY_TIMEOUT_MULTIPLIER = 30;

  private final Namesystem namesystem;

  private final DatanodeManager datanodeManager;
  private final HeartbeatManager heartbeatManager;
  private final BlockTokenSecretManager blockTokenSecretManager;

  private final PendingDataNodeMessages pendingDNMessages =
    new PendingDataNodeMessages();

  private volatile long pendingReplicationBlocksCount = 0L;
  private volatile long corruptReplicaBlocksCount = 0L;
  private volatile long underReplicatedBlocksCount = 0L;
  private volatile long scheduledReplicationBlocksCount = 0L;
  private final AtomicLong excessBlocksCount = new AtomicLong(0L);
  private final long startupDelayBlockDeletionInMs;
  private final BlockReportLeaseManager blockReportLeaseManager;
  private ObjectName mxBeanName;

  /** Used by metrics */
  public long getPendingReplicationBlocksCount() {
    return pendingReplicationBlocksCount;
  }
  /** Used by metrics */
  public long getUnderReplicatedBlocksCount() {
    return underReplicatedBlocksCount;
  }
  /** Used by metrics */
  public long getCorruptReplicaBlocksCount() {
    return corruptReplicaBlocksCount;
  }
  /** Used by metrics */
  public long getScheduledReplicationBlocksCount() {
    return scheduledReplicationBlocksCount;
  }
  /** Used by metrics */
  public long getPendingDeletionBlocksCount() {
    return invalidateBlocks.numBlocks();
  }
  /** Used by metrics */
  public long getStartupDelayBlockDeletionInMs() {
    return startupDelayBlockDeletionInMs;
  }
  /** Used by metrics */
  public long getExcessBlocksCount() {
    return excessBlocksCount.get();
  }
  /** Used by metrics */
  public long getPostponedMisreplicatedBlocksCount() {
    return postponedMisreplicatedBlocks.size();
  }
  /** Used by metrics */
  public int getPendingDataNodeMessageCount() {
    return pendingDNMessages.count();
  }

  /**replicationRecheckInterval is how often namenode checks for new replication work*/
  private final long replicationRecheckInterval;

  /** How often to check and the limit for the storageinfo efficiency. */
  private final long storageInfoDefragmentInterval;
  private final long storageInfoDefragmentTimeout;
  private final double storageInfoDefragmentRatio;

  /**
   * Mapping: Block -> { BlockCollection, datanodes, self ref }
   * Updated only in response to client-sent information.
   */
  final BlocksMap blocksMap;

  /** Replication thread. */
  final Daemon replicationThread = new Daemon(new ReplicationMonitor());

  /** StorageInfoDefragmenter thread. */
  private final Daemon storageInfoDefragmenterThread =
      new Daemon(new StorageInfoDefragmenter());

  /** Block report thread for handling async reports. */
  private final BlockReportProcessingThread blockReportThread =
      new BlockReportProcessingThread();

  /** Store blocks -> datanodedescriptor(s) map of corrupt replicas */
  final CorruptReplicasMap corruptReplicas = new CorruptReplicasMap();

  /** Blocks to be invalidated. */
  private final InvalidateBlocks invalidateBlocks;
  
  /**
   * After a failover, over-replicated blocks may not be handled
   * until all of the replicas have done a block report to the
   * new active. This is to make sure that this NameNode has been
   * notified of all block deletions that might have been pending
   * when the failover happened.
   */
  private final LinkedHashSet<Block> postponedMisreplicatedBlocks =
      new LinkedHashSet<Block>();
  private final int blocksPerPostpondedRescan;
  private final ArrayList<Block> rescannedMisreplicatedBlocks;

  /**
   * Maps a StorageID to the set of blocks that are "extra" for this
   * DataNode. We'll eventually remove these extras.
   */
  public final Map<String, LightWeightLinkedSet<Block>> excessReplicateMap =
    new HashMap<>();

  /**
   * Store set of Blocks that need to be replicated 1 or more times.
   * We also store pending replication-orders.
   */
  public final UnderReplicatedBlocks neededReplications = new UnderReplicatedBlocks();

  @VisibleForTesting
  final PendingReplicationBlocks pendingReplications;

  /** Stores information about block recovery attempts. */
  private final PendingRecoveryBlocks pendingRecoveryBlocks;

  /** The maximum number of replicas allowed for a block */
  public final short maxReplication;
  /**
   * The maximum number of outgoing replication streams a given node should have
   * at one time considering all but the highest priority replications needed.
    */
  int maxReplicationStreams;
  /**
   * The maximum number of outgoing replication streams a given node should have
   * at one time.
   */
  int replicationStreamsHardLimit;
  /** Minimum copies needed or else write is disallowed */
  public final short minReplication;
  /** Default number of replicas */
  public final int defaultReplication;
  /** value returned by MAX_CORRUPT_FILES_RETURNED */
  final int maxCorruptFilesReturned;

  final float blocksInvalidateWorkPct;
  final int blocksReplWorkMultiplier;
  
  // whether or not to issue block encryption keys.
  final boolean encryptDataTransfer;
  
  // Max number of blocks to log info about during a block report.
  private final long maxNumBlocksToLog;

  /**
   * When running inside a Standby node, the node may receive block reports
   * from datanodes before receiving the corresponding namespace edits from
   * the active NameNode. Thus, it will postpone them for later processing,
   * instead of marking the blocks as corrupt.
   */
  private boolean shouldPostponeBlocksFromFuture = false;

  /**
   * Process replication queues asynchronously to allow namenode safemode exit
   * and failover to be faster. HDFS-5496
   */
  private Daemon replicationQueuesInitializer = null;
  /**
   * Number of blocks to process asychronously for replication queues
   * initialization once aquired the namesystem lock. Remaining blocks will be
   * processed again after aquiring lock again.
   */
  private int numBlocksPerIteration;


  /**
   * Minimum size that a block can be sent to Balancer through getBlocks.
   * And after HDFS-8824, the small blocks are unused anyway, so there's no
   * point to send them to balancer.
   */
  private long getBlocksMinBlockSize = -1;

  /**
   * Progress of the Replication queues initialisation.
   */
  private double replicationQueuesInitProgress = 0.0;

  /** for block replicas placement */
  private BlockPlacementPolicy blockplacement;
  private final BlockStoragePolicySuite storagePolicySuite;

  /** Check whether name system is running before terminating */
  private boolean checkNSRunning = true;

  /** Minimum live replicas needed for the datanode to be transitioned
   * from ENTERING_MAINTENANCE to IN_MAINTENANCE.
   */
  private final short minReplicationToBeInMaintenance;

  public BlockManager(final Namesystem namesystem, final FSClusterStats stats,
      final Configuration conf) throws IOException {
    this.namesystem = namesystem;
    datanodeManager = new DatanodeManager(this, namesystem, conf);
    heartbeatManager = datanodeManager.getHeartbeatManager();

    blocksPerPostpondedRescan = (int)Math.min(Integer.MAX_VALUE,
        datanodeManager.getBlocksPerPostponedMisreplicatedBlocksRescan());
    rescannedMisreplicatedBlocks =
        new ArrayList<Block>(blocksPerPostpondedRescan);
    startupDelayBlockDeletionInMs = conf.getLong(
        DFSConfigKeys.DFS_NAMENODE_STARTUP_DELAY_BLOCK_DELETION_SEC_KEY,
        DFSConfigKeys.DFS_NAMENODE_STARTUP_DELAY_BLOCK_DELETION_SEC_DEFAULT) * 1000L;
    invalidateBlocks = new InvalidateBlocks(
        datanodeManager.blockInvalidateLimit, startupDelayBlockDeletionInMs);

    // Compute the map capacity by allocating 2% of total memory
    blocksMap = new BlocksMap(
        LightWeightGSet.computeCapacity(2.0, "BlocksMap"));
    blockplacement = BlockPlacementPolicy.getInstance(
        conf, stats, datanodeManager.getNetworkTopology(),
        datanodeManager.getHost2DatanodeMap());
    storagePolicySuite = BlockStoragePolicySuite.createDefaultSuite();
    pendingReplications = new PendingReplicationBlocks(conf.getInt(
      DFSConfigKeys.DFS_NAMENODE_REPLICATION_PENDING_TIMEOUT_SEC_KEY,
      DFSConfigKeys.DFS_NAMENODE_REPLICATION_PENDING_TIMEOUT_SEC_DEFAULT) * 1000L);

    blockTokenSecretManager = createBlockTokenSecretManager(conf);

    this.maxCorruptFilesReturned = conf.getInt(
      DFSConfigKeys.DFS_DEFAULT_MAX_CORRUPT_FILES_RETURNED_KEY,
      DFSConfigKeys.DFS_DEFAULT_MAX_CORRUPT_FILES_RETURNED);
    this.defaultReplication = conf.getInt(DFSConfigKeys.DFS_REPLICATION_KEY,
        DFSConfigKeys.DFS_REPLICATION_DEFAULT);

    final int maxR = conf.getInt(DFSConfigKeys.DFS_REPLICATION_MAX_KEY,
        DFSConfigKeys.DFS_REPLICATION_MAX_DEFAULT);
    final int minR = conf.getInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_MIN_KEY,
        DFSConfigKeys.DFS_NAMENODE_REPLICATION_MIN_DEFAULT);
    if (minR <= 0)
      throw new IOException("Unexpected configuration parameters: "
          + DFSConfigKeys.DFS_NAMENODE_REPLICATION_MIN_KEY
          + " = " + minR + " <= 0");
    if (maxR > Short.MAX_VALUE)
      throw new IOException("Unexpected configuration parameters: "
          + DFSConfigKeys.DFS_REPLICATION_MAX_KEY
          + " = " + maxR + " > " + Short.MAX_VALUE);
    if (minR > maxR)
      throw new IOException("Unexpected configuration parameters: "
          + DFSConfigKeys.DFS_NAMENODE_REPLICATION_MIN_KEY
          + " = " + minR + " > "
          + DFSConfigKeys.DFS_REPLICATION_MAX_KEY
          + " = " + maxR);
    this.minReplication = (short)minR;
    this.maxReplication = (short)maxR;

    this.maxReplicationStreams =
        conf.getInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_MAX_STREAMS_KEY,
            DFSConfigKeys.DFS_NAMENODE_REPLICATION_MAX_STREAMS_DEFAULT);
    this.replicationStreamsHardLimit =
        conf.getInt(
            DFSConfigKeys.DFS_NAMENODE_REPLICATION_STREAMS_HARD_LIMIT_KEY,
            DFSConfigKeys.DFS_NAMENODE_REPLICATION_STREAMS_HARD_LIMIT_DEFAULT);

    this.blocksInvalidateWorkPct = DFSUtil.getInvalidateWorkPctPerIteration(conf);
    this.blocksReplWorkMultiplier = DFSUtil.getReplWorkMultiplier(conf);

    this.replicationRecheckInterval = 
      conf.getInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_INTERVAL_KEY,
              DFSConfigKeys.DFS_NAMENODE_REPLICATION_INTERVAL_DEFAULT) * 1000L;

    this.storageInfoDefragmentInterval =
      conf.getLong(
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_INTERVAL_MS_KEY,
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_INTERVAL_MS_DEFAULT);
    this.storageInfoDefragmentTimeout =
      conf.getLong(
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_TIMEOUT_MS_KEY,
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_TIMEOUT_MS_DEFAULT);
    this.storageInfoDefragmentRatio =
      conf.getDouble(
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_RATIO_KEY,
          DFSConfigKeys.DFS_NAMENODE_STORAGEINFO_DEFRAGMENT_RATIO_DEFAULT);

    this.encryptDataTransfer =
        conf.getBoolean(DFSConfigKeys.DFS_ENCRYPT_DATA_TRANSFER_KEY,
            DFSConfigKeys.DFS_ENCRYPT_DATA_TRANSFER_DEFAULT);

    this.maxNumBlocksToLog =
        conf.getLong(DFSConfigKeys.DFS_MAX_NUM_BLOCKS_TO_LOG_KEY,
            DFSConfigKeys.DFS_MAX_NUM_BLOCKS_TO_LOG_DEFAULT);
    this.numBlocksPerIteration = conf.getInt(
        DFSConfigKeys.DFS_BLOCK_MISREPLICATION_PROCESSING_LIMIT,
        DFSConfigKeys.DFS_BLOCK_MISREPLICATION_PROCESSING_LIMIT_DEFAULT);

    this.getBlocksMinBlockSize = conf.getLongBytes(
            DFSConfigKeys.DFS_BALANCER_GETBLOCKS_MIN_BLOCK_SIZE_KEY,
            DFSConfigKeys.DFS_BALANCER_GETBLOCKS_MIN_BLOCK_SIZE_DEFAULT);

    final int minMaintenanceR = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_MAINTENANCE_REPLICATION_MIN_KEY,
        DFSConfigKeys.DFS_NAMENODE_MAINTENANCE_REPLICATION_MIN_DEFAULT);

    if (minMaintenanceR < 0) {
      throw new IOException("Unexpected configuration parameters: "
          + DFSConfigKeys.DFS_NAMENODE_MAINTENANCE_REPLICATION_MIN_KEY
          + " = " + minMaintenanceR + " < 0");
    }
    if (minMaintenanceR > defaultReplication) {
      throw new IOException("Unexpected configuration parameters: "
          + DFSConfigKeys.DFS_NAMENODE_MAINTENANCE_REPLICATION_MIN_KEY
          + " = " + minMaintenanceR + " > "
          + DFSConfigKeys.DFS_REPLICATION_KEY
          + " = " + defaultReplication);
    }
    this.minReplicationToBeInMaintenance = (short)minMaintenanceR;

    long heartbeatIntervalSecs = conf.getTimeDuration(
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY,
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_DEFAULT, TimeUnit.SECONDS);
    long blockRecoveryTimeout = getBlockRecoveryTimeout(heartbeatIntervalSecs);
    pendingRecoveryBlocks = new PendingRecoveryBlocks(blockRecoveryTimeout);

    this.blockReportLeaseManager = new BlockReportLeaseManager(conf);

    LOG.info("defaultReplication         = " + defaultReplication);
    LOG.info("maxReplication             = " + maxReplication);
    LOG.info("minReplication             = " + minReplication);
    LOG.info("maxReplicationStreams      = " + maxReplicationStreams);
    LOG.info("replicationRecheckInterval = " + replicationRecheckInterval);
    LOG.info("encryptDataTransfer        = " + encryptDataTransfer);
    LOG.info("maxNumBlocksToLog          = " + maxNumBlocksToLog);
  }

  private static BlockTokenSecretManager createBlockTokenSecretManager(
      final Configuration conf) {
    final boolean isEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, 
        DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_DEFAULT);
    LOG.info(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY + "=" + isEnabled);

    if (!isEnabled) {
      if (UserGroupInformation.isSecurityEnabled()) {
        LOG.error("Security is enabled but block access tokens " +
            "(via " + DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY + ") " +
            "aren't enabled. This may cause issues " +
            "when clients attempt to talk to a DataNode.");
      }
      return null;
    }

    final long updateMin = conf.getLong(
        DFSConfigKeys.DFS_BLOCK_ACCESS_KEY_UPDATE_INTERVAL_KEY, 
        DFSConfigKeys.DFS_BLOCK_ACCESS_KEY_UPDATE_INTERVAL_DEFAULT);
    final long lifetimeMin = conf.getLong(
        DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_LIFETIME_KEY, 
        DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_LIFETIME_DEFAULT);
    final String encryptionAlgorithm = conf.get(
        DFSConfigKeys.DFS_DATA_ENCRYPTION_ALGORITHM_KEY);
    LOG.info(DFSConfigKeys.DFS_BLOCK_ACCESS_KEY_UPDATE_INTERVAL_KEY
        + "=" + updateMin + " min(s), "
        + DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_LIFETIME_KEY
        + "=" + lifetimeMin + " min(s), "
        + DFSConfigKeys.DFS_DATA_ENCRYPTION_ALGORITHM_KEY
        + "=" + encryptionAlgorithm);
    
    String nsId = DFSUtil.getNamenodeNameServiceId(conf);
    boolean isHaEnabled = HAUtil.isHAEnabled(conf, nsId);

    if (isHaEnabled) {
      String thisNnId = HAUtil.getNameNodeId(conf, nsId);
      String otherNnId = HAUtil.getNameNodeIdOfOtherNode(conf, nsId);
      return new BlockTokenSecretManager(updateMin*60*1000L,
          lifetimeMin*60*1000L, thisNnId.compareTo(otherNnId) < 0 ? 0 : 1, null,
          encryptionAlgorithm);
    } else {
      return new BlockTokenSecretManager(updateMin*60*1000L,
          lifetimeMin*60*1000L, 0, null, encryptionAlgorithm);
    }
  }

  public BlockStoragePolicy getStoragePolicy(final String policyName) {
    return storagePolicySuite.getPolicy(policyName);
  }

  public BlockStoragePolicy getStoragePolicy(final byte policyId) {
    return storagePolicySuite.getPolicy(policyId);
  }

  public BlockStoragePolicy[] getStoragePolicies() {
    return storagePolicySuite.getAllPolicies();
  }

  public void setBlockPoolId(String blockPoolId) {
    if (isBlockTokenEnabled()) {
      blockTokenSecretManager.setBlockPoolId(blockPoolId);
    }
  }

  /** get the BlockTokenSecretManager */
  @VisibleForTesting
  public BlockTokenSecretManager getBlockTokenSecretManager() {
    return blockTokenSecretManager;
  }

  /** Allow silent termination of replication monitor for testing */
  @VisibleForTesting
  void enableRMTerminationForTesting() {
    checkNSRunning = false;
  }

  private boolean isBlockTokenEnabled() {
    return blockTokenSecretManager != null;
  }

  /** Should the access keys be updated? */
  boolean shouldUpdateBlockKey(final long updateTime) throws IOException {
    return isBlockTokenEnabled()? blockTokenSecretManager.updateKeys(updateTime)
        : false;
  }

  public void activate(Configuration conf) {
    pendingReplications.start();
    datanodeManager.activate(conf);
    this.replicationThread.start();
    storageInfoDefragmenterThread.setName("StorageInfoMonitor");
    storageInfoDefragmenterThread.start();
    this.blockReportThread.start();
    mxBeanName = MBeans.register("NameNode", "BlockStats", this);
  }

  public void close() {
    try {
      replicationThread.interrupt();
      storageInfoDefragmenterThread.interrupt();
      blockReportThread.interrupt();
      replicationThread.join(3000);
      storageInfoDefragmenterThread.join(3000);
      blockReportThread.join(3000);
    } catch (InterruptedException ie) {
    }
    datanodeManager.close();
    pendingReplications.stop();
    blocksMap.close();
  }

  /** @return the datanodeManager */
  public DatanodeManager getDatanodeManager() {
    return datanodeManager;
  }

  @VisibleForTesting
  public BlockPlacementPolicy getBlockPlacementPolicy() {
    return blockplacement;
  }

  /** Set BlockPlacementPolicy */
  public void setBlockPlacementPolicy(BlockPlacementPolicy newpolicy) {
    if (newpolicy == null) {
      throw new HadoopIllegalArgumentException("newpolicy == null");
    }
    this.blockplacement = newpolicy;
  }

  /** Dump meta data to out. */
  public void metaSave(PrintWriter out) {
    assert namesystem.hasWriteLock();
    final List<DatanodeDescriptor> live = new ArrayList<DatanodeDescriptor>();
    final List<DatanodeDescriptor> dead = new ArrayList<DatanodeDescriptor>();
    datanodeManager.fetchDatanodes(live, dead, false);
    out.println("Live Datanodes: " + live.size());
    out.println("Dead Datanodes: " + dead.size());
    //
    // Dump contents of neededReplication
    //
    synchronized (neededReplications) {
      out.println("Metasave: Blocks waiting for replication: " + 
                  neededReplications.size());
      for (Block block : neededReplications) {
        dumpBlockMeta(block, out);
      }
    }
    
    // Dump any postponed over-replicated blocks
    out.println("Mis-replicated blocks that have been postponed:");
    for (Block block : postponedMisreplicatedBlocks) {
      dumpBlockMeta(block, out);
    }

    // Dump blocks from pendingReplication
    pendingReplications.metaSave(out);

    // Dump blocks that are waiting to be deleted
    invalidateBlocks.dump(out);

    // Dump all datanodes
    getDatanodeManager().datanodeDump(out);
  }

  /**
   * Dump the metadata for the given block in a human-readable
   * form.
   */
  private void dumpBlockMeta(Block block, PrintWriter out) {
    List<DatanodeDescriptor> containingNodes =
                                      new ArrayList<DatanodeDescriptor>();
    List<DatanodeStorageInfo> containingLiveReplicasNodes =
      new ArrayList<DatanodeStorageInfo>();
    
    NumberReplicas numReplicas = new NumberReplicas();
    // source node returned is not used
    chooseSourceDatanode(block, containingNodes,
        containingLiveReplicasNodes, numReplicas,
        UnderReplicatedBlocks.LEVEL);
    
    // containingLiveReplicasNodes can include READ_ONLY_SHARED replicas which are 
    // not included in the numReplicas.liveReplicas() count
    assert containingLiveReplicasNodes.size() >= numReplicas.liveReplicas();
    int usableReplicas = numReplicas.liveReplicas() +
                         numReplicas.decommissionedAndDecommissioning();
    
    if (block instanceof BlockInfo) {
      BlockCollection bc = ((BlockInfo) block).getBlockCollection();
      String fileName = (bc == null) ? "[orphaned]" : bc.getName();
      out.print(fileName + ": ");
    }
    // l: == live:, d: == decommissioned c: == corrupt e: == excess
    out.print(block + ((usableReplicas > 0)? "" : " MISSING") +
              " (replicas:" +
              " l: " + numReplicas.liveReplicas() +
              " d: " + numReplicas.decommissionedAndDecommissioning() +
              " c: " + numReplicas.corruptReplicas() +
              " e: " + numReplicas.excessReplicas() + ") ");

    Collection<DatanodeDescriptor> corruptNodes = 
                                  corruptReplicas.getNodes(block);
    
    for (DatanodeStorageInfo storage : blocksMap.getStorages(block)) {
      final DatanodeDescriptor node = storage.getDatanodeDescriptor();
      String state = "";
      if (corruptNodes != null && corruptNodes.contains(node)) {
        state = "(corrupt)";
      } else if (node.isDecommissioned() || 
          node.isDecommissionInProgress()) {
        state = "(decommissioned)";
      }
      
      if (storage.areBlockContentsStale()) {
        state += " (block deletions maybe out of date)";
      }
      out.print(" " + node + state + " : ");
    }
    out.println("");
  }

  /** @return maxReplicationStreams */
  public int getMaxReplicationStreams() {
    return maxReplicationStreams;
  }

  /**
   * @return true if the block has minimum replicas
   */
  public boolean checkMinReplication(BlockInfo block) {
    return (countNodes(block).liveReplicas() >= minReplication);
  }

  public short getMinReplication() {
    return minReplication;
  }

  public short getMinReplicationToBeInMaintenance() {
    return minReplicationToBeInMaintenance;
  }

  private short getMinMaintenanceStorageNum(BlockInfo block) {
    return (short) Math.min(minReplicationToBeInMaintenance,
        getReplication(block));
  }

  /**
   * Commit a block of a file
   * 
   * @param block block to be committed
   * @param commitBlock - contains client reported block length and generation
   * @return true if the block is changed to committed state.
   * @throws IOException if the block does not have at least a minimal number
   * of replicas reported from data-nodes.
   */
  private boolean commitBlock(final BlockInfoUnderConstruction block,
      final Block commitBlock) throws IOException {
    if (block.getBlockUCState() == BlockUCState.COMMITTED)
      return false;
    assert block.getNumBytes() <= commitBlock.getNumBytes() :
      "commitBlock length is less than the stored one "
      + commitBlock.getNumBytes() + " vs. " + block.getNumBytes();
    if(block.getGenerationStamp() != commitBlock.getGenerationStamp()) {
      throw new IOException("Commit block with mismatching GS. NN has " +
        block + ", client submits " + commitBlock);
    }
    List<ReplicaUnderConstruction> staleReplicas =
        block.commitBlock(commitBlock);
    removeStaleReplicas(staleReplicas, block);
    return true;
  }
  
  /**
   * Commit the last block of the file and mark it as complete if it has
   * meets the minimum replication requirement
   * 
   * @param bc block collection
   * @param commitBlock - contains client reported block length and generation
   * @return true if the last block is changed to committed state.
   * @throws IOException if the block does not have at least a minimal number
   * of replicas reported from data-nodes.
   */
  public boolean commitOrCompleteLastBlock(BlockCollection bc,
      Block commitBlock) throws IOException {
    if(commitBlock == null)
      return false; // not committing, this is a block allocation retry
    BlockInfo lastBlock = bc.getLastBlock();
    if(lastBlock == null)
      return false; // no blocks in file yet
    if(lastBlock.isComplete())
      return false; // already completed (e.g. by syncBlock)
    if(lastBlock.isUnderRecovery()) {
      throw new IOException("Commit or complete block " + commitBlock +
          ", whereas it is under recovery.");
    }
    
    final boolean b = commitBlock((BlockInfoUnderConstruction)lastBlock, commitBlock);

    // Count replicas on decommissioning nodes, as these will not be
    // decommissioned unless recovery/completing last block has finished
    NumberReplicas numReplicas = countNodes(lastBlock);
    int numUsableReplicas = numReplicas.liveReplicas() +
        numReplicas.decommissioning() +
        numReplicas.liveEnteringMaintenanceReplicas();

    if (numUsableReplicas >= minReplication) {
      if (b) {
        addExpectedReplicasToPending(lastBlock);
      }
      completeBlock(bc, bc.numBlocks() - 1, false);
    }
    return b;
  }

  /**
   * If IBR is not sent from expected locations yet, add the datanodes to
   * pendingReplications in order to keep ReplicationMonitor from scheduling
   * the block.
   */
  private void addExpectedReplicasToPending(BlockInfo lastBlock) {
    DatanodeStorageInfo[] expectedStorages =
            ((BlockInfoUnderConstruction)lastBlock).getExpectedStorageLocations();
    if (expectedStorages.length - lastBlock.numNodes() > 0) {
      ArrayList<DatanodeDescriptor> pendingNodes =
          new ArrayList<DatanodeDescriptor>();
      for (DatanodeStorageInfo storage : expectedStorages) {
        DatanodeDescriptor dnd = storage.getDatanodeDescriptor();
        if (lastBlock.findStorageInfo(dnd) == null) {
          pendingNodes.add(dnd);
        }
      }
      pendingReplications.increment(lastBlock,
          pendingNodes.toArray(new DatanodeDescriptor[pendingNodes.size()]));
    }
  }

  /**
   * Convert a specified block of the file to a complete block.
   * @param bc file
   * @param blkIndex  block index in the file
   * @throws IOException if the block does not have at least a minimal number
   * of replicas reported from data-nodes.
   */
  private BlockInfo completeBlock(final BlockCollection bc,
      final int blkIndex, boolean force) throws IOException {
    if(blkIndex < 0)
      return null;
    BlockInfo curBlock = bc.getBlocks()[blkIndex];
    if(curBlock.isComplete())
      return curBlock;
    BlockInfoUnderConstruction ucBlock = (BlockInfoUnderConstruction)curBlock;
    int numNodes = ucBlock.numNodes();
    if (!force && numNodes < minReplication)
      throw new IOException("Cannot complete block: " +
          "block does not satisfy minimal replication requirement.");
    if(!force && ucBlock.getBlockUCState() != BlockUCState.COMMITTED)
      throw new IOException(
          "Cannot complete block: block has not been COMMITTED by the client");
    BlockInfo completeBlock = ucBlock.convertToCompleteBlock();
    // replace penultimate block in file
    bc.setBlock(blkIndex, completeBlock);

    // Since safe-mode only counts complete blocks, and we now have
    // one more complete block, we need to adjust the total up, and
    // also count it as safe, if we have at least the minimum replica
    // count. (We may not have the minimum replica count yet if this is
    // a "forced" completion when a file is getting closed by an
    // OP_CLOSE edit on the standby).
    namesystem.adjustSafeModeBlockTotals(0, 1);
    namesystem.incrementSafeBlockCount(
        Math.min(numNodes, minReplication));

    // replace block in the blocksMap
    return blocksMap.replaceBlock(completeBlock);
  }

  private BlockInfo completeBlock(final BlockCollection bc,
      final BlockInfo block, boolean force) throws IOException {
    BlockInfo[] fileBlocks = bc.getBlocks();
    for(int idx = 0; idx < fileBlocks.length; idx++)
      if(fileBlocks[idx] == block) {
        return completeBlock(bc, idx, force);
      }
    return block;
  }

  /**
   * Force the given block in the given file to be marked as complete,
   * regardless of whether enough replicas are present. This is necessary
   * when tailing edit logs as a Standby.
   */
  public BlockInfo forceCompleteBlock(final BlockCollection bc,
      final BlockInfoUnderConstruction block) throws IOException {
    List<ReplicaUnderConstruction> staleReplicas = block.commitBlock(block);
    removeStaleReplicas(staleReplicas, block);
    return completeBlock(bc, block, true);
  }


  /**
   * Convert the last block of the file to an under construction block.<p>
   * The block is converted only if the file has blocks and the last one
   * is a partial block (its size is less than the preferred block size).
   * The converted block is returned to the client.
   * The client uses the returned block locations to form the data pipeline
   * for this block.<br>
   * The methods returns null if there is no partial block at the end.
   * The client is supposed to allocate a new block with the next call.
   *
   * @param bc file
   * @return the last block locations if the block is partial or null otherwise
   */
  public LocatedBlock convertLastBlockToUnderConstruction(
      BlockCollection bc) throws IOException {
    BlockInfo oldBlock = bc.getLastBlock();
    if(oldBlock == null ||
        bc.getPreferredBlockSize() == oldBlock.getNumBytes())
      return null;
    assert oldBlock == getStoredBlock(oldBlock) :
      "last block of the file is not in blocksMap";

    DatanodeStorageInfo[] targets = getStorages(oldBlock);

    BlockInfoUnderConstruction ucBlock = bc.setLastBlock(oldBlock, targets);
    blocksMap.replaceBlock(ucBlock);

    // Remove block from replication queue.
    NumberReplicas replicas = countNodes(ucBlock);
    neededReplications.remove(ucBlock, replicas.liveReplicas(),
        replicas.readOnlyReplicas(),
        replicas.outOfServiceReplicas(), getReplication(ucBlock));
    pendingReplications.remove(ucBlock);

    // remove this block from the list of pending blocks to be deleted. 
    for (DatanodeStorageInfo storage : targets) {
      invalidateBlocks.remove(storage.getDatanodeDescriptor(), oldBlock);
    }
    
    // Adjust safe-mode totals, since under-construction blocks don't
    // count in safe-mode.
    namesystem.adjustSafeModeBlockTotals(
        // decrement safe if we had enough
        targets.length >= minReplication ? -1 : 0,
        // always decrement total blocks
        -1);

    final long fileLength = bc.computeContentSummary().getLength();
    final long pos = fileLength - ucBlock.getNumBytes();
    return createLocatedBlock(ucBlock, pos, AccessMode.WRITE);
  }

  /**
   * Get all valid locations of the block
   */
  private List<DatanodeStorageInfo> getValidLocations(Block block) {
    final List<DatanodeStorageInfo> locations
        = new ArrayList<DatanodeStorageInfo>(blocksMap.numNodes(block));
    for(DatanodeStorageInfo storage : blocksMap.getStorages(block)) {
      // filter invalidate replicas
      if(!invalidateBlocks.contains(storage.getDatanodeDescriptor(), block)) {
        locations.add(storage);
      }
    }
    return locations;
  }
  
  private List<LocatedBlock> createLocatedBlockList(final BlockInfo[] blocks,
      final long offset, final long length, final int nrBlocksToReturn,
      final AccessMode mode) throws IOException {
    int curBlk = 0;
    long curPos = 0, blkSize = 0;
    int nrBlocks = (blocks[0].getNumBytes() == 0) ? 0 : blocks.length;
    for (curBlk = 0; curBlk < nrBlocks; curBlk++) {
      blkSize = blocks[curBlk].getNumBytes();
      assert blkSize > 0 : "Block of size 0";
      if (curPos + blkSize > offset) {
        break;
      }
      curPos += blkSize;
    }

    if (nrBlocks > 0 && curBlk == nrBlocks)   // offset >= end of file
      return Collections.<LocatedBlock>emptyList();

    long endOff = offset + length;
    List<LocatedBlock> results = new ArrayList<LocatedBlock>(blocks.length);
    do {
      results.add(createLocatedBlock(blocks[curBlk], curPos, mode));
      curPos += blocks[curBlk].getNumBytes();
      curBlk++;
    } while (curPos < endOff 
          && curBlk < blocks.length
          && results.size() < nrBlocksToReturn);
    return results;
  }

  private LocatedBlock createLocatedBlock(final BlockInfo[] blocks,
      final long endPos, final AccessMode mode) throws IOException {
    int curBlk = 0;
    long curPos = 0;
    int nrBlocks = (blocks[0].getNumBytes() == 0) ? 0 : blocks.length;
    for (curBlk = 0; curBlk < nrBlocks; curBlk++) {
      long blkSize = blocks[curBlk].getNumBytes();
      if (curPos + blkSize >= endPos) {
        break;
      }
      curPos += blkSize;
    }
    
    return createLocatedBlock(blocks[curBlk], curPos, mode);
  }
  
  private LocatedBlock createLocatedBlock(final BlockInfo blk, final long pos,
    final BlockTokenSecretManager.AccessMode mode) throws IOException {
    final LocatedBlock lb = createLocatedBlock(blk, pos);
    if (mode != null) {
      setBlockToken(lb, mode);
    }
    return lb;
  }

  /** @return a LocatedBlock for the given block */
  private LocatedBlock createLocatedBlock(final BlockInfo blk, final long pos
      ) throws IOException {
    if (blk instanceof BlockInfoUnderConstruction) {
      if (blk.isComplete()) {
        throw new IOException(
            "blk instanceof BlockInfoUnderConstruction && blk.isComplete()"
            + ", blk=" + blk);
      }
      final BlockInfoUnderConstruction uc = (BlockInfoUnderConstruction)blk;
      final DatanodeStorageInfo[] storages = uc.getExpectedStorageLocations();
      final ExtendedBlock eb = new ExtendedBlock(namesystem.getBlockPoolId(), blk);
      return new LocatedBlock(eb, storages, pos, false);
    }

    // get block locations
    NumberReplicas numberReplicas = countNodes(blk);
    final int numCorruptNodes = numberReplicas.corruptReplicas();
    final int numCorruptReplicas = corruptReplicas.numCorruptReplicas(blk);
    if (numCorruptNodes != numCorruptReplicas) {
      LOG.warn("Inconsistent number of corrupt replicas for "
          + blk + " blockMap has " + numCorruptNodes
          + " but corrupt replicas map has " + numCorruptReplicas);
    }

    final int numNodes = blocksMap.numNodes(blk);
    final boolean isCorrupt = numCorruptReplicas == numNodes;
    int numMachines = isCorrupt ? numNodes: numNodes - numCorruptReplicas;
    numMachines -= numberReplicas.maintenanceNotForReadReplicas();
    DatanodeStorageInfo[] machines = new DatanodeStorageInfo[numMachines];
    int j = 0;
    if (numMachines > 0) {
      final boolean noCorrupt = (numCorruptReplicas == 0);
      for(DatanodeStorageInfo storage : blocksMap.getStorages(blk)) {
        if (storage.getState() != State.FAILED) {
          final DatanodeDescriptor d = storage.getDatanodeDescriptor();
          // Don't pick IN_MAINTENANCE or dead ENTERING_MAINTENANCE states.
          if (d.isInMaintenance()
              || (d.isEnteringMaintenance() && !d.isAlive())) {
            continue;
          }
          if (noCorrupt) {
            machines[j++] = storage;
          } else {
            final boolean replicaCorrupt = isReplicaCorrupt(blk, d);
            if (isCorrupt || !replicaCorrupt) {
              machines[j++] = storage;
            }
          }
        }
      }
    }

    if(j < machines.length) {
      machines = Arrays.copyOf(machines, j);
    }

    assert j == machines.length :
      "isCorrupt: " + isCorrupt +
      " numMachines: " + numMachines +
      " numNodes: " + numNodes +
      " numCorrupt: " + numCorruptNodes +
      " numCorruptRepls: " + numCorruptReplicas;
    final ExtendedBlock eb = new ExtendedBlock(namesystem.getBlockPoolId(), blk);
    return new LocatedBlock(eb, machines, pos, isCorrupt);
  }

  /** Create a LocatedBlocks. */
  public LocatedBlocks createLocatedBlocks(final BlockInfo[] blocks,
      final long fileSizeExcludeBlocksUnderConstruction,
      final boolean isFileUnderConstruction, final long offset,
      final long length, final boolean needBlockToken,
      final boolean inSnapshot, FileEncryptionInfo feInfo)
      throws IOException {
    assert namesystem.hasReadLock();
    if (blocks == null) {
      return null;
    } else if (blocks.length == 0) {
      return new LocatedBlocks(0, isFileUnderConstruction,
          Collections.<LocatedBlock>emptyList(), null, false, feInfo);
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("blocks = " + java.util.Arrays.asList(blocks));
      }
      final AccessMode mode = needBlockToken? AccessMode.READ: null;
      final List<LocatedBlock> locatedblocks = createLocatedBlockList(
          blocks, offset, length, Integer.MAX_VALUE, mode);

      final LocatedBlock lastlb;
      final boolean isComplete;
      if (!inSnapshot) {
        final BlockInfo last = blocks[blocks.length - 1];
        final long lastPos = last.isComplete()?
            fileSizeExcludeBlocksUnderConstruction - last.getNumBytes()
            : fileSizeExcludeBlocksUnderConstruction;
        lastlb = createLocatedBlock(last, lastPos, mode);
        isComplete = last.isComplete();
      } else {
        lastlb = createLocatedBlock(blocks,
            fileSizeExcludeBlocksUnderConstruction, mode);
        isComplete = true;
      }
      return new LocatedBlocks(
          fileSizeExcludeBlocksUnderConstruction, isFileUnderConstruction,
          locatedblocks, lastlb, isComplete, feInfo);
    }
  }

  /** @return current access keys. */
  public ExportedBlockKeys getBlockKeys() {
    return isBlockTokenEnabled()? blockTokenSecretManager.exportKeys()
        : ExportedBlockKeys.DUMMY_KEYS;
  }

  /** Generate a block token for the located block. */
  public void setBlockToken(final LocatedBlock b,
      final BlockTokenSecretManager.AccessMode mode) throws IOException {
    if (isBlockTokenEnabled()) {
      // Use cached UGI if serving RPC calls.
      b.setBlockToken(blockTokenSecretManager.generateToken(
          NameNode.getRemoteUser().getShortUserName(),
          b.getBlock(), EnumSet.of(mode)));
    }    
  }

  void addKeyUpdateCommand(final List<DatanodeCommand> cmds,
      final DatanodeDescriptor nodeinfo) {
    // check access key update
    if (isBlockTokenEnabled() && nodeinfo.needKeyUpdate()) {
      cmds.add(new KeyUpdateCommand(blockTokenSecretManager.exportKeys()));
      nodeinfo.setNeedKeyUpdate(false);
    }
  }
  
  public DataEncryptionKey generateDataEncryptionKey() {
    if (isBlockTokenEnabled() && encryptDataTransfer) {
      return blockTokenSecretManager.generateDataEncryptionKey();
    } else {
      return null;
    }
  }

  /**
   * Clamp the specified replication between the minimum and the maximum
   * replication levels.
   */
  public short adjustReplication(short replication) {
    return replication < minReplication? minReplication
        : replication > maxReplication? maxReplication: replication;
  }

  /**
   * Check whether the replication parameter is within the range
   * determined by system configuration and throw an exception if it's not.
   *
   * @param src the path to the target file
   * @param replication the requested replication factor
   * @param clientName the name of the client node making the request
   * @throws java.io.IOException thrown if the requested replication factor
   * is out of bounds
   */
   public void verifyReplication(String src,
                          short replication,
                          String clientName) throws IOException {

    if (replication < minReplication || replication > maxReplication) {
      StringBuilder msg = new StringBuilder("Requested replication factor of ");

      msg.append(replication);

      if (replication > maxReplication) {
        msg.append(" exceeds maximum of ");
        msg.append(maxReplication);
      } else {
        msg.append(" is less than the required minimum of ");
        msg.append(minReplication);
      }

      msg.append(" for ").append(src);

      if (clientName != null) {
        msg.append(" from ").append(clientName);
      }

      throw new IOException(msg.toString());
    }
  }

  /**
   * Check if a block is replicated to at least the minimum replication.
   */
  public boolean isSufficientlyReplicated(BlockInfo b) {
    // Compare against the lesser of the minReplication and number of live DNs.
    final int replication =
        Math.min(minReplication, getDatanodeManager().getNumLiveDataNodes());
    return countNodes(b).liveReplicas() >= replication;
  }

  /**
   * return a list of blocks & their locations on <code>datanode</code> whose
   * total size is <code>size</code>
   * 
   * @param datanode on which blocks are located
   * @param size total size of blocks
   */
  public BlocksWithLocations getBlocks(DatanodeID datanode, long size) throws IOException {
    namesystem.checkOperation(OperationCategory.UNCHECKED);
    namesystem.readLock();
    try {
      namesystem.checkOperation(OperationCategory.UNCHECKED);
      return getBlocksWithLocations(datanode, size);  
    } finally {
      namesystem.readUnlock();
    }
  }

  /** Get all blocks with location information from a datanode. */
  private BlocksWithLocations getBlocksWithLocations(final DatanodeID datanode,
      final long size) throws UnregisteredNodeException {
    final DatanodeDescriptor node = getDatanodeManager().getDatanode(datanode);
    if (node == null) {
      blockLog.warn("BLOCK* getBlocks: Asking for blocks from an" +
          " unrecorded node {}", datanode);
      throw new HadoopIllegalArgumentException(
          "Datanode " + datanode + " not found.");
    }

    int numBlocks = node.numBlocks();
    if(numBlocks == 0) {
      return new BlocksWithLocations(new BlockWithLocations[0]);
    }
    Iterator<BlockInfo> iter = node.getBlockIterator();
    int startBlock = DFSUtil.getRandom().nextInt(numBlocks); // starting from a random block
    // skip blocks
    for(int i=0; i<startBlock; i++) {
      iter.next();
    }
    List<BlockWithLocations> results = new ArrayList<BlockWithLocations>();
    long totalSize = 0;
    BlockInfo curBlock;
    while(totalSize<size && iter.hasNext()) {
      curBlock = iter.next();
      if(!curBlock.isComplete())  continue;
      if (curBlock.getNumBytes() < getBlocksMinBlockSize) {
        continue;
      }
      totalSize += addBlock(curBlock, results);
    }
    if(totalSize<size) {
      iter = node.getBlockIterator(); // start from the beginning
      for(int i=0; i<startBlock&&totalSize<size; i++) {
        curBlock = iter.next();
        if(!curBlock.isComplete())  continue;
        if (curBlock.getNumBytes() < getBlocksMinBlockSize) {
          continue;
        }
        totalSize += addBlock(curBlock, results);
      }
    }

    return new BlocksWithLocations(
        results.toArray(new BlockWithLocations[results.size()]));
  }

   
  /** Remove the blocks associated to the given datanode. */
  void removeBlocksAssociatedTo(final DatanodeDescriptor node) {
    for (DatanodeStorageInfo storage : node.getStorageInfos()) {
      final Iterator<BlockInfo> it = storage.getBlockIterator();
      while (it.hasNext()) {
        BlockInfo block = it.next();
        // DatanodeStorageInfo must be removed using the iterator to avoid
        // ConcurrentModificationException in the underlying storage
        it.remove();
        removeStoredBlock(block, node);
      }
    }
    // Remove all pending DN messages referencing this DN.
    pendingDNMessages.removeAllMessagesForDatanode(node);

    node.resetBlocks();
    invalidateBlocks.remove(node);
  }

  /** Remove the blocks associated to the given DatanodeStorageInfo. */
  void removeBlocksAssociatedTo(final DatanodeStorageInfo storageInfo) {
    assert namesystem.hasWriteLock();
    final Iterator<? extends Block> it = storageInfo.getBlockIterator();
    DatanodeDescriptor node = storageInfo.getDatanodeDescriptor();
    while(it.hasNext()) {
      Block block = it.next();
      // DatanodeStorageInfo must be removed using the iterator to avoid
      // ConcurrentModificationException in the underlying storage
      it.remove();
      removeStoredBlock(block, node);
      invalidateBlocks.remove(node, block);
    }
    namesystem.checkSafeMode();
    LOG.info("Removed blocks associated with storage {} from DataNode {}",
        storageInfo, node);
  }

  /**
   * Adds block to list of blocks which will be invalidated on specified
   * datanode and log the operation
   */
  void addToInvalidates(final Block block, final DatanodeInfo datanode) {
    if (!namesystem.isPopulatingReplQueues()) {
      return;
    }
    invalidateBlocks.add(block, datanode, true);
  }

  /**
   * Adds block to list of blocks which will be invalidated on all its
   * datanodes.
   */
  private void addToInvalidates(Block b) {
    if (!namesystem.isPopulatingReplQueues()) {
      return;
    }
    StringBuilder datanodes = new StringBuilder();
    for(DatanodeStorageInfo storage : blocksMap.getStorages(b, State.NORMAL)) {
      final DatanodeDescriptor node = storage.getDatanodeDescriptor();
      invalidateBlocks.add(b, node, false);
      datanodes.append(node).append(" ");
    }
    if (datanodes.length() != 0) {
      blockLog.info("BLOCK* addToInvalidates: {} {}", b, datanodes.toString());
    }
  }

  /**
   * Remove all block invalidation tasks under this datanode UUID;
   * used when a datanode registers with a new UUID and the old one
   * is wiped.
   */
  void removeFromInvalidates(final DatanodeInfo datanode) {
    if (!namesystem.isPopulatingReplQueues()) {
      return;
    }
    invalidateBlocks.remove(datanode);
  }

  /**
   * Mark the block belonging to datanode as corrupt
   * @param blk Block to be marked as corrupt
   * @param dn Datanode which holds the corrupt replica
   * @param storageID if known, null otherwise.
   * @param reason a textual reason why the block should be marked corrupt,
   * for logging purposes
   */
  public void findAndMarkBlockAsCorrupt(final ExtendedBlock blk,
      final DatanodeInfo dn, String storageID, String reason) throws IOException {
    assert namesystem.hasWriteLock();
    final BlockInfo storedBlock = getStoredBlock(blk.getLocalBlock());
    if (storedBlock == null) {
      // Check if the replica is in the blockMap, if not
      // ignore the request for now. This could happen when BlockScanner
      // thread of Datanode reports bad block before Block reports are sent
      // by the Datanode on startup
      blockLog.info("BLOCK* findAndMarkBlockAsCorrupt: {} not found", blk);
      return;
    }

    DatanodeDescriptor node = getDatanodeManager().getDatanode(dn);
    if (node == null) {
      throw new IOException("Cannot mark " + blk
          + " as corrupt because datanode " + dn + " (" + dn.getDatanodeUuid()
          + ") does not exist");
    }

    DatanodeStorageInfo storage = null;
    if (storageID != null) {
      storage = node.getStorageInfo(storageID);
    }
    if (storage == null) {
      storage = storedBlock.findStorageInfo(node);
    }

    if (storage == null) {
      blockLog.debug("BLOCK* findAndMarkBlockAsCorrupt: {} not found on {}",
          blk, dn);
      return;
    }
    markBlockAsCorrupt(new BlockToMarkCorrupt(storedBlock,
            blk.getGenerationStamp(), reason, Reason.CORRUPTION_REPORTED),
        storage, node);
  }

  /**
   * 
   * @param b
   * @param storageInfo storage that contains the block, if known. null otherwise.
   * @throws IOException
   */
  private void markBlockAsCorrupt(BlockToMarkCorrupt b,
      DatanodeStorageInfo storageInfo,
      DatanodeDescriptor node) throws IOException {

    if (b.corrupted.isDeleted()) {
      blockLog.info("BLOCK markBlockAsCorrupt: {} cannot be marked as" +
          " corrupt as it does not belong to any file", b);
      addToInvalidates(b.corrupted, node);
      return;
    } 
    short expectedReplicas =
        b.corrupted.getBlockCollection().getBlockReplication();

    // Add replica to the data-node if it is not already there
    if (storageInfo != null) {
      storageInfo.addBlock(b.stored);
    }

    // Add this replica to corruptReplicas Map
    corruptReplicas.addToCorruptReplicasMap(b.corrupted, node, b.reason,
        b.reasonCode);

    NumberReplicas numberOfReplicas = countNodes(b.stored);
    boolean hasEnoughLiveReplicas = numberOfReplicas.liveReplicas() >=
        expectedReplicas;
    boolean minReplicationSatisfied =
        numberOfReplicas.liveReplicas() >= minReplication;
    boolean hasMoreCorruptReplicas = minReplicationSatisfied &&
        (numberOfReplicas.liveReplicas() + numberOfReplicas.corruptReplicas()) >
        expectedReplicas;
    boolean corruptedDuringWrite = minReplicationSatisfied &&
        (b.stored.getGenerationStamp() > b.corrupted.getGenerationStamp());
    // case 1: have enough number of live replicas
    // case 2: corrupted replicas + live replicas > Replication factor
    // case 3: Block is marked corrupt due to failure while writing. In this
    //         case genstamp will be different than that of valid block.
    // In all these cases we can delete the replica.
    // In case of 3, rbw block will be deleted and valid block can be replicated
    if (hasEnoughLiveReplicas || hasMoreCorruptReplicas
        || corruptedDuringWrite) {
      // the block is over-replicated so invalidate the replicas immediately
      invalidateBlock(b, node);
    } else if (namesystem.isPopulatingReplQueues()) {
      // add the block to neededReplication
      updateNeededReplications(b.stored, -1, 0);
    }
  }

  /**
   * Invalidates the given block on the given datanode.
   * @return true if the block was successfully invalidated and no longer
   * present in the BlocksMap
   */
  private boolean invalidateBlock(BlockToMarkCorrupt b, DatanodeInfo dn
      ) throws IOException {
    blockLog.info("BLOCK* invalidateBlock: {} on {}", b, dn);
    DatanodeDescriptor node = getDatanodeManager().getDatanode(dn);
    if (node == null) {
      throw new IOException("Cannot invalidate " + b
          + " because datanode " + dn + " does not exist.");
    }

    // Check how many copies we have of the block
    NumberReplicas nr = countNodes(b.stored);
    if (nr.replicasOnStaleNodes() > 0) {
      blockLog.info("BLOCK* invalidateBlocks: postponing " +
          "invalidation of {} on {} because {} replica(s) are located on " +
          "nodes with potentially out-of-date block reports", b, dn,
          nr.replicasOnStaleNodes());
      postponeBlock(b.corrupted);
      return false;
    } else if (nr.liveReplicas() >= 1) {
      // If we have at least one copy on a live node, then we can delete it.
      addToInvalidates(b.corrupted, dn);
      removeStoredBlock(b.stored, node);
      blockLog.debug("BLOCK* invalidateBlocks: {} on {} listed for deletion.",
          b, dn);
      return true;
    } else {
      blockLog.info("BLOCK* invalidateBlocks: {} on {} is the only copy and" +
          " was not deleted", b, dn);
      return false;
    }
  }


  public void setPostponeBlocksFromFuture(boolean postpone) {
    this.shouldPostponeBlocksFromFuture  = postpone;
  }


  private void postponeBlock(Block blk) {
    postponedMisreplicatedBlocks.add(blk);
  }
  
  
  void updateState() {
    pendingReplicationBlocksCount = pendingReplications.size();
    underReplicatedBlocksCount = neededReplications.size();
    corruptReplicaBlocksCount = corruptReplicas.size();
  }

  /** Return number of under-replicated but not missing blocks */
  public int getUnderReplicatedNotMissingBlocks() {
    return neededReplications.getUnderReplicatedBlockCount();
  }
  
  /**
   * Schedule blocks for deletion at datanodes
   * @param nodesToProcess number of datanodes to schedule deletion work
   * @return total number of block for deletion
   */
  int computeInvalidateWork(int nodesToProcess) {
    final List<DatanodeInfo> nodes = invalidateBlocks.getDatanodes();
    Collections.shuffle(nodes);

    nodesToProcess = Math.min(nodes.size(), nodesToProcess);

    int blockCnt = 0;
    for (DatanodeInfo dnInfo : nodes) {
      int blocks = invalidateWorkForOneNode(dnInfo);
      if (blocks > 0) {
        blockCnt += blocks;
        if (--nodesToProcess == 0) {
          break;
        }
      }
    }
    return blockCnt;
  }

  /**
   * Scan blocks in {@link #neededReplications} and assign replication
   * work to data-nodes they belong to.
   *
   * The number of process blocks equals either twice the number of live
   * data-nodes or the number of under-replicated blocks whichever is less.
   *
   * @return number of blocks scheduled for replication during this iteration.
   */
  int computeReplicationWork(int blocksToProcess) {
    List<List<BlockInfo>> blocksToReplicate = null;
    namesystem.writeLock();
    try {
      // Choose the blocks to be replicated
      blocksToReplicate = neededReplications
          .chooseUnderReplicatedBlocks(blocksToProcess);
    } finally {
      namesystem.writeUnlock();
    }
    return computeReplicationWorkForBlocks(blocksToReplicate);
  }

  /** Replicate a set of blocks
   *
   * @param blocksToReplicate blocks to be replicated, for each priority
   * @return the number of blocks scheduled for replication
   */
  @VisibleForTesting
  int computeReplicationWorkForBlocks(List<List<BlockInfo>> blocksToReplicate) {
    int requiredReplication, numEffectiveReplicas;
    List<DatanodeDescriptor> containingNodes;
    DatanodeDescriptor srcNode;
    BlockCollection bc = null;
    int additionalReplRequired;

    int scheduledWork = 0;
    List<ReplicationWork> work = new LinkedList<ReplicationWork>();

    namesystem.writeLock();
    try {
      synchronized (neededReplications) {
        for (int priority = 0; priority < blocksToReplicate.size(); priority++) {
          for (BlockInfo block : blocksToReplicate.get(priority)) {
            // block should belong to a file
            bc = blocksMap.getBlockCollection(block);
            // abandoned block or block reopened for append
            if(bc == null || (bc.isUnderConstruction() && block.equals(bc.getLastBlock()))) {
              neededReplications.remove(block, priority); // remove from neededReplications
              continue;
            }

            // get a source data-node
            containingNodes = new ArrayList<DatanodeDescriptor>();
            List<DatanodeStorageInfo> liveReplicaNodes = new ArrayList<DatanodeStorageInfo>();
            NumberReplicas numReplicas = new NumberReplicas();
            srcNode = chooseSourceDatanode(
                block, containingNodes, liveReplicaNodes, numReplicas,
                priority);
            requiredReplication = getExpectedLiveRedundancyNum(block, numReplicas);

            if(srcNode == null) { // block can not be replicated from any node
              LOG.debug("Block " + block + " cannot be repl from any node");
              continue;
            }

            // liveReplicaNodes can include READ_ONLY_SHARED replicas which are
            // not included in the numReplicas.liveReplicas() count
            assert liveReplicaNodes.size() >= numReplicas.liveReplicas();

            // do not schedule more if enough replicas is already pending
            int pendingNum = pendingReplications.getNumReplicas(block);
            numEffectiveReplicas = numReplicas.liveReplicas() + pendingNum;
            if (hasEnoughEffectiveReplicas(block, numReplicas, pendingNum)) {
              // remove from neededReplications
              neededReplications.remove(block, priority);
              blockLog.info("BLOCK* Removing {} from neededReplications as" +
                  " it has enough replicas", block);
              continue;
            }

            if (numReplicas.liveReplicas() < requiredReplication) {
              additionalReplRequired = requiredReplication
                  - numEffectiveReplicas;
            } else {
              additionalReplRequired = 1; // Needed on a new rack
            }
            work.add(new ReplicationWork(block, bc, srcNode,
                containingNodes, liveReplicaNodes, additionalReplRequired,
                priority));
          }
        }
      }
    } finally {
      namesystem.writeUnlock();
    }

    final Set<Node> excludedNodes = new HashSet<Node>();
    for(ReplicationWork rw : work){
      // Exclude all of the containing nodes from being targets.
      // This list includes decommissioning or corrupt nodes.
      excludedNodes.clear();
      for (DatanodeDescriptor dn : rw.containingNodes) {
        excludedNodes.add(dn);
      }

      // choose replication targets: NOT HOLDING THE GLOBAL LOCK
      rw.chooseTargets(blockplacement, storagePolicySuite, excludedNodes);
    }

    namesystem.writeLock();
    try {
      for(ReplicationWork rw : work){
        final DatanodeStorageInfo[] targets = rw.targets;
        if(targets == null || targets.length == 0){
          rw.targets = null;
          continue;
        }

        synchronized (neededReplications) {
          BlockInfo block = rw.block;
          int priority = rw.priority;
          // Recheck since global lock was released
          // block should belong to a file
          bc = blocksMap.getBlockCollection(block);
          // abandoned block or block reopened for append
          if(bc == null || (bc.isUnderConstruction() && block.equals(bc.getLastBlock()))) {
            neededReplications.remove(block, priority); // remove from neededReplications
            rw.targets = null;
            continue;
          }

          // do not schedule more if enough replicas is already pending
          NumberReplicas numReplicas = countNodes(block);
          requiredReplication = getExpectedLiveRedundancyNum(block, numReplicas);
          int pendingNum = pendingReplications.getNumReplicas(block);
          numEffectiveReplicas = numReplicas.liveReplicas() + pendingNum;
          if (hasEnoughEffectiveReplicas(block, numReplicas, pendingNum)) {
            // remove from neededReplications
            neededReplications.remove(block, priority);
            rw.targets = null;
            blockLog.info("BLOCK* Removing {} from neededReplications as" +
                " it has enough replicas", block);
            continue;
          }

          if ( (numReplicas.liveReplicas() >= requiredReplication) &&
               (!isPlacementPolicySatisfied(block)) ) {
            if (rw.srcNode.getNetworkLocation().equals(
                targets[0].getDatanodeDescriptor().getNetworkLocation())) {
              //No use continuing, unless a new rack in this case
              continue;
            }
          }

          // Add block to the to be replicated list
          rw.srcNode.addBlockToBeReplicated(block, targets);
          scheduledWork++;
          DatanodeStorageInfo.incrementBlocksScheduled(targets);

          // Move the block-replication into a "pending" state.
          // The reason we use 'pending' is so we can retry
          // replications that fail after an appropriate amount of time.
          pendingReplications.increment(block,
              DatanodeStorageInfo.toDatanodeDescriptors(targets));
          blockLog.debug("BLOCK* block {} is moved from neededReplications to "
                  + "pendingReplications", block);

          // remove from neededReplications
          if(numEffectiveReplicas + targets.length >= requiredReplication) {
            neededReplications.remove(block, priority); // remove from neededReplications
          }
        }
      }
    } finally {
      namesystem.writeUnlock();
    }

    if (blockLog.isInfoEnabled()) {
      // log which blocks have been scheduled for replication
      for(ReplicationWork rw : work){
        DatanodeStorageInfo[] targets = rw.targets;
        if (targets != null && targets.length != 0) {
          StringBuilder targetList = new StringBuilder("datanode(s)");
          for (int k = 0; k < targets.length; k++) {
            targetList.append(' ');
            targetList.append(targets[k].getDatanodeDescriptor());
          }
          blockLog.info("BLOCK* ask {} to replicate {} to {}", rw.srcNode,
              rw.block, targetList);
        }
      }
    }
    if (blockLog.isDebugEnabled()) {
      blockLog.debug("BLOCK* neededReplications = {} pendingReplications = {}",
          neededReplications.size(), pendingReplications.size());
    }

    return scheduledWork;
  }

  // Check if the number of live + pending replicas satisfies
  // the expected redundancy.
  boolean hasEnoughEffectiveReplicas(BlockInfo block,
      NumberReplicas numReplicas, int pendingReplicaNum) {
    int required = getExpectedLiveRedundancyNum(block, numReplicas);
    int numEffectiveReplicas = numReplicas.liveReplicas() + pendingReplicaNum;
    return (numEffectiveReplicas >= required) &&
        (pendingReplicaNum > 0 || isPlacementPolicySatisfied(block));
  }



  /** Choose target for WebHDFS redirection. */
  public DatanodeStorageInfo[] chooseTarget4WebHDFS(String src,
      DatanodeDescriptor clientnode, Set<Node> excludes, long blocksize) {
    return blockplacement.chooseTarget(src, 1, clientnode,
        Collections.<DatanodeStorageInfo>emptyList(), false, excludes,
        blocksize, storagePolicySuite.getDefaultPolicy(), null);
  }

  /** Choose target for getting additional datanodes for an existing pipeline. */
  public DatanodeStorageInfo[] chooseTarget4AdditionalDatanode(String src,
      int numAdditionalNodes,
      Node clientnode,
      List<DatanodeStorageInfo> chosen,
      Set<Node> excludes,
      long blocksize,
      byte storagePolicyID) {
    
    final BlockStoragePolicy storagePolicy = storagePolicySuite.getPolicy(storagePolicyID);
    return blockplacement.chooseTarget(src, numAdditionalNodes, clientnode,
        chosen, true, excludes, blocksize, storagePolicy, null);
  }

  /**
   * Choose target datanodes for creating a new block.
   * 
   * @throws IOException
   *           if the number of targets < minimum replication.
   * @see BlockPlacementPolicy#chooseTarget(String, int, Node,
   *      Set, long, List, BlockStoragePolicy)
   */
  public DatanodeStorageInfo[] chooseTarget4NewBlock(final String src,
      final int numOfReplicas, final Node client,
      final Set<Node> excludedNodes,
      final long blocksize,
      final List<String> favoredNodes,
      final byte storagePolicyID,
      final EnumSet<AddBlockFlag> flags) throws IOException {
    List<DatanodeDescriptor> favoredDatanodeDescriptors =
        getDatanodeDescriptors(favoredNodes);
    final BlockStoragePolicy storagePolicy = storagePolicySuite.getPolicy(storagePolicyID);
    final DatanodeStorageInfo[] targets = blockplacement.chooseTarget(src,
        numOfReplicas, client, excludedNodes, blocksize, 
        favoredDatanodeDescriptors, storagePolicy, flags);
    if (targets.length < minReplication) {
      throw new IOException("File " + src + " could only be replicated to "
          + targets.length + " nodes instead of minReplication (="
          + minReplication + ").  There are "
          + getDatanodeManager().getNetworkTopology().getNumOfLeaves()
          + " datanode(s) running and "
          + (excludedNodes == null? "no": excludedNodes.size())
          + " node(s) are excluded in this operation.");
    }
    return targets;
  }

  /**
   * Get list of datanode descriptors for given list of nodes. Nodes are
   * hostaddress:port or just hostaddress.
   */
  List<DatanodeDescriptor> getDatanodeDescriptors(List<String> nodes) {
    List<DatanodeDescriptor> datanodeDescriptors = null;
    if (nodes != null) {
      datanodeDescriptors = new ArrayList<DatanodeDescriptor>(nodes.size());
      for (int i = 0; i < nodes.size(); i++) {
        DatanodeDescriptor node = datanodeManager.getDatanodeDescriptor(nodes.get(i));
        if (node != null) {
          datanodeDescriptors.add(node);
        }
      }
    }
    return datanodeDescriptors;
  }

  /**
   * Parse the data-nodes the block belongs to and choose one,
   * which will be the replication source.
   *
   * We prefer nodes that are in DECOMMISSION_INPROGRESS state to other nodes
   * since the former do not have write traffic and hence are less busy.
   * We do not use already decommissioned nodes as a source, unless there no
   * other choice.
   * Otherwise we choose a random node among those that did not reach their
   * replication limits.  However, if the replication is of the highest priority
   * and all nodes have reached their replication limits, we will choose a
   * random node despite the replication limit.
   *
   * In addition form a list of all nodes containing the block
   * and calculate its replication numbers.
   *
   * @param block Block for which a replication source is needed
   * @param containingNodes List to be populated with nodes found to contain the 
   *                        given block
   * @param nodesContainingLiveReplicas List to be populated with nodes found to
   *                                    contain live replicas of the given block
   * @param numReplicas NumberReplicas instance to be initialized with the 
   *                                   counts of live, corrupt, excess, and
   *                                   decommissioned replicas of the given
   *                                   block.
   * @param priority integer representing replication priority of the given
   *                 block
   * @return the DatanodeDescriptor of the chosen node from which to replicate
   *         the given block
   */
   @VisibleForTesting
   DatanodeDescriptor chooseSourceDatanode(Block block,
       List<DatanodeDescriptor> containingNodes,
       List<DatanodeStorageInfo>  nodesContainingLiveReplicas,
       NumberReplicas numReplicas,
       int priority) {
    containingNodes.clear();
    nodesContainingLiveReplicas.clear();
    DatanodeDescriptor srcNode = null;
    DatanodeDescriptor decommissionedSrc = null;
    int live = 0;
    int readonly = 0;
    int decommissioned = 0;
    int decommissioning = 0;
    int corrupt = 0;
    int excess = 0;
    int maintenanceNotForRead = 0;
    int maintenanceForRead = 0;

    Collection<DatanodeDescriptor> nodesCorrupt = corruptReplicas.getNodes(block);
    for(DatanodeStorageInfo storage : blocksMap.getStorages(block)) {
      final DatanodeDescriptor node = storage.getDatanodeDescriptor();
      LightWeightLinkedSet<Block> excessBlocks =
        excessReplicateMap.get(node.getDatanodeUuid());
      int countableReplica = storage.getState() == State.NORMAL ? 1 : 0; 
      if ((nodesCorrupt != null) && (nodesCorrupt.contains(node)))
        corrupt += countableReplica;
      else if (node.isDecommissionInProgress()) {
        decommissioning += countableReplica;
      } else if (node.isDecommissioned()) {
        decommissioned += countableReplica;
      } else if (node.isMaintenance()) {
        if (node.isInMaintenance() || !node.isAlive()) {
          maintenanceNotForRead++;
        } else {
          maintenanceForRead++;
        }
      } else if (excessBlocks != null && excessBlocks.contains(block)) {
        excess += countableReplica;
      } else {
        nodesContainingLiveReplicas.add(storage);
        live += countableReplica;
      }
      if (storage.getState() == State.READ_ONLY_SHARED) {
        readonly++;
      }
      containingNodes.add(node);
      // Check if this replica is corrupt
      // If so, do not select the node as src node
      if ((nodesCorrupt != null) && nodesCorrupt.contains(node))
        continue;
      if(priority != UnderReplicatedBlocks.QUEUE_HIGHEST_PRIORITY
          && !node.isDecommissionInProgress() && !node.isEnteringMaintenance()
          && node.getNumberOfBlocksToBeReplicated() >= maxReplicationStreams) {
        continue; // already reached replication limit
      }
      if (node.getNumberOfBlocksToBeReplicated() >= replicationStreamsHardLimit)
      {
        continue;
      }
      // the block must not be scheduled for removal on srcNode
      if(excessBlocks != null && excessBlocks.contains(block))
        continue;
      // Save the live decommissioned replica in case we need it. Such replicas
      // are normally not used for replication, but if nothing else is
      // available, one can be selected as a source.
      if (node.isDecommissioned()) {
        if (decommissionedSrc == null ||
            ThreadLocalRandom.current().nextBoolean()) {
          decommissionedSrc = node;
        }
        continue;
      }
      // Don't use dead ENTERING_MAINTENANCE or IN_MAINTENANCE nodes.
      if((!node.isAlive() && node.isEnteringMaintenance()) ||
          node.isInMaintenance()) {
        continue;
      }

      // We got this far, current node is a reasonable choice
      if (srcNode == null) {
        srcNode = node;
        continue;
      }
      // switch to a different node randomly
      // this to prevent from deterministically selecting the same node even
      // if the node failed to replicate the block on previous iterations
      if(DFSUtil.getRandom().nextBoolean())
        srcNode = node;
    }
    if(numReplicas != null)
      numReplicas.set(live, readonly, decommissioned, decommissioning, corrupt,
          excess, 0, maintenanceNotForRead, maintenanceForRead);
    // Pick a live decommissioned replica, if nothing else is available.
    if (live == 0 && srcNode == null && decommissionedSrc != null) {
      return decommissionedSrc;
    }
    return srcNode;
  }

  /**
   * If there were any replication requests that timed out, reap them
   * and put them back into the neededReplication queue
   */
  void processPendingReplications() {
    BlockInfo[] timedOutItems = pendingReplications.getTimedOutBlocks();
    if (timedOutItems != null) {
      namesystem.writeLock();
      try {
        for (int i = 0; i < timedOutItems.length; i++) {
          /*
           * Use the blockinfo from the blocksmap to be certain we're working
           * with the most up-to-date block information (e.g. genstamp).
           */
          BlockInfo bi = blocksMap.getStoredBlock(timedOutItems[i]);
          if (bi == null) {
            continue;
          }
          NumberReplicas num = countNodes(timedOutItems[i]);
          if (isNeededReplication(bi, num)) {
            neededReplications.add(bi, num.liveReplicas(),
                num.readOnlyReplicas(), num.outOfServiceReplicas(),
                getReplication(bi));
          }
        }
      } finally {
        namesystem.writeUnlock();
      }
      /* If we know the target datanodes where the replication timedout,
       * we could invoke decBlocksScheduled() on it. Its ok for now.
       */
    }
  }

  public long requestBlockReportLeaseId(DatanodeRegistration nodeReg) {
    assert namesystem.hasReadLock();
    DatanodeDescriptor node = null;
    try {
      node = datanodeManager.getDatanode(nodeReg);
    } catch (UnregisteredNodeException e) {
      LOG.warn("Unregistered datanode {}", nodeReg);
      return 0;
    }
    if (node == null) {
      LOG.warn("Failed to find datanode {}", nodeReg);
      return 0;
    }
    // Request a new block report lease.  The BlockReportLeaseManager has
    // its own internal locking.
    long leaseId = blockReportLeaseManager.requestLease(node);
    BlockManagerFaultInjector.getInstance().
        requestBlockReportLease(node, leaseId);
    return leaseId;
  }

  /**
   * StatefulBlockInfo is used to build the "toUC" list, which is a list of
   * updates to the information about under-construction blocks.
   * Besides the block in question, it provides the ReplicaState
   * reported by the datanode in the block report. 
   */
  static class StatefulBlockInfo {
    final BlockInfoUnderConstruction storedBlock;
    final Block reportedBlock;
    final ReplicaState reportedState;
    
    StatefulBlockInfo(BlockInfoUnderConstruction storedBlock,
        Block reportedBlock, ReplicaState reportedState) {
      this.storedBlock = storedBlock;
      this.reportedBlock = reportedBlock;
      this.reportedState = reportedState;
    }
  }

  /**
   * BlockToMarkCorrupt is used to build the "toCorrupt" list, which is a
   * list of blocks that should be considered corrupt due to a block report.
   */
  private static class BlockToMarkCorrupt {
    /** The corrupted block in a datanode. */
    final BlockInfo corrupted;
    /** The corresponding block stored in the BlockManager. */
    final BlockInfo stored;
    /** The reason to mark corrupt. */
    final String reason;
    /** The reason code to be stored */
    final Reason reasonCode;

    BlockToMarkCorrupt(BlockInfo corrupted, BlockInfo stored, String reason,
        Reason reasonCode) {
      Preconditions.checkNotNull(corrupted, "corrupted is null");
      Preconditions.checkNotNull(stored, "stored is null");

      this.corrupted = corrupted;
      this.stored = stored;
      this.reason = reason;
      this.reasonCode = reasonCode;
    }

    BlockToMarkCorrupt(BlockInfo stored, String reason, Reason reasonCode) {
      this(stored, stored, reason, reasonCode);
    }

    BlockToMarkCorrupt(BlockInfo stored, long gs, String reason,
        Reason reasonCode) {
      this(new BlockInfo(stored), stored, reason, reasonCode);
      //the corrupted block in datanode has a different generation stamp
      corrupted.setGenerationStamp(gs);
    }

    @Override
    public String toString() {
      return corrupted + "("
          + (corrupted == stored? "same as stored": "stored=" + stored) + ")";
    }
  }

  /**
   * The given storage is reporting all its blocks.
   * Update the (storage-->block list) and (block-->storage list) maps.
   *
   * @return true if all known storages of the given DN have finished reporting.
   * @throws IOException
   */
  public boolean processReport(final DatanodeID nodeID,
      final DatanodeStorage storage,
      final BlockListAsLongs newReport,
      BlockReportContext context) throws IOException {
    namesystem.writeLock();
    final long startTime = Time.monotonicNow(); //after acquiring write lock
    final long endTime;
    DatanodeDescriptor node;
    Collection<Block> invalidatedBlocks = Collections.emptyList();
    String strBlockReportId =
        context != null ? Long.toHexString(context.getReportId()) : "";

    try {
      node = datanodeManager.getDatanode(nodeID);
      if (node == null || !node.isRegistered()) {
        throw new IOException(
            "ProcessReport from dead or unregistered node: " + nodeID);
      }

      // To minimize startup time, we discard any second (or later) block reports
      // that we receive while still in startup phase.
      DatanodeStorageInfo storageInfo = node.getStorageInfo(storage.getStorageID());

      if (storageInfo == null) {
        // We handle this for backwards compatibility.
        storageInfo = node.updateStorage(storage);
      }
      if (namesystem.isInStartupSafeMode()
          && storageInfo.getBlockReportCount() > 0) {
        blockLog.info("BLOCK* processReport 0x{}: "
            + "discarded non-initial block report from {}"
            + " because namenode still in startup phase",
            strBlockReportId, nodeID);
        blockReportLeaseManager.removeLease(node);
        return !node.hasStaleStorages();
      }
      if (context != null) {
        if (!blockReportLeaseManager.checkLease(node, startTime,
              context.getLeaseId())) {
          return false;
        }
      }

      if (storageInfo.getBlockReportCount() == 0) {
        // The first block report can be processed a lot more efficiently than
        // ordinary block reports.  This shortens restart times.
        blockLog.info("BLOCK* processReport 0x{}: Processing first "
            + "storage report for {} from datanode {}",
            strBlockReportId,
            storageInfo.getStorageID(),
            nodeID.getDatanodeUuid());
        processFirstBlockReport(storageInfo, newReport);
      } else {
        invalidatedBlocks = processReport(storageInfo, newReport, context);
      }
      
      storageInfo.receivedBlockReport();
    } finally {
      endTime = Time.monotonicNow();
      namesystem.writeUnlock();
    }

    for (Block b : invalidatedBlocks) {
      blockLog.debug("BLOCK* processReport 0x{}: {} on node {} size {} does not"
          + " belong to any file", strBlockReportId, b, node, b.getNumBytes());
    }

    // Log the block report processing stats from Namenode perspective
    final NameNodeMetrics metrics = NameNode.getNameNodeMetrics();
    if (metrics != null) {
      metrics.addBlockReport((int) (endTime - startTime));
    }
    blockLog.info("BLOCK* processReport 0x{}: from storage {} node {}, " +
        "blocks: {}, hasStaleStorage: {}, processing time: {} msecs, " +
        "invalidatedBlocks: {}", strBlockReportId, storage.getStorageID(),
        nodeID, newReport.getNumberOfBlocks(),
        node.hasStaleStorages(), (endTime - startTime),
        invalidatedBlocks.size());
    return !node.hasStaleStorages();
  }

  public void removeBRLeaseIfNeeded(final DatanodeID nodeID,
      final BlockReportContext context) throws IOException {
    namesystem.writeLock();
    DatanodeDescriptor node;
    try {
      node = datanodeManager.getDatanode(nodeID);
      if (context != null) {
        if (context.getTotalRpcs() == context.getCurRpc() + 1) {
          long leaseId = this.getBlockReportLeaseManager().removeLease(node);
          BlockManagerFaultInjector.getInstance().
              removeBlockReportLease(node, leaseId);
        }
        LOG.debug("Processing RPC with index {} out of total {} RPCs in "
                + "processReport 0x{}", context.getCurRpc(),
            context.getTotalRpcs(), Long.toHexString(context.getReportId()));
      }
    } finally {
      namesystem.writeUnlock();
    }
  }

  /**
   * Rescan the list of blocks which were previously postponed.
   */
  void rescanPostponedMisreplicatedBlocks() {
    if (getPostponedMisreplicatedBlocksCount() == 0) {
      return;
    }
    namesystem.writeLock();
    long startTime = Time.monotonicNow();
    long startSize = postponedMisreplicatedBlocks.size();
    try {
      Iterator<Block> it = postponedMisreplicatedBlocks.iterator();
      for (int i=0; i < blocksPerPostpondedRescan && it.hasNext(); i++) {
        Block b = it.next();
        it.remove();

        BlockInfo bi = blocksMap.getStoredBlock(b);
        if (bi == null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("BLOCK* rescanPostponedMisreplicatedBlocks: " +
                "Postponed mis-replicated block " + b + " no longer found " +
                "in block map.");
          }
          continue;
        }
        MisReplicationResult res = processMisReplicatedBlock(bi);
        if (LOG.isDebugEnabled()) {
          LOG.debug("BLOCK* rescanPostponedMisreplicatedBlocks: " +
              "Re-scanned block " + b + ", result is " + res);
        }
        if (res == MisReplicationResult.POSTPONE) {
          rescannedMisreplicatedBlocks.add(b);
        }
      }
    } finally {
      postponedMisreplicatedBlocks.addAll(rescannedMisreplicatedBlocks);
      rescannedMisreplicatedBlocks.clear();
      long endSize = postponedMisreplicatedBlocks.size();
      namesystem.writeUnlock();
      LOG.info("Rescan of postponedMisreplicatedBlocks completed in " +
          (Time.monotonicNow() - startTime) + " msecs. " +
          endSize + " blocks are left. " +
          (startSize - endSize) + " blocks were removed.");
    }
  }
  
  private Collection<Block> processReport(
      final DatanodeStorageInfo storageInfo,
      final BlockListAsLongs report,
      BlockReportContext context) throws IOException {
    // Normal case:
    // Modify the (block-->datanode) map, according to the difference
    // between the old and new block report.
    //
    Collection<BlockInfo> toAdd = new LinkedList<BlockInfo>();
    Collection<Block> toRemove = new TreeSet<Block>();
    Collection<Block> toInvalidate = new LinkedList<Block>();
    Collection<BlockToMarkCorrupt> toCorrupt = new LinkedList<BlockToMarkCorrupt>();
    Collection<StatefulBlockInfo> toUC = new LinkedList<StatefulBlockInfo>();

    boolean sorted = false;
    String strBlockReportId = "";
    if (context != null) {
      sorted = context.isSorted();
      strBlockReportId = Long.toHexString(context.getReportId());
    }

    Iterable<BlockReportReplica> sortedReport;
    if (!sorted) {
      blockLog.warn("BLOCK* processReport 0x{}: Report from the DataNode ({}) "
                    + "is unsorted. This will cause overhead on the NameNode "
                    + "which needs to sort the Full BR. Please update the "
                    + "DataNode to the same version of Hadoop HDFS as the "
                    + "NameNode ({}).",
                    strBlockReportId,
                    storageInfo.getDatanodeDescriptor().getDatanodeUuid(),
                    VersionInfo.getVersion());
      Set<BlockReportReplica> set = new FoldedTreeSet<>();
      for (BlockReportReplica iblk : report) {
        set.add(new BlockReportReplica(iblk));
      }
      sortedReport = set;
    } else {
      sortedReport = report;
    }

    reportDiffSorted(storageInfo, sortedReport,
                     toAdd, toRemove, toInvalidate, toCorrupt, toUC);

    DatanodeDescriptor node = storageInfo.getDatanodeDescriptor();
    // Process the blocks on each queue
    for (StatefulBlockInfo b : toUC) { 
      addStoredBlockUnderConstruction(b, storageInfo);
    }
    for (Block b : toRemove) {
      removeStoredBlock(b, node);
    }
    int numBlocksLogged = 0;
    for (BlockInfo b : toAdd) {
      addStoredBlock(b, storageInfo, null, numBlocksLogged < maxNumBlocksToLog);
      numBlocksLogged++;
    }
    if (numBlocksLogged > maxNumBlocksToLog) {
      blockLog.info("BLOCK* processReport 0x{}: logged info for {} of {} " +
          "reported.", strBlockReportId, maxNumBlocksToLog, numBlocksLogged);
    }
    for (Block b : toInvalidate) {
      addToInvalidates(b, node);
    }
    for (BlockToMarkCorrupt b : toCorrupt) {
      markBlockAsCorrupt(b, storageInfo, node);
    }

    return toInvalidate;
  }

  /**
   * Mark block replicas as corrupt except those on the storages in 
   * newStorages list.
   */
  public void markBlockReplicasAsCorrupt(BlockInfo block,
      long oldGenerationStamp, long oldNumBytes, 
      DatanodeStorageInfo[] newStorages) throws IOException {
    assert namesystem.hasWriteLock();
    BlockToMarkCorrupt b = null;
    if (block.getGenerationStamp() != oldGenerationStamp) {
      b = new BlockToMarkCorrupt(block, oldGenerationStamp,
          "genstamp does not match " + oldGenerationStamp
          + " : " + block.getGenerationStamp(), Reason.GENSTAMP_MISMATCH);
    } else if (block.getNumBytes() != oldNumBytes) {
      b = new BlockToMarkCorrupt(block,
          "length does not match " + oldNumBytes
          + " : " + block.getNumBytes(), Reason.SIZE_MISMATCH);
    } else {
      return;
    }

    for (DatanodeStorageInfo storage : getStorages(block)) {
      boolean isCorrupt = true;
      if (newStorages != null) {
        for (DatanodeStorageInfo newStorage : newStorages) {
          if (newStorage!= null && storage.equals(newStorage)) {
            isCorrupt = false;
            break;
          }
        }
      }
      if (isCorrupt) {
        blockLog.info("BLOCK* markBlockReplicasAsCorrupt: mark block replica" +
            " {} on {} as corrupt because the dn is not in the new committed " +
            "storage list.", b, storage.getDatanodeDescriptor());
        markBlockAsCorrupt(b, storage, storage.getDatanodeDescriptor());
      }
    }
  }

  /**
   * processFirstBlockReport is intended only for processing "initial" block
   * reports, the first block report received from a DN after it registers.
   * It just adds all the valid replicas to the datanode, without calculating 
   * a toRemove list (since there won't be any).  It also silently discards 
   * any invalid blocks, thereby deferring their processing until 
   * the next block report.
   * @param storageInfo - DatanodeStorageInfo that sent the report
   * @param report - the initial block report, to be processed
   * @throws IOException 
   */
  private void processFirstBlockReport(
      final DatanodeStorageInfo storageInfo,
      final BlockListAsLongs report) throws IOException {
    if (report == null) return;
    assert (namesystem.hasWriteLock());
    assert (storageInfo.getBlockReportCount() == 0);

    for (BlockReportReplica iblk : report) {
      ReplicaState reportedState = iblk.getState();

      if (LOG.isDebugEnabled()) {
        LOG.debug("Initial report of block " + iblk.getBlockName()
            + " on " + storageInfo.getDatanodeDescriptor() + " size " +
            iblk.getNumBytes() + " replicaState = " + reportedState);
      }
      if (shouldPostponeBlocksFromFuture &&
          namesystem.isGenStampInFuture(iblk)) {
        queueReportedBlock(storageInfo, iblk, reportedState,
            QUEUE_REASON_FUTURE_GENSTAMP);
        continue;
      }
      
      BlockInfo storedBlock = blocksMap.getStoredBlock(iblk);
      // If block does not belong to any file, we are done.
      if (storedBlock == null) continue;
      
      // If block is corrupt, mark it and continue to next block.
      BlockUCState ucState = storedBlock.getBlockUCState();
      BlockToMarkCorrupt c = checkReplicaCorrupt(
          iblk, reportedState, storedBlock, ucState,
          storageInfo.getDatanodeDescriptor());
      if (c != null) {
        if (shouldPostponeBlocksFromFuture) {
          // In the Standby, we may receive a block report for a file that we
          // just have an out-of-date gen-stamp or state for, for example.
          queueReportedBlock(storageInfo, iblk, reportedState,
              QUEUE_REASON_CORRUPT_STATE);
        } else {
          markBlockAsCorrupt(c, storageInfo, storageInfo.getDatanodeDescriptor());
        }
        continue;
      }
      
      // If block is under construction, add this replica to its list
      if (isBlockUnderConstruction(storedBlock, ucState, reportedState)) {
        ((BlockInfoUnderConstruction)storedBlock).addReplicaIfNotPresent(
            storageInfo, iblk, reportedState);
        // OpenFileBlocks only inside snapshots also will be added to safemode
        // threshold. So we need to update such blocks to safemode
        // refer HDFS-5283
        BlockInfoUnderConstruction blockUC = (BlockInfoUnderConstruction) storedBlock;
        if (namesystem.isInSnapshot(blockUC)) {
          int numOfReplicas = blockUC.getNumExpectedLocations();
          namesystem.incrementSafeBlockCount(numOfReplicas);
        }
        //and fall through to next clause
      }      
      //add replica if appropriate
      if (reportedState == ReplicaState.FINALIZED) {
        addStoredBlockImmediate(storedBlock, storageInfo);
      }
    }
  }

  private void reportDiffSorted(DatanodeStorageInfo storageInfo,
      Iterable<BlockReportReplica> newReport,
      Collection<BlockInfo> toAdd,              // add to DatanodeDescriptor
      Collection<Block> toRemove,           // remove from DatanodeDescriptor
      Collection<Block> toInvalidate,       // should be removed from DN
      Collection<BlockToMarkCorrupt> toCorrupt, // add to corrupt replicas list
      Collection<StatefulBlockInfo> toUC) { // add to under-construction list

    // The blocks must be sorted and the storagenodes blocks must be sorted
    Iterator<BlockInfo> storageBlocksIterator = storageInfo.getBlockIterator();
    DatanodeDescriptor dn = storageInfo.getDatanodeDescriptor();
    BlockInfo storageBlock = null;

    for (BlockReportReplica replica : newReport) {

      long replicaID = replica.getBlockId();
      ReplicaState reportedState = replica.getState();

      if (LOG.isDebugEnabled()) {
        LOG.debug("Reported block " + replica
                  + " on " + dn + " size " + replica.getNumBytes()
                  + " replicaState = " + reportedState);
      }

      if (shouldPostponeBlocksFromFuture
          && namesystem.isGenStampInFuture(replica)) {
        queueReportedBlock(storageInfo, replica, reportedState,
                           QUEUE_REASON_FUTURE_GENSTAMP);
        continue;
      }

      if (storageBlock == null && storageBlocksIterator.hasNext()) {
        storageBlock = storageBlocksIterator.next();
      }

      do {
        int cmp;
        if (storageBlock == null ||
            (cmp = Long.compare(replicaID, storageBlock.getBlockId())) < 0) {
          // Check if block is available in NN but not yet on this storage
          BlockInfo nnBlock = getStoredBlock(replica);
          if (nnBlock != null) {
            reportDiffSortedInner(storageInfo, replica, reportedState,
                                  nnBlock, toAdd, toCorrupt, toUC);
          } else {
            // Replica not found anywhere so it should be invalidated
            toInvalidate.add(new Block(replica));
          }
          break;
        } else if (cmp == 0) {
          // Replica matched current storageblock
          reportDiffSortedInner(storageInfo, replica, reportedState,
                                storageBlock, toAdd, toCorrupt, toUC);
          storageBlock = null;
        } else {
          // replica has higher ID than storedBlock
          // Remove all stored blocks with IDs lower than replica
          do {
            toRemove.add(storageBlock);
            storageBlock = storageBlocksIterator.hasNext()
                           ? storageBlocksIterator.next() : null;
          } while (storageBlock != null &&
                   Long.compare(replicaID, storageBlock.getBlockId()) > 0);
        }
      } while (storageBlock != null);
    }

    // Iterate any remaing blocks that have not been reported and remove them
    while (storageBlocksIterator.hasNext()) {
      toRemove.add(storageBlocksIterator.next());
    }
  }

  private void reportDiffSortedInner(
      final DatanodeStorageInfo storageInfo,
      final BlockReportReplica replica, final ReplicaState reportedState,
      final BlockInfo storedBlock,
      final Collection<BlockInfo> toAdd,
      final Collection<BlockToMarkCorrupt> toCorrupt,
      final Collection<StatefulBlockInfo> toUC) {

    assert replica != null;
    assert storedBlock != null;

    DatanodeDescriptor dn = storageInfo.getDatanodeDescriptor();
    BlockUCState ucState = storedBlock.getBlockUCState();

    // Block is on the NN
    if (LOG.isDebugEnabled()) {
      LOG.debug("In memory blockUCState = " + ucState);
    }

    // Ignore replicas already scheduled to be removed from the DN
    if (invalidateBlocks.contains(dn, replica)) {
      return;
    }

    BlockToMarkCorrupt c = checkReplicaCorrupt(replica, reportedState,
                                               storedBlock, ucState, dn);
    if (c != null) {
      if (shouldPostponeBlocksFromFuture) {
        // If the block is an out-of-date generation stamp or state,
        // but we're the standby, we shouldn't treat it as corrupt,
        // but instead just queue it for later processing.
        // TODO: Pretty confident this should be s/storedBlock/block below,
        // since we should be postponing the info of the reported block, not
        // the stored block. See HDFS-6289 for more context.
        queueReportedBlock(storageInfo, storedBlock, reportedState,
            QUEUE_REASON_CORRUPT_STATE);
      } else {
        toCorrupt.add(c);
      }
    } else if (isBlockUnderConstruction(storedBlock, ucState, reportedState)) {
      toUC.add(new StatefulBlockInfo((BlockInfoUnderConstruction) storedBlock,
          new Block(replica), reportedState));
    } else if (reportedState == ReplicaState.FINALIZED &&
               (storedBlock.findStorageInfo(storageInfo) == -1 ||
                corruptReplicas.isReplicaCorrupt(storedBlock, dn))) {
      // Add replica if appropriate. If the replica was previously corrupt
      // but now okay, it might need to be updated.
      toAdd.add(storedBlock);
    }
  }

  /**
   * Queue the given reported block for later processing in the
   * standby node. @see PendingDataNodeMessages.
   * @param reason a textual reason to report in the debug logs
   */
  private void queueReportedBlock(DatanodeStorageInfo storageInfo, Block block,
      ReplicaState reportedState, String reason) {
    assert shouldPostponeBlocksFromFuture;
    
    if (LOG.isDebugEnabled()) {
      LOG.debug("Queueing reported block " + block +
          " in state " + reportedState + 
          " from datanode " + storageInfo.getDatanodeDescriptor() +
          " for later processing because " + reason + ".");
    }
    pendingDNMessages.enqueueReportedBlock(storageInfo, block, reportedState);
  }

  /**
   * Try to process any messages that were previously queued for the given
   * block. This is called from FSEditLogLoader whenever a block's state
   * in the namespace has changed or a new block has been created.
   */
  public void processQueuedMessagesForBlock(Block b) throws IOException {
    Queue<ReportedBlockInfo> queue = pendingDNMessages.takeBlockQueue(b);
    if (queue == null) {
      // Nothing to re-process
      return;
    }
    processQueuedMessages(queue);
  }
  
  private void processQueuedMessages(Iterable<ReportedBlockInfo> rbis)
      throws IOException {
    for (ReportedBlockInfo rbi : rbis) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Processing previouly queued message " + rbi);
      }
      if (rbi.getReportedState() == null) {
        // This is a DELETE_BLOCK request
        DatanodeStorageInfo storageInfo = rbi.getStorageInfo();
        removeStoredBlock(rbi.getBlock(),
            storageInfo.getDatanodeDescriptor());
      } else {
        processAndHandleReportedBlock(rbi.getStorageInfo(),
            rbi.getBlock(), rbi.getReportedState(), null);
      }
    }
  }
  
  /**
   * Process any remaining queued datanode messages after entering
   * active state. At this point they will not be re-queued since
   * we are the definitive master node and thus should be up-to-date
   * with the namespace information.
   */
  public void processAllPendingDNMessages() throws IOException {
    assert !shouldPostponeBlocksFromFuture :
      "processAllPendingDNMessages() should be called after disabling " +
      "block postponement.";
    int count = pendingDNMessages.count();
    if (count > 0) {
      LOG.info("Processing " + count + " messages from DataNodes " +
          "that were previously queued during standby state");
    }
    processQueuedMessages(pendingDNMessages.takeAll());
    assert pendingDNMessages.count() == 0;
  }

  /**
   * The next two methods test the various cases under which we must conclude
   * the replica is corrupt, or under construction.  These are laid out
   * as switch statements, on the theory that it is easier to understand
   * the combinatorics of reportedState and ucState that way.  It should be
   * at least as efficient as boolean expressions.
   * 
   * @return a BlockToMarkCorrupt object, or null if the replica is not corrupt
   */
  private BlockToMarkCorrupt checkReplicaCorrupt(
      Block reported, ReplicaState reportedState, 
      BlockInfo storedBlock, BlockUCState ucState,
      DatanodeDescriptor dn) {
    switch(reportedState) {
    case FINALIZED:
      switch(ucState) {
      case COMPLETE:
      case COMMITTED:
        if (storedBlock.getGenerationStamp() != reported.getGenerationStamp()) {
          final long reportedGS = reported.getGenerationStamp();
          return new BlockToMarkCorrupt(storedBlock, reportedGS,
              "block is " + ucState + " and reported genstamp " + reportedGS
              + " does not match genstamp in block map "
              + storedBlock.getGenerationStamp(), Reason.GENSTAMP_MISMATCH);
        } else if (storedBlock.getNumBytes() != reported.getNumBytes()) {
          return new BlockToMarkCorrupt(storedBlock,
              "block is " + ucState + " and reported length " +
              reported.getNumBytes() + " does not match " +
              "length in block map " + storedBlock.getNumBytes(),
              Reason.SIZE_MISMATCH);
        } else {
          return null; // not corrupt
        }
      case UNDER_CONSTRUCTION:
        if (storedBlock.getGenerationStamp() > reported.getGenerationStamp()) {
          final long reportedGS = reported.getGenerationStamp();
          return new BlockToMarkCorrupt(storedBlock, reportedGS, "block is "
              + ucState + " and reported state " + reportedState
              + ", But reported genstamp " + reportedGS
              + " does not match genstamp in block map "
              + storedBlock.getGenerationStamp(), Reason.GENSTAMP_MISMATCH);
        }
        return null;
      default:
        return null;
      }
    case RBW:
    case RWR:
      if (!storedBlock.isComplete()) {
        return null; // not corrupt
      } else if (storedBlock.getGenerationStamp() != reported.getGenerationStamp()) {
        final long reportedGS = reported.getGenerationStamp();
        return new BlockToMarkCorrupt(storedBlock, reportedGS,
            "reported " + reportedState + " replica with genstamp " + reportedGS
            + " does not match COMPLETE block's genstamp in block map "
            + storedBlock.getGenerationStamp(), Reason.GENSTAMP_MISMATCH);
      } else { // COMPLETE block, same genstamp
        if (reportedState == ReplicaState.RBW) {
          // If it's a RBW report for a COMPLETE block, it may just be that
          // the block report got a little bit delayed after the pipeline
          // closed. So, ignore this report, assuming we will get a
          // FINALIZED replica later. See HDFS-2791
          LOG.info("Received an RBW replica for " + storedBlock +
              " on " + dn + ": ignoring it, since it is " +
              "complete with the same genstamp");
          return null;
        } else {
          return new BlockToMarkCorrupt(storedBlock,
              "reported replica has invalid state " + reportedState,
              Reason.INVALID_STATE);
        }
      }
    case RUR:       // should not be reported
    case TEMPORARY: // should not be reported
    default:
      String msg = "Unexpected replica state " + reportedState
      + " for block: " + storedBlock + 
      " on " + dn + " size " + storedBlock.getNumBytes();
      // log here at WARN level since this is really a broken HDFS invariant
      LOG.warn(msg);
      return new BlockToMarkCorrupt(storedBlock, msg, Reason.INVALID_STATE);
    }
  }

  private boolean isBlockUnderConstruction(BlockInfo storedBlock,
      BlockUCState ucState, ReplicaState reportedState) {
    switch(reportedState) {
    case FINALIZED:
      switch(ucState) {
      case UNDER_CONSTRUCTION:
      case UNDER_RECOVERY:
        return true;
      default:
        return false;
      }
    case RBW:
    case RWR:
      return (!storedBlock.isComplete());
    case RUR:       // should not be reported                                                                                             
    case TEMPORARY: // should not be reported                                                                                             
    default:
      return false;
    }
  }

  void addStoredBlockUnderConstruction(StatefulBlockInfo ucBlock,
      DatanodeStorageInfo storageInfo) throws IOException {
    BlockInfoUnderConstruction block = ucBlock.storedBlock;
    block.addReplicaIfNotPresent(
        storageInfo, ucBlock.reportedBlock, ucBlock.reportedState);

    if (ucBlock.reportedState == ReplicaState.FINALIZED &&
        (block.findStorageInfo(storageInfo) < 0)) {
      addStoredBlock(block, storageInfo, null, true);
    }
  } 

  /**
   * Faster version of
   * {@link #addStoredBlock(BlockInfo, DatanodeStorageInfo, DatanodeDescriptor, boolean)}
   * , intended for use with initial block report at startup. If not in startup
   * safe mode, will call standard addStoredBlock(). Assumes this method is
   * called "immediately" so there is no need to refresh the storedBlock from
   * blocksMap. Doesn't handle underReplication/overReplication, or worry about
   * pendingReplications or corruptReplicas, because it's in startup safe mode.
   * Doesn't log every block, because there are typically millions of them.
   * 
   * @throws IOException
   */
  private void addStoredBlockImmediate(BlockInfo storedBlock,
      DatanodeStorageInfo storageInfo)
  throws IOException {
    assert (storedBlock != null && namesystem.hasWriteLock());
    if (!namesystem.isInStartupSafeMode() 
        || namesystem.isPopulatingReplQueues()) {
      addStoredBlock(storedBlock, storageInfo, null, false);
      return;
    }

    // just add it
    AddBlockResult result = storageInfo.addBlockInitial(storedBlock);

    // Now check for completion of blocks and safe block count
    int numCurrentReplica = countLiveNodes(storedBlock);
    if (storedBlock.getBlockUCState() == BlockUCState.COMMITTED
        && numCurrentReplica >= minReplication) {
      completeBlock(storedBlock.getBlockCollection(), storedBlock, false);
    } else if (storedBlock.isComplete() && result == AddBlockResult.ADDED) {
      // check whether safe replication is reached for the block
      // only complete blocks are counted towards that.
      // In the case that the block just became complete above, completeBlock()
      // handles the safe block count maintenance.
      namesystem.incrementSafeBlockCount(numCurrentReplica);
    }
  }

  /**
   * Modify (block-->datanode) map. Remove block from set of
   * needed replications if this takes care of the problem.
   * @return the block that is stored in blockMap.
   */
  private Block addStoredBlock(final BlockInfo block,
                               DatanodeStorageInfo storageInfo,
                               DatanodeDescriptor delNodeHint,
                               boolean logEveryBlock)
  throws IOException {
    assert block != null && namesystem.hasWriteLock();
    BlockInfo storedBlock;
    DatanodeDescriptor node = storageInfo.getDatanodeDescriptor();
    if (block instanceof BlockInfoUnderConstruction) {
      //refresh our copy in case the block got completed in another thread
      storedBlock = blocksMap.getStoredBlock(block);
    } else {
      storedBlock = block;
    }
    if (storedBlock == null || storedBlock.isDeleted()) {
      // If this block does not belong to anyfile, then we are done.
      blockLog.info("BLOCK* addStoredBlock: {} on {} size {} but it does not" +
          " belong to any file", block, node, block.getNumBytes());

      // we could add this block to invalidate set of this datanode.
      // it will happen in next block report otherwise.
      return block;
    }
    BlockCollection bc = storedBlock.getBlockCollection();
    assert bc != null : "Block must belong to a file";

    // add block to the datanode
    AddBlockResult result = storageInfo.addBlock(storedBlock);

    int curReplicaDelta;
    if (result == AddBlockResult.ADDED) {
      curReplicaDelta = (node.isDecommissioned()) ? 0 : 1;
      if (logEveryBlock) {
        logAddStoredBlock(storedBlock, node);
      }
    } else if (result == AddBlockResult.REPLACED) {
      curReplicaDelta = 0;
      blockLog.warn("BLOCK* addStoredBlock: block {} moved to storageType " +
          "{} on node {}", storedBlock, storageInfo.getStorageType(), node);
    } else {
      // if the same block is added again and the replica was corrupt
      // previously because of a wrong gen stamp, remove it from the
      // corrupt block list.
      corruptReplicas.removeFromCorruptReplicasMap(block, node,
          Reason.GENSTAMP_MISMATCH);
      curReplicaDelta = 0;
      blockLog.debug("BLOCK* addStoredBlock: Redundant addStoredBlock request"
              + " received for {} on node {} size {}", storedBlock, node,
          storedBlock.getNumBytes());
    }

    // Now check for completion of blocks and safe block count
    NumberReplicas num = countNodes(storedBlock);
    int numLiveReplicas = num.liveReplicas();
    int pendingNum = pendingReplications.getNumReplicas(storedBlock);
    int numCurrentReplica = numLiveReplicas + pendingNum;

    if(storedBlock.getBlockUCState() == BlockUCState.COMMITTED &&
        numLiveReplicas >= minReplication) {
      storedBlock = completeBlock(bc, storedBlock, false);
    } else if (storedBlock.isComplete() && result == AddBlockResult.ADDED) {
      // check whether safe replication is reached for the block
      // only complete blocks are counted towards that
      // Is no-op if not in safe mode.
      // In the case that the block just became complete above, completeBlock()
      // handles the safe block count maintenance.
      namesystem.incrementSafeBlockCount(numCurrentReplica);
    }
    
    // if file is under construction, then done for now
    if (bc.isUnderConstruction()) {
      return storedBlock;
    }

    // do not try to handle over/under-replicated blocks during first safe mode
    if (!namesystem.isPopulatingReplQueues()) {
      return storedBlock;
    }

    // handle underReplication/overReplication
    short fileReplication = bc.getBlockReplication();
    if (!isNeededReplication(storedBlock, num, pendingNum)) {
      neededReplications.remove(storedBlock, numCurrentReplica,
          num.readOnlyReplicas(), num.outOfServiceReplicas(), fileReplication);
    } else {
      updateNeededReplications(storedBlock, curReplicaDelta, 0);
    }
    if (numCurrentReplica > fileReplication) {
      processOverReplicatedBlock(storedBlock, fileReplication, node, delNodeHint);
    }
    // If the file replication has reached desired value
    // we can remove any corrupt replicas the block may have
    int corruptReplicasCount = corruptReplicas.numCorruptReplicas(storedBlock);
    int numCorruptNodes = num.corruptReplicas();
    if (numCorruptNodes != corruptReplicasCount) {
      LOG.warn("Inconsistent number of corrupt replicas for " +
          storedBlock + "blockMap has " + numCorruptNodes + 
          " but corrupt replicas map has " + corruptReplicasCount);
    }
    if ((corruptReplicasCount > 0) && (numLiveReplicas >= fileReplication))
      invalidateCorruptReplicas(storedBlock);
    return storedBlock;
  }

  private void logAddStoredBlock(BlockInfo storedBlock, DatanodeDescriptor node) {
    if (!blockLog.isInfoEnabled()) {
      return;
    }

    StringBuilder sb = new StringBuilder(500);
    sb.append("BLOCK* addStoredBlock: blockMap updated: ")
      .append(node)
      .append(" is added to ");
    storedBlock.appendStringTo(sb);
    sb.append(" size " )
      .append(storedBlock.getNumBytes());
    blockLog.info(sb.toString());
  }
  /**
   * Invalidate corrupt replicas.
   * <p>
   * This will remove the replicas from the block's location list,
   * add them to {@link #invalidateBlocks} so that they could be further
   * deleted from the respective data-nodes,
   * and remove the block from corruptReplicasMap.
   * <p>
   * This method should be called when the block has sufficient
   * number of live replicas.
   *
   * @param blk Block whose corrupt replicas need to be invalidated
   */
  private void invalidateCorruptReplicas(BlockInfo blk) {
    Collection<DatanodeDescriptor> nodes = corruptReplicas.getNodes(blk);
    boolean removedFromBlocksMap = true;
    if (nodes == null)
      return;
    // make a copy of the array of nodes in order to avoid
    // ConcurrentModificationException, when the block is removed from the node
    DatanodeDescriptor[] nodesCopy = nodes.toArray(new DatanodeDescriptor[0]);
    for (DatanodeDescriptor node : nodesCopy) {
      try {
        if (!invalidateBlock(new BlockToMarkCorrupt(blk, null,
              Reason.ANY), node)) {
          removedFromBlocksMap = false;
        }
      } catch (IOException e) {
        blockLog.info("invalidateCorruptReplicas error in deleting bad block"
            + " {} on {}", blk, node, e);
        removedFromBlocksMap = false;
      }
    }
    // Remove the block from corruptReplicasMap
    if (removedFromBlocksMap) {
      corruptReplicas.removeFromCorruptReplicasMap(blk);
    }
  }

  /**
   * For each block in the name-node verify whether it belongs to any file,
   * over or under replicated. Place it into the respective queue.
   */
  public void processMisReplicatedBlocks() {
    assert namesystem.hasWriteLock();
    stopReplicationInitializer();
    neededReplications.clear();
    replicationQueuesInitializer = new Daemon() {

      @Override
      public void run() {
        try {
          processMisReplicatesAsync();
        } catch (InterruptedException ie) {
          LOG.info("Interrupted while processing replication queues.");
        } catch (Exception e) {
          LOG.error("Error while processing replication queues async", e);
        }
      }
    };
    replicationQueuesInitializer.setName("Replication Queue Initializer");
    replicationQueuesInitializer.start();
  }

  /*
   * Stop the ongoing initialisation of replication queues
   */
  private void stopReplicationInitializer() {
    if (replicationQueuesInitializer != null) {
      replicationQueuesInitializer.interrupt();
      try {
        replicationQueuesInitializer.join();
      } catch (final InterruptedException e) {
        LOG.warn("Interrupted while waiting for replicationQueueInitializer. Returning..");
        return;
      } finally {
        replicationQueuesInitializer = null;
      }
    }
  }

  /*
   * Since the BlocksMapGset does not throw the ConcurrentModificationException
   * and supports further iteration after modification to list, there is a
   * chance of missing the newly added block while iterating. Since every
   * addition to blocksMap will check for mis-replication, missing mis-replication
   * check for new blocks will not be a problem.
   */
  private void processMisReplicatesAsync() throws InterruptedException {
    long nrInvalid = 0, nrOverReplicated = 0;
    long nrUnderReplicated = 0, nrPostponed = 0, nrUnderConstruction = 0;
    long startTimeMisReplicatedScan = Time.monotonicNow();
    Iterator<BlockInfo> blocksItr = blocksMap.getBlocks().iterator();
    long totalBlocks = blocksMap.size();
    replicationQueuesInitProgress = 0;
    long totalProcessed = 0;
    long sleepDuration =
        Math.max(1, Math.min(numBlocksPerIteration/1000, 10000));

    while (namesystem.isRunning() && !Thread.currentThread().isInterrupted()) {
      int processed = 0;
      namesystem.writeLockInterruptibly();
      try {
        while (processed < numBlocksPerIteration && blocksItr.hasNext()) {
          BlockInfo block = blocksItr.next();
          MisReplicationResult res = processMisReplicatedBlock(block);
          switch (res) {
          case UNDER_REPLICATED:
            LOG.trace("under replicated block {}: {}", block, res);
            nrUnderReplicated++;
            break;
          case OVER_REPLICATED:
            LOG.trace("over replicated block {}: {}", block, res);
            nrOverReplicated++;
            break;
          case INVALID:
            LOG.trace("invalid block {}: {}", block, res);
            nrInvalid++;
            break;
          case POSTPONE:
            LOG.trace("postpone block {}: {}", block, res);
            nrPostponed++;
            postponeBlock(block);
            break;
          case UNDER_CONSTRUCTION:
            LOG.trace("under construction block {}: {}", block, res);
            nrUnderConstruction++;
            break;
          case OK:
            break;
          default:
            throw new AssertionError("Invalid enum value: " + res);
          }
          processed++;
        }
        totalProcessed += processed;
        // there is a possibility that if any of the blocks deleted/added during
        // initialisation, then progress might be different.
        replicationQueuesInitProgress = Math.min((double) totalProcessed
            / totalBlocks, 1.0);

        if (!blocksItr.hasNext()) {
          LOG.info("Total number of blocks            = " + blocksMap.size());
          LOG.info("Number of invalid blocks          = " + nrInvalid);
          LOG.info("Number of under-replicated blocks = " + nrUnderReplicated);
          LOG.info("Number of  over-replicated blocks = " + nrOverReplicated
              + ((nrPostponed > 0) ? (" (" + nrPostponed + " postponed)") : ""));
          LOG.info("Number of blocks being written    = " + nrUnderConstruction);
          NameNode.stateChangeLog
              .info("STATE* Replication Queue initialization "
                  + "scan for invalid, over- and under-replicated blocks "
                  + "completed in " + (Time.monotonicNow() - startTimeMisReplicatedScan)
                  + " msec");
          break;
        }
      } finally {
        namesystem.writeUnlock();
        // Make sure it is out of the write lock for sufficiently long time.
        Thread.sleep(sleepDuration);
      }
    }
    if (Thread.currentThread().isInterrupted()) {
      LOG.info("Interrupted while processing replication queues.");
    }
  }

  /**
   * Get the progress of the Replication queues initialisation
   * 
   * @return Returns values between 0 and 1 for the progress.
   */
  public double getReplicationQueuesInitProgress() {
    return replicationQueuesInitProgress;
  }

  /**
   * Process a single possibly misreplicated block. This adds it to the
   * appropriate queues if necessary, and returns a result code indicating
   * what happened with it.
   */
  private MisReplicationResult processMisReplicatedBlock(BlockInfo block) {
    BlockCollection bc = block.getBlockCollection();
    if (block.isDeleted()) {
      // block does not belong to any file
      addToInvalidates(block);
      return MisReplicationResult.INVALID;
    }
    if (!block.isComplete()) {
      // Incomplete blocks are never considered mis-replicated --
      // they'll be reached when they are completed or recovered.
      return MisReplicationResult.UNDER_CONSTRUCTION;
    }
    // calculate current replication
    short expectedReplication =
        block.getBlockCollection().getBlockReplication();
    NumberReplicas num = countNodes(block);
    int numCurrentReplica = num.liveReplicas();
    // add to under-replicated queue if need to be
    if (isNeededReplication(block, num)) {
      if (neededReplications.add(block, numCurrentReplica,
          num.readOnlyReplicas(), num.outOfServiceReplicas(),
          expectedReplication)) {
        return MisReplicationResult.UNDER_REPLICATED;
      }
    }

    if (numCurrentReplica > expectedReplication) {
      if (num.replicasOnStaleNodes() > 0) {
        // If any of the replicas of this block are on nodes that are
        // considered "stale", then these replicas may in fact have
        // already been deleted. So, we cannot safely act on the
        // over-replication until a later point in time, when
        // the "stale" nodes have block reported.
        return MisReplicationResult.POSTPONE;
      }
      
      // over-replicated block
      processOverReplicatedBlock(block, expectedReplication, null, null);
      return MisReplicationResult.OVER_REPLICATED;
    }
    
    return MisReplicationResult.OK;
  }
  
  /** Set replication for the blocks. */
  public void setReplication(final short oldRepl, final short newRepl,
      final String src, final BlockInfo... blocks) {
    if (newRepl == oldRepl) {
      return;
    }

    // update needReplication priority queues
    for(BlockInfo b : blocks) {
      NumberReplicas num = countNodes(b);
      updateNeededReplications(b, 0, newRepl - oldRepl);
      if (num.liveReplicas() > newRepl) {
        processOverReplicatedBlock(b, newRepl, null, null);
      }
    }

    if (oldRepl > newRepl) {
      // old replication > the new one; need to remove copies
      LOG.info("Decreasing replication from " + oldRepl + " to " + newRepl
          + " for " + src);
      for(BlockInfo b : blocks) {
        processOverReplicatedBlock(b, newRepl, null, null);
      }
    } else { // replication factor is increased
      LOG.info("Increasing replication from " + oldRepl + " to " + newRepl
          + " for " + src);
    }
  }

  /**
   * Find how many of the containing nodes are "extra", if any.
   * If there are any extras, call chooseExcessReplicates() to
   * mark them in the excessReplicateMap.
   */
  private void processOverReplicatedBlock(final Block block,
      final short replication, final DatanodeDescriptor addedNode,
      DatanodeDescriptor delNodeHint) {
    assert namesystem.hasWriteLock();
    if (addedNode == delNodeHint) {
      delNodeHint = null;
    }
    Collection<DatanodeStorageInfo> nonExcess = new ArrayList<DatanodeStorageInfo>();
    Collection<DatanodeDescriptor> corruptNodes = corruptReplicas
        .getNodes(block);
    for(DatanodeStorageInfo storage : blocksMap.getStorages(block, State.NORMAL)) {
      final DatanodeDescriptor cur = storage.getDatanodeDescriptor();
      if (storage.areBlockContentsStale()) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("BLOCK* processOverReplicatedBlock: Postponing " + block
              + " since storage " + storage
              + " does not yet have up-to-date information.");
        }
        postponeBlock(block);
        return;
      }
      LightWeightLinkedSet<Block> excessBlocks = excessReplicateMap.get(cur
          .getDatanodeUuid());
      if (excessBlocks == null || !excessBlocks.contains(block)) {
        if (cur.isInService()) {
          // exclude corrupt replicas
          if (corruptNodes == null || !corruptNodes.contains(cur)) {
            nonExcess.add(storage);
          }
        }
      }
    }
    chooseExcessReplicates(nonExcess, block, replication, 
        addedNode, delNodeHint);
  }

  private void chooseExcessReplicates(
      final Collection<DatanodeStorageInfo> nonExcess,
      Block b, short replication,
      DatanodeDescriptor addedNode,
      DatanodeDescriptor delNodeHint) {
    assert namesystem.hasWriteLock();
    // first form a rack to datanodes map and
    BlockCollection bc = getBlockCollection(b);
    final BlockStoragePolicy storagePolicy = storagePolicySuite.getPolicy(
        bc.getStoragePolicyID());
    final List<StorageType> excessTypes = storagePolicy.chooseExcess(
        replication, DatanodeStorageInfo.toStorageTypes(nonExcess));
    List<DatanodeStorageInfo> replicasToDelete = blockplacement
        .chooseReplicasToDelete(nonExcess, replication, excessTypes,
            addedNode, delNodeHint);
    for (DatanodeStorageInfo choosenReplica : replicasToDelete) {
      processChosenExcessReplica(nonExcess, choosenReplica, b);
    }
  }

  private void processChosenExcessReplica(
      final Collection<DatanodeStorageInfo> nonExcess,
      final DatanodeStorageInfo chosen, Block b) {
    nonExcess.remove(chosen);
    addToExcessReplicate(chosen.getDatanodeDescriptor(), b);
    //
    // The 'excessblocks' tracks blocks until we get confirmation
    // that the datanode has deleted them; the only way we remove them
    // is when we get a "removeBlock" message.
    //
    // The 'invalidate' list is used to inform the datanode the block
    // should be deleted.  Items are removed from the invalidate list
    // upon giving instructions to the datanodes.
    //
    addToInvalidates(b, chosen.getDatanodeDescriptor());
    blockLog.debug("BLOCK* chooseExcessReplicates: "
        +"("+chosen+", "+b+") is added to invalidated blocks set");
  }

  private void addToExcessReplicate(DatanodeInfo dn, Block block) {
    assert namesystem.hasWriteLock();
    LightWeightLinkedSet<Block> excessBlocks = excessReplicateMap.get(dn.getDatanodeUuid());
    if (excessBlocks == null) {
      excessBlocks = new LightWeightLinkedSet<Block>();
      excessReplicateMap.put(dn.getDatanodeUuid(), excessBlocks);
    }
    if (excessBlocks.add(block)) {
      excessBlocksCount.incrementAndGet();
      blockLog.debug("BLOCK* addToExcessReplicate: ({}, {}) is added to"
          + " excessReplicateMap", dn, block);
    }
  }

  private void removeStoredBlock(DatanodeStorageInfo storageInfo, Block block,
      DatanodeDescriptor node) {
    if (shouldPostponeBlocksFromFuture &&
        namesystem.isGenStampInFuture(block)) {
      queueReportedBlock(storageInfo, block, null,
          QUEUE_REASON_FUTURE_GENSTAMP);
      return;
    }
    removeStoredBlock(block, node);
  }

  /**
   * Modify (block-->datanode) map. Possibly generate replication tasks, if the
   * removed block is still valid.
   */
  public void removeStoredBlock(Block block, DatanodeDescriptor node) {
    blockLog.debug("BLOCK* removeStoredBlock: {} from {}", block, node);
    assert (namesystem.hasWriteLock());
    {
      BlockInfo storedBlock = getStoredBlock(block);
      if (storedBlock == null || !blocksMap.removeNode(storedBlock, node)) {
        blockLog.debug("BLOCK* removeStoredBlock: {} has already been" +
            " removed from node {}", block, node);
        return;
      }

      CachedBlock cblock = namesystem.getCacheManager().getCachedBlocks()
          .get(new CachedBlock(block.getBlockId(), (short) 0, false));
      if (cblock != null) {
        boolean removed = false;
        removed |= node.getPendingCached().remove(cblock);
        removed |= node.getCached().remove(cblock);
        removed |= node.getPendingUncached().remove(cblock);
        if (removed) {
          blockLog.debug("BLOCK* removeStoredBlock: {} removed from caching "
              + "related lists on node {}", block, node);
        }
      }

      //
      // It's possible that the block was removed because of a datanode
      // failure. If the block is still valid, check if replication is
      // necessary. In that case, put block on a possibly-will-
      // be-replicated list.
      //
      BlockCollection bc = blocksMap.getBlockCollection(block);
      if (bc != null) {
        namesystem.decrementSafeBlockCount(storedBlock);
        updateNeededReplications(storedBlock, -1, 0);
      }

      //
      // We've removed a block from a node, so it's definitely no longer
      // in "excess" there.
      //
      LightWeightLinkedSet<Block> excessBlocks = excessReplicateMap.get(node
          .getDatanodeUuid());
      if (excessBlocks != null) {
        if (excessBlocks.remove(block)) {
          excessBlocksCount.decrementAndGet();
          blockLog.debug("BLOCK* removeStoredBlock: {} is removed from " +
              "excessBlocks", block);
          if (excessBlocks.size() == 0) {
            excessReplicateMap.remove(node.getDatanodeUuid());
          }
        }
      }

      // Remove the replica from corruptReplicas
      corruptReplicas.removeFromCorruptReplicasMap(block, node);
    }
  }

  private void removeStaleReplicas(List<ReplicaUnderConstruction> staleReplicas,
      BlockInfoUnderConstruction block) {
    if (staleReplicas == null) {
      return;
    }
    for (ReplicaUnderConstruction r : staleReplicas) {
      removeStoredBlock(block,
          r.getExpectedStorageLocation().getDatanodeDescriptor());
      NameNode.blockStateChangeLog
          .info("BLOCK* Removing stale replica " + "from location: {}",
              r.getExpectedStorageLocation());
    }
  }

  /**
   * Get all valid locations of the block & add the block to results
   * return the length of the added block; 0 if the block is not added
   */
  private long addBlock(Block block, List<BlockWithLocations> results) {
    final List<DatanodeStorageInfo> locations = getValidLocations(block);
    if(locations.size() == 0) {
      return 0;
    } else {
      final String[] datanodeUuids = new String[locations.size()];
      final String[] storageIDs = new String[datanodeUuids.length];
      final StorageType[] storageTypes = new StorageType[datanodeUuids.length];
      for(int i = 0; i < locations.size(); i++) {
        final DatanodeStorageInfo s = locations.get(i);
        datanodeUuids[i] = s.getDatanodeDescriptor().getDatanodeUuid();
        storageIDs[i] = s.getStorageID();
        storageTypes[i] = s.getStorageType();
      }
      results.add(new BlockWithLocations(block, datanodeUuids, storageIDs,
          storageTypes));
      return block.getNumBytes();
    }
  }

  /**
   * The given node is reporting that it received a certain block.
   */
  @VisibleForTesting
  void addBlock(DatanodeStorageInfo storageInfo, Block block, String delHint)
      throws IOException {
    DatanodeDescriptor node = storageInfo.getDatanodeDescriptor();
    // Decrement number of blocks scheduled to this datanode.
    // for a retry request (of DatanodeProtocol#blockReceivedAndDeleted with 
    // RECEIVED_BLOCK), we currently also decrease the approximate number. 
    node.decrementBlocksScheduled(storageInfo.getStorageType());

    // get the deletion hint node
    DatanodeDescriptor delHintNode = null;
    if (delHint != null && delHint.length() != 0) {
      delHintNode = datanodeManager.getDatanode(delHint);
      if (delHintNode == null) {
        blockLog.warn("BLOCK* blockReceived: {} is expected to be removed " +
            "from an unrecorded node {}", block, delHint);
      }
    }

    //
    // Modify the blocks->datanode map and node's map.
    //
    BlockInfo storedBlock = getStoredBlock(block);
    if (storedBlock != null &&
        block.getGenerationStamp() == storedBlock.getGenerationStamp()) {
      pendingReplications.decrement(storedBlock, node);
    }
    processAndHandleReportedBlock(storageInfo, block, ReplicaState.FINALIZED,
        delHintNode);
  }
  
  private void processAndHandleReportedBlock(
      DatanodeStorageInfo storageInfo, Block block,
      ReplicaState reportedState, DatanodeDescriptor delHintNode)
      throws IOException {

    final DatanodeDescriptor node = storageInfo.getDatanodeDescriptor();

    if(LOG.isDebugEnabled()) {
      LOG.debug("Reported block " + block
          + " on " + node + " size " + block.getNumBytes()
          + " replicaState = " + reportedState);
    }

    if (shouldPostponeBlocksFromFuture &&
        namesystem.isGenStampInFuture(block)) {
      queueReportedBlock(storageInfo, block, reportedState,
          QUEUE_REASON_FUTURE_GENSTAMP);
      return;
    }

    // find block by blockId
    BlockInfo storedBlock = getStoredBlock(block);
    if(storedBlock == null) {
      // If blocksMap does not contain reported block id,
      // the replica should be removed from the data-node.
      blockLog.debug("BLOCK* addBlock: block {} on node {} size {} does not " +
          "belong to any file", block, node, block.getNumBytes());
      addToInvalidates(new Block(block), node);
      return;
    }

    BlockUCState ucState = storedBlock.getBlockUCState();
    // Block is on the NN
    if(LOG.isDebugEnabled()) {
      LOG.debug("In memory blockUCState = " + ucState);
    }

    // Ignore replicas already scheduled to be removed from the DN
    if(invalidateBlocks.contains(node, block)) {
      return;
    }

    BlockToMarkCorrupt c = checkReplicaCorrupt(
        block, reportedState, storedBlock, ucState, node);
    if (c != null) {
      if (shouldPostponeBlocksFromFuture) {
        // If the block is an out-of-date generation stamp or state,
        // but we're the standby, we shouldn't treat it as corrupt,
        // but instead just queue it for later processing.
        // TODO: Pretty confident this should be s/storedBlock/block below,
        // since we should be postponing the info of the reported block, not
        // the stored block. See HDFS-6289 for more context.
        queueReportedBlock(storageInfo, storedBlock, reportedState,
            QUEUE_REASON_CORRUPT_STATE);
      } else {
        markBlockAsCorrupt(c, storageInfo, node);
      }
      return;
    }

    if (isBlockUnderConstruction(storedBlock, ucState, reportedState)) {
      addStoredBlockUnderConstruction(
          new StatefulBlockInfo((BlockInfoUnderConstruction) storedBlock,
              new Block(block), reportedState),
          storageInfo);
      return;
    }

    // Add replica if appropriate. If the replica was previously corrupt
    // but now okay, it might need to be updated.
    if (reportedState == ReplicaState.FINALIZED
        && (storedBlock.findStorageInfo(storageInfo) == -1 ||
            corruptReplicas.isReplicaCorrupt(storedBlock, node))) {
      addStoredBlock(storedBlock, storageInfo, delHintNode, true);
    }
  }

  /**
   * The given node is reporting incremental information about some blocks.
   * This includes blocks that are starting to be received, completed being
   * received, or deleted.
   * 
   * This method must be called with FSNamesystem lock held.
   */
  public void processIncrementalBlockReport(final DatanodeID nodeID,
      final StorageReceivedDeletedBlocks srdb) throws IOException {
    assert namesystem.hasWriteLock();
    final DatanodeDescriptor node = datanodeManager.getDatanode(nodeID);
    if (node == null || !node.isRegistered()) {
      blockLog.warn("BLOCK* processIncrementalBlockReport"
              + " is received from dead or unregistered node {}", nodeID);
      throw new IOException(
          "Got incremental block report from unregistered or dead node");
    }
    try {
      processIncrementalBlockReport(node, srdb);
    } catch (Exception ex) {
      node.setForceRegistration(true);
      throw ex;
    }
  }

  private void processIncrementalBlockReport(final DatanodeDescriptor node,
      final StorageReceivedDeletedBlocks srdb) throws IOException {
    DatanodeStorageInfo storageInfo =
        node.getStorageInfo(srdb.getStorage().getStorageID());
    if (storageInfo == null) {
      // The DataNode is reporting an unknown storage. Usually the NN learns
      // about new storages from heartbeats but during NN restart we may
      // receive a block report or incremental report before the heartbeat.
      // We must handle this for protocol compatibility. This issue was
      // uncovered by HDFS-6094.
      storageInfo = node.updateStorage(srdb.getStorage());
    }

    int received = 0;
    int deleted = 0;
    int receiving = 0;

    for (ReceivedDeletedBlockInfo rdbi : srdb.getBlocks()) {
      switch (rdbi.getStatus()) {
      case DELETED_BLOCK:
        removeStoredBlock(storageInfo, rdbi.getBlock(), node);
        deleted++;
        break;
      case RECEIVED_BLOCK:
        addBlock(storageInfo, rdbi.getBlock(), rdbi.getDelHints());
        received++;
        break;
      case RECEIVING_BLOCK:
        receiving++;
        processAndHandleReportedBlock(storageInfo, rdbi.getBlock(),
                                      ReplicaState.RBW, null);
        break;
      default:
        String msg = 
          "Unknown block status code reported by " + node +
          ": " + rdbi;
        blockLog.warn(msg);
        assert false : msg; // if assertions are enabled, throw.
        break;
      }
      blockLog.debug("BLOCK* block {}: {} is received from {}",
          rdbi.getStatus(), rdbi.getBlock(), node);
    }
    blockLog.debug("*BLOCK* NameNode.processIncrementalBlockReport: from "
            + "{} receiving: {}, received: {}, deleted: {}", node, receiving,
        received, deleted);
  }

  /**
   * Return the number of nodes hosting a given block, grouped
   * by the state of those replicas.
   */
  public NumberReplicas countNodes(Block b) {
    int decommissioned = 0;
    int decommissioning = 0;
    int live = 0;
    int readonly = 0;
    int corrupt = 0;
    int excess = 0;
    int stale = 0;
    int maintenanceNotForRead = 0;
    int maintenanceForRead = 0;
    Collection<DatanodeDescriptor> nodesCorrupt = corruptReplicas.getNodes(b);
    for(DatanodeStorageInfo storage : blocksMap.getStorages(b)) {
      if (storage.getState() == State.FAILED) {
        continue;
      } else if (storage.getState() == State.READ_ONLY_SHARED) {
        readonly++;
        continue;
      }
      final DatanodeDescriptor node = storage.getDatanodeDescriptor();
      if ((nodesCorrupt != null) && (nodesCorrupt.contains(node))) {
        corrupt++;
      } else if (node.isDecommissionInProgress()) {
        decommissioning++;
      } else if (node.isDecommissioned()) {
        decommissioned++;
      } else if (node.isMaintenance()) {
        if (node.isInMaintenance() || !node.isAlive()) {
          maintenanceNotForRead++;
        } else {
          maintenanceForRead++;
        }
      } else {
        LightWeightLinkedSet<Block> blocksExcess = excessReplicateMap.get(node
            .getDatanodeUuid());
        if (blocksExcess != null && blocksExcess.contains(b)) {
          excess++;
        } else {
          live++;
        }
      }
      if (storage.areBlockContentsStale()) {
        stale++;
      }
    }
    return new NumberReplicas(live, readonly, decommissioned, decommissioning,
        corrupt, excess, stale, maintenanceNotForRead, maintenanceForRead);
  }

  /** 
   * Simpler, faster form of {@link #countNodes} that only returns the number
   * of live nodes.  If in startup safemode (or its 30-sec extension period),
   * then it gains speed by ignoring issues of excess replicas or nodes
   * that are decommissioned or in process of becoming decommissioned.
   * If not in startup, then it calls {@link #countNodes} instead.
   *
   * @param b - the block being tested
   * @return count of live nodes for this block
   */
  int countLiveNodes(BlockInfo b) {
    if (!namesystem.isInStartupSafeMode()) {
      return countNodes(b).liveReplicas();
    }
    // else proceed with fast case
    int live = 0;
    Collection<DatanodeDescriptor> nodesCorrupt = corruptReplicas.getNodes(b);
    for(DatanodeStorageInfo storage : blocksMap.getStorages(b, State.NORMAL)) {
      final DatanodeDescriptor node = storage.getDatanodeDescriptor();
      if ((nodesCorrupt == null) || (!nodesCorrupt.contains(node)))
        live++;
    }
    return live;
  }

  /**
   * On putting the node in service, check if the node has excess replicas.
   * If there are any excess replicas, call processOverReplicatedBlock().
   * Process over replicated blocks only when active NN is out of safe mode.
   */
  void processExtraRedundancyBlocksOnInService(
      final DatanodeDescriptor srcNode) {
    if (!namesystem.isPopulatingReplQueues()) {
      return;
    }
    final Iterator<BlockInfo> it = srcNode.getBlockIterator();
    int numOverReplicated = 0;
    while(it.hasNext()) {
      final BlockInfo block = it.next();
      BlockCollection bc = blocksMap.getBlockCollection(block);
      short expectedReplication = bc.getBlockReplication();
      NumberReplicas num = countNodes(block);
      int numCurrentReplica = num.liveReplicas();
      if (numCurrentReplica > expectedReplication) {
        // over-replicated block 
        processOverReplicatedBlock(block, expectedReplication, null, null);
        numOverReplicated++;
      }
    }
    LOG.info("Invalidated " + numOverReplicated + " over-replicated blocks on " +
        srcNode + " during recommissioning");
  }

  /**
   * Returns whether a node can be safely decommissioned or in maintenance
   * based on its liveness. Dead nodes cannot always be safely decommissioned
   * or in maintenance.
   */
  boolean isNodeHealthyForDecommissionOrMaintenance(DatanodeDescriptor node) {
    if (!node.checkBlockReportReceived()) {
      LOG.info("Node {} hasn't sent its first block report.", node);
      return false;
    }

    if (node.isAlive()) {
      return true;
    }

    updateState();
    if (pendingReplicationBlocksCount == 0 &&
        underReplicatedBlocksCount == 0) {
      LOG.info("Node {} is dead and there are no under-replicated" +
          " blocks or blocks pending replication. Safe to decommission or" +
          " put in maintenance.", node);
      return true;
    }

    LOG.warn("Node {} is dead " +
        "while in {}. Cannot be safely " +
        "decommissioned or be in maintenance since there is risk of reduced " +
        "data durability or data loss. Either restart the failed node or " +
        "force decommissioning or maintenance by removing, calling " +
        "refreshNodes, then re-adding to the excludes or host config files.",
        node, node.getAdminState());
    return false;
  }

  public int getActiveBlockCount() {
    return blocksMap.size();
  }

  public DatanodeStorageInfo[] getStorages(BlockInfo block) {
    final DatanodeStorageInfo[] storages = new DatanodeStorageInfo[block.numNodes()];
    int i = 0;
    for(DatanodeStorageInfo s : blocksMap.getStorages(block)) {
      storages[i++] = s;
    }
    return storages;
  }

  public int getTotalBlocks() {
    return blocksMap.size();
  }

  public void removeBlock(BlockInfo block) {
    assert namesystem.hasWriteLock();
    // No need to ACK blocks that are being removed entirely
    // from the namespace, since the removal of the associated
    // file already removes them from the block map below.
    block.setNumBytes(BlockCommand.NO_ACK);
    addToInvalidates(block);
    removeBlockFromMap(block);
    // Remove the block from pendingReplications and neededReplications
    pendingReplications.remove(block);
    neededReplications.remove(block, UnderReplicatedBlocks.LEVEL);
    postponedMisreplicatedBlocks.remove(block);
  }

  public BlockInfo getStoredBlock(Block block) {
    return blocksMap.getStoredBlock(block);
  }

  public void updateLastBlock(BlockInfoUnderConstruction lastBlock,
      ExtendedBlock newBlock) {
    lastBlock.setNumBytes(newBlock.getNumBytes());
    List<ReplicaUnderConstruction> staleReplicas = lastBlock
        .setGenerationStampAndVerifyReplicas(newBlock.getGenerationStamp());
    removeStaleReplicas(staleReplicas, lastBlock);
  }

  /** updates a block in under replication queue */
  private void updateNeededReplications(final BlockInfo block,
      final int curReplicasDelta, int expectedReplicasDelta) {
    namesystem.writeLock();
    try {
      if (!namesystem.isPopulatingReplQueues() || !block.isComplete()) {
        return;
      }
      NumberReplicas repl = countNodes(block);
      int pendingNum = pendingReplications.getNumReplicas(block);
      int curExpectedReplicas = getReplication(block);
      if (!hasEnoughEffectiveReplicas(block, repl, pendingNum)) {
        neededReplications.update(block, repl.liveReplicas(), repl.readOnlyReplicas(),
            repl.outOfServiceReplicas(), curExpectedReplicas,
            curReplicasDelta, expectedReplicasDelta);
      } else {
        int oldReplicas = repl.liveReplicas()-curReplicasDelta;
        int oldExpectedReplicas = curExpectedReplicas-expectedReplicasDelta;
        neededReplications.remove(block, oldReplicas, repl.readOnlyReplicas(),
            repl.outOfServiceReplicas(), oldExpectedReplicas);
      }
    } finally {
      namesystem.writeUnlock();
    }
  }

  /**
   * Check replication of the blocks in the collection.
   * If any block is needed replication, insert it into the replication queue.
   * Otherwise, if the block is more than the expected replication factor,
   * process it as an over replicated block.
   */
  public void checkReplication(BlockCollection bc) {
    for (BlockInfo block : bc.getBlocks()) {
      final short expected = bc.getBlockReplication();
      final NumberReplicas n = countNodes(block);
      final int pending = pendingReplications.getNumReplicas(block);
      if (!hasEnoughEffectiveReplicas(block, n, pending)) {
        neededReplications.add(block, n.liveReplicas() + pending,
            n.readOnlyReplicas(),
            n.outOfServiceReplicas(), expected);
      } else if (n.liveReplicas() > expected) {
        processOverReplicatedBlock(block, expected, null, null);
      }
    }
  }

  /**
   * @return 0 if the block is not found;
   *         otherwise, return the replication factor of the block.
   */
  private int getReplication(Block block) {
    final BlockCollection bc = blocksMap.getBlockCollection(block);
    return bc == null? 0: bc.getBlockReplication();
  }

  /**
   * Get blocks to invalidate for <i>nodeId</i>
   * in {@link #invalidateBlocks}.
   *
   * @return number of blocks scheduled for removal during this iteration.
   */
  private int invalidateWorkForOneNode(DatanodeInfo dn) {
    final List<Block> toInvalidate;
    
    namesystem.writeLock();
    try {
      // blocks should not be replicated or removed if safe mode is on
      if (namesystem.isInSafeMode()) {
        LOG.debug("In safemode, not computing replication work");
        return 0;
      }
      try {
        DatanodeDescriptor dnDescriptor = datanodeManager.getDatanode(dn);
        if (dnDescriptor == null) {
          LOG.warn("DataNode " + dn + " cannot be found with UUID " +
              dn.getDatanodeUuid() + ", removing block invalidation work.");
          invalidateBlocks.remove(dn);
          return 0;
        }
        toInvalidate = invalidateBlocks.invalidateWork(dnDescriptor);
        
        if (toInvalidate == null) {
          return 0;
        }
      } catch(UnregisteredNodeException une) {
        return 0;
      }
    } finally {
      namesystem.writeUnlock();
    }
    if (blockLog.isInfoEnabled()) {
      blockLog.info("BLOCK* " + getClass().getSimpleName()
          + ": ask " + dn + " to delete " + toInvalidate);
    }
    return toInvalidate.size();
  }

  boolean isPlacementPolicySatisfied(Block b) {
    List<DatanodeDescriptor> liveNodes = new ArrayList<DatanodeDescriptor>();
    Collection<DatanodeDescriptor> corruptNodes = corruptReplicas
        .getNodes(b);
    for (DatanodeStorageInfo storage : blocksMap.getStorages(b)) {
      final DatanodeDescriptor cur = storage.getDatanodeDescriptor();
      // Nodes under maintenance should be counted as valid replicas from
      // rack policy point of view.
      if (!cur.isDecommissionInProgress() && !cur.isDecommissioned()
          && ((corruptNodes == null) || !corruptNodes.contains(cur))) {
        liveNodes.add(cur);
      }
    }
    DatanodeInfo[] locs = liveNodes.toArray(new DatanodeInfo[liveNodes.size()]);
    return blockplacement.verifyBlockPlacement(locs,
        getReplication(b)).isPlacementPolicySatisfied();
  }

  boolean isNeededReplicationForMaintenance(BlockInfo storedBlock,
      NumberReplicas numberReplicas) {
    return storedBlock.isComplete() && (numberReplicas.liveReplicas() <
        getMinMaintenanceStorageNum(storedBlock) ||
        !isPlacementPolicySatisfied(storedBlock));
  }

  boolean isNeededReplication(BlockInfo storedBlock,
      NumberReplicas numberReplicas) {
    return isNeededReplication(storedBlock, numberReplicas, 0);
  }

  /**
   * A block needs reconstruction if the number of redundancies is less than
   * expected or if it does not have enough racks.
   */
 boolean isNeededReplication(BlockInfo storedBlock,
      NumberReplicas numberReplicas, int pending) {
    return storedBlock.isComplete() &&
        !hasEnoughEffectiveReplicas(storedBlock, numberReplicas, pending);
  }

  // Exclude maintenance, but make sure it has minimal live replicas
  // to satisfy the maintenance requirement.
  public short getExpectedLiveRedundancyNum(BlockInfo block,
      NumberReplicas numberReplicas) {
    final short expectedRedundancy = (short) getReplication(block);
    return (short)Math.max(expectedRedundancy -
        numberReplicas.maintenanceReplicas(),
        getMinMaintenanceStorageNum(block));
  }

  public long getMissingBlocksCount() {
    // not locking
    return this.neededReplications.getCorruptBlockSize();
  }

  public long getMissingReplOneBlocksCount() {
    // not locking
    return this.neededReplications.getCorruptReplOneBlockSize();
  }

  public BlockInfo addBlockCollection(BlockInfo block, BlockCollection bc) {
    return blocksMap.addBlockCollection(block, bc);
  }

  public BlockCollection getBlockCollection(Block b) {
    return blocksMap.getBlockCollection(b);
  }

  /** @return an iterator of the datanodes. */
  public Iterable<DatanodeStorageInfo> getStorages(final Block block) {
    return blocksMap.getStorages(block);
  }

  public int numCorruptReplicas(Block block) {
    return corruptReplicas.numCorruptReplicas(block);
  }

  public void removeBlockFromMap(Block block) {
    removeFromExcessReplicateMap(block);
    blocksMap.removeBlock(block);
    // If block is removed from blocksMap remove it from corruptReplicasMap
    corruptReplicas.removeFromCorruptReplicasMap(block);
  }

  /**
   * If a block is removed from blocksMap, remove it from excessReplicateMap.
   */
  private void removeFromExcessReplicateMap(Block block) {
    for (DatanodeStorageInfo info : blocksMap.getStorages(block)) {
      String uuid = info.getDatanodeDescriptor().getDatanodeUuid();
      LightWeightLinkedSet<Block> excessReplicas = excessReplicateMap.get(uuid);
      if (excessReplicas != null) {
        if (excessReplicas.remove(block)) {
          excessBlocksCount.decrementAndGet();
          if (excessReplicas.isEmpty()) {
            excessReplicateMap.remove(uuid);
          }
        }
      }
    }
  }

  public int getCapacity() {
    return blocksMap.getCapacity();
  }

  /**
   * Return a range of corrupt replica block ids. Up to numExpectedBlocks
   * blocks starting at the next block after startingBlockId are returned
   * (fewer if numExpectedBlocks blocks are unavailable). If startingBlockId
   * is null, up to numExpectedBlocks blocks are returned from the beginning.
   * If startingBlockId cannot be found, null is returned.
   *
   * @param numExpectedBlocks Number of block ids to return.
   *  0 <= numExpectedBlocks <= 100
   * @param startingBlockId Block id from which to start. If null, start at
   *  beginning.
   * @return Up to numExpectedBlocks blocks from startingBlockId if it exists
   *
   */
  public long[] getCorruptReplicaBlockIds(int numExpectedBlocks,
                                   Long startingBlockId) {
    return corruptReplicas.getCorruptReplicaBlockIds(numExpectedBlocks,
                                                     startingBlockId);
  }

  /**
   * Return an iterator over the set of blocks for which there are no replicas.
   */
  public Iterator<BlockInfo> getCorruptReplicaBlockIterator() {
    return neededReplications.iterator(
        UnderReplicatedBlocks.QUEUE_WITH_CORRUPT_BLOCKS);
  }

  /**
   * Get the replicas which are corrupt for a given block.
   */
  public Collection<DatanodeDescriptor> getCorruptReplicas(Block block) {
    return corruptReplicas.getNodes(block);
  }

 /**
  * Get reason for certain corrupted replicas for a given block and a given dn.
  */
 public String getCorruptReason(Block block, DatanodeDescriptor node) {
   return corruptReplicas.getCorruptReason(block, node);
 }

  /** @return the size of UnderReplicatedBlocks */
  public int numOfUnderReplicatedBlocks() {
    return neededReplications.size();
  }

  /**
   * Periodically calls computeReplicationWork().
   */
  private class ReplicationMonitor implements Runnable {

    @Override
    public void run() {
      while (namesystem.isRunning()) {
        try {
          // Process replication work only when active NN is out of safe mode.
          if (namesystem.isPopulatingReplQueues()) {
            computeDatanodeWork();
            processPendingReplications();
            rescanPostponedMisreplicatedBlocks();
          }
          Thread.sleep(replicationRecheckInterval);
        } catch (Throwable t) {
          if (!namesystem.isRunning()) {
            LOG.info("Stopping ReplicationMonitor.");
            if (!(t instanceof InterruptedException)) {
              LOG.info("ReplicationMonitor received an exception"
                  + " while shutting down.", t);
            }
            break;
          } else if (!checkNSRunning && t instanceof InterruptedException) {
            LOG.info("Stopping ReplicationMonitor for testing.");
            break;
          }
          LOG.error("ReplicationMonitor thread received Runtime exception. ",
              t);
          terminate(1, t);
        }
      }
    }
  }

  /**
   * Runnable that monitors the fragmentation of the StorageInfo TreeSet and
   * compacts it when it falls under a certain threshold.
   */
  private class StorageInfoDefragmenter implements Runnable {

    @Override
    public void run() {
      while (namesystem.isRunning()) {
        try {
          // Check storage efficiency only when active NN is out of safe mode.
          if (namesystem.isPopulatingReplQueues()) {
            scanAndCompactStorages();
          }
          Thread.sleep(storageInfoDefragmentInterval);
        } catch (Throwable t) {
          if (!namesystem.isRunning()) {
            LOG.info("Stopping thread.");
            if (!(t instanceof InterruptedException)) {
              LOG.info("Received an exception while shutting down.", t);
            }
            break;
          } else if (!checkNSRunning && t instanceof InterruptedException) {
            LOG.info("Stopping for testing.");
            break;
          }
          LOG.error("Thread received Runtime exception.", t);
          terminate(1, t);
        }
      }
    }

    private void scanAndCompactStorages() throws InterruptedException {
      ArrayList<String> datanodesAndStorages = new ArrayList<>();
      for (DatanodeDescriptor node
          : datanodeManager.getDatanodeListForReport(HdfsConstants.DatanodeReportType.ALL)) {
        for (DatanodeStorageInfo storage : node.getStorageInfos()) {
          try {
            namesystem.readLock();
            double ratio = storage.treeSetFillRatio();
            if (ratio < storageInfoDefragmentRatio) {
              datanodesAndStorages.add(node.getDatanodeUuid());
              datanodesAndStorages.add(storage.getStorageID());
            }
            LOG.info("StorageInfo TreeSet fill ratio {} : {}{}",
                     storage.getStorageID(), ratio,
                     (ratio < storageInfoDefragmentRatio)
                     ? " (queued for defragmentation)" : "");
          } finally {
            namesystem.readUnlock();
          }
        }
      }
      if (!datanodesAndStorages.isEmpty()) {
        for (int i = 0; i < datanodesAndStorages.size(); i += 2) {
          namesystem.writeLock();
          try {
            final DatanodeDescriptor dn = datanodeManager.
                getDatanode(datanodesAndStorages.get(i));
            if (dn == null) {
              continue;
            }
            final DatanodeStorageInfo storage = dn.
                getStorageInfo(datanodesAndStorages.get(i + 1));
            if (storage != null) {
              boolean aborted =
                  !storage.treeSetCompact(storageInfoDefragmentTimeout);
              if (aborted) {
                // Compaction timed out, reset iterator to continue with
                // the same storage next iteration.
                i -= 2;
              }
              LOG.info("StorageInfo TreeSet defragmented {} : {}{}",
                       storage.getStorageID(), storage.treeSetFillRatio(),
                       aborted ? " (aborted)" : "");
            }
          } finally {
            namesystem.writeUnlock();
          }
          // Wait between each iteration
          Thread.sleep(1000);
        }
      }
    }
  }

  /**
   * Compute block replication and block invalidation work that can be scheduled
   * on data-nodes. The datanode will be informed of this work at the next
   * heartbeat.
   * 
   * @return number of blocks scheduled for replication or removal.
   */
  int computeDatanodeWork() {
    // Blocks should not be replicated or removed if in safe mode.
    // It's OK to check safe mode here w/o holding lock, in the worst
    // case extra replications will be scheduled, and these will get
    // fixed up later.
    if (namesystem.isInSafeMode()) {
      return 0;
    }

    final int numlive = heartbeatManager.getLiveDatanodeCount();
    final int blocksToProcess = numlive
        * this.blocksReplWorkMultiplier;
    final int nodesToProcess = (int) Math.ceil(numlive
        * this.blocksInvalidateWorkPct);

    int workFound = this.computeReplicationWork(blocksToProcess);

    // Update counters
    namesystem.writeLock();
    try {
      this.updateState();
      this.scheduledReplicationBlocksCount = workFound;
    } finally {
      namesystem.writeUnlock();
    }
    workFound += this.computeInvalidateWork(nodesToProcess);
    return workFound;
  }

  /**
   * Clear all queues that hold decisions previously made by
   * this NameNode.
   */
  public void clearQueues() {
    neededReplications.clear();
    pendingReplications.clear();
    excessReplicateMap.clear();
    invalidateBlocks.clear();
    datanodeManager.clearPendingQueues();
    postponedMisreplicatedBlocks.clear();
  };

  private static class ReplicationWork {
    private final BlockInfo block;
    private final String srcPath;
    private final long blockSize;
    private final byte storagePolicyID;
    private final DatanodeDescriptor srcNode;
    private final int additionalReplRequired;
    private final int priority;
    private final List<DatanodeDescriptor> containingNodes;
    private final List<DatanodeStorageInfo> liveReplicaStorages;
    private DatanodeStorageInfo targets[];

    public ReplicationWork(BlockInfo block,
        BlockCollection bc,
        DatanodeDescriptor srcNode,
        List<DatanodeDescriptor> containingNodes,
        List<DatanodeStorageInfo> liveReplicaStorages,
        int additionalReplRequired,
        int priority) {
      this.block = block;
      this.srcPath = bc.getName();
      this.blockSize = block.getNumBytes();
      this.storagePolicyID = bc.getStoragePolicyID();
      this.srcNode = srcNode;
      this.srcNode.incrementPendingReplicationWithoutTargets();
      this.containingNodes = containingNodes;
      this.liveReplicaStorages = liveReplicaStorages;
      this.additionalReplRequired = additionalReplRequired;
      this.priority = priority;
      this.targets = null;
    }

    private void chooseTargets(BlockPlacementPolicy blockplacement,
        BlockStoragePolicySuite storagePolicySuite,
        Set<Node> excludedNodes) {
      try {
        targets = blockplacement.chooseTarget(getSrcPath(),
            additionalReplRequired, srcNode, liveReplicaStorages, false,
            excludedNodes, blockSize,
            storagePolicySuite.getPolicy(getStoragePolicyID()), null);
      } finally {
        srcNode.decrementPendingReplicationWithoutTargets();
      }
    }

    private String getSrcPath() {
      return srcPath;
    }

    private byte getStoragePolicyID() {
      return storagePolicyID;
    }
  }

  /**
   * A simple result enum for the result of
   * {@link BlockManager#processMisReplicatedBlock(BlockInfo)}.
   */
  enum MisReplicationResult {
    /** The block should be invalidated since it belongs to a deleted file. */
    INVALID,
    /** The block is currently under-replicated. */
    UNDER_REPLICATED,
    /** The block is currently over-replicated. */
    OVER_REPLICATED,
    /** A decision can't currently be made about this block. */
    POSTPONE,
    /** The block is under construction, so should be ignored */
    UNDER_CONSTRUCTION,
    /** The block is properly replicated */
    OK
  }

  public void shutdown() {
    stopReplicationInitializer();
    blocksMap.close();
    MBeans.unregister(mxBeanName);
    mxBeanName = null;
  }
  
  public void clear() {
    clearQueues();
    blocksMap.clear();
  }

  public BlockReportLeaseManager getBlockReportLeaseManager() {
    return blockReportLeaseManager;
  }

  @Override // BlockStatsMXBean
  public Map<StorageType, StorageTypeStats> getStorageTypeStats() {
    return  datanodeManager.getDatanodeStatistics().getStorageTypeStats();
  }

  // async processing of an action, used for IBRs.
  public void enqueueBlockOp(final Runnable action) throws IOException {
    try {
      blockReportThread.enqueue(action);
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    }
  }

  // sync batch processing for a full BR.
  public <T> T runBlockOp(final Callable<T> action)
      throws IOException {
    final FutureTask<T> future = new FutureTask<T>(action);
    enqueueBlockOp(future);
    try {
      return future.get();
    } catch (ExecutionException ee) {
      Throwable cause = ee.getCause();
      if (cause == null) {
        cause = ee;
      }
      if (!(cause instanceof IOException)) {
        cause = new IOException(cause);
      }
      throw (IOException)cause;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IOException(ie);
    }
  }

  /**
   * Notification of a successful block recovery.
   * @param block for which the recovery succeeded
   */
  public void successfulBlockRecovery(BlockInfo block) {
    pendingRecoveryBlocks.remove(block);
  }

  /**
   * Checks whether a recovery attempt has been made for the given block.
   * If so, checks whether that attempt has timed out.
   * @param b block for which recovery is being attempted
   * @return true if no recovery attempt has been made or
   *         the previous attempt timed out
   */
  public boolean addBlockRecoveryAttempt(BlockInfo b) {
    return pendingRecoveryBlocks.add(b);
  }

  @VisibleForTesting
  public void flushBlockOps() throws IOException {
    runBlockOp(new Callable<Void>(){
      @Override
      public Void call() {
        return null;
      }
    });
  }

  public int getBlockOpQueueLength() {
    return blockReportThread.queue.size();
  }

  private class BlockReportProcessingThread extends Thread {
    private static final long MAX_LOCK_HOLD_MS = 4;
    private long lastFull = 0;

    private final BlockingQueue<Runnable> queue =
        new ArrayBlockingQueue<Runnable>(1024);

    BlockReportProcessingThread() {
      super("Block report processor");
      setDaemon(true);
    }

    @Override
    public void run() {
      try {
        processQueue();
      } catch (Throwable t) {
        ExitUtil.terminate(1,
            getName() + " encountered fatal exception: " + t);
      }
    }

    private void processQueue() {
      while (namesystem.isRunning()) {
        NameNodeMetrics metrics = NameNode.getNameNodeMetrics();
        try {
          Runnable action = queue.take();
          // batch as many operations in the write lock until the queue
          // runs dry, or the max lock hold is reached.
          int processed = 0;
          namesystem.writeLock();
          metrics.setBlockOpsQueued(queue.size() + 1);
          try {
            long start = Time.monotonicNow();
            do {
              processed++;
              action.run();
              if (Time.monotonicNow() - start > MAX_LOCK_HOLD_MS) {
                break;
              }
              action = queue.poll();
            } while (action != null);
          } finally {
            namesystem.writeUnlock();
            metrics.addBlockOpsBatched(processed - 1);
          }
        } catch (InterruptedException e) {
          // ignore unless thread was specifically interrupted.
          if (Thread.interrupted()) {
            break;
          }
        }
      }
      queue.clear();
    }

    void enqueue(Runnable action) throws InterruptedException {
      if (!queue.offer(action)) {
        if (!isAlive() && namesystem.isRunning()) {
          ExitUtil.terminate(1, getName()+" is not running");
        }
        long now = Time.monotonicNow();
        if (now - lastFull > 4000) {
          lastFull = now;
          LOG.info("Block report queue is full");
        }
        queue.put(action);
      }
    }
  }

  boolean isReplicaCorrupt(BlockInfo blk, DatanodeDescriptor d) {
    return corruptReplicas.isReplicaCorrupt(blk, d);
  }

  private static long getBlockRecoveryTimeout(long heartbeatIntervalSecs) {
    return TimeUnit.SECONDS.toMillis(heartbeatIntervalSecs *
        BLOCK_RECOVERY_TIMEOUT_MULTIPLIER);
  }

  @VisibleForTesting
  public void setBlockRecoveryTimeout(long blockRecoveryTimeout) {
    pendingRecoveryBlocks.setRecoveryTimeoutInterval(blockRecoveryTimeout);
  }
}
