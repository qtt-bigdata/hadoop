package org.apache.hadoop.hdfs.server.blockmanagement;


import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.StorageType;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Datanode statistics.
 * For decommissioning/decommissioned nodes, only used capacity is counted.
 */
class DatanodeStats {

  private final StorageTypeStatsMap statsMap = new StorageTypeStatsMap();
  private long capacityTotal = 0L;
  private long capacityUsed = 0L;
  private long capacityUsedNonDfs = 0L;
  private long capacityRemaining = 0L;
  private long blockPoolUsed = 0L;
  private int xceiverCount = 0;
  private long cacheCapacity = 0L;
  private long cacheUsed = 0L;

  private int nodesInService = 0;
  private int nodesInServiceXceiverCount = 0;
  private int expiredHeartbeats = 0;

  synchronized void add(final DatanodeDescriptor node) {
    xceiverCount += node.getXceiverCount();
    if (node.isInService()) {
      capacityUsed += node.getDfsUsed();
      capacityUsedNonDfs += node.getNonDfsUsed();
      blockPoolUsed += node.getBlockPoolUsed();
      nodesInService++;
      nodesInServiceXceiverCount += node.getXceiverCount();
      capacityTotal += node.getCapacity();
      capacityRemaining += node.getRemaining();
      cacheCapacity += node.getCacheCapacity();
      cacheUsed += node.getCacheUsed();
    } else if (node.isDecommissionInProgress() ||
        node.isEnteringMaintenance()) {
      cacheCapacity += node.getCacheCapacity();
      cacheUsed += node.getCacheUsed();
    }
    Set<StorageType> storageTypes = new HashSet<>();
    for (DatanodeStorageInfo storageInfo : node.getStorageInfos()) {
      statsMap.addStorage(storageInfo, node);
      storageTypes.add(storageInfo.getStorageType());
    }
    for (StorageType storageType : storageTypes) {
      statsMap.addNode(storageType, node);
    }
  }

  synchronized void subtract(final DatanodeDescriptor node) {
    xceiverCount -= node.getXceiverCount();
    if (node.isInService()) {
      capacityUsed -= node.getDfsUsed();
      capacityUsedNonDfs -= node.getNonDfsUsed();
      blockPoolUsed -= node.getBlockPoolUsed();
      nodesInService--;
      nodesInServiceXceiverCount -= node.getXceiverCount();
      capacityTotal -= node.getCapacity();
      capacityRemaining -= node.getRemaining();
      cacheCapacity -= node.getCacheCapacity();
      cacheUsed -= node.getCacheUsed();
    } else if (node.isDecommissionInProgress() ||
        node.isEnteringMaintenance()) {
      cacheCapacity -= node.getCacheCapacity();
      cacheUsed -= node.getCacheUsed();
    }
    Set<StorageType> storageTypes = new HashSet<>();
    for (DatanodeStorageInfo storageInfo : node.getStorageInfos()) {
      statsMap.subtractStorage(storageInfo, node);
      storageTypes.add(storageInfo.getStorageType());
    }
    for (StorageType storageType : storageTypes) {
      statsMap.subtractNode(storageType, node);
    }
  }

  /** Increment expired heartbeat counter. */
  void incrExpiredHeartbeats() {
    expiredHeartbeats++;
  }

  synchronized Map<StorageType, StorageTypeStats> getStatsMap() {
    return statsMap.get();
  }

  synchronized long getCapacityTotal() {
    return capacityTotal;
  }

  synchronized long getCapacityUsed() {
    return capacityUsed;
  }

  synchronized long getCapacityRemaining() {
    return capacityRemaining;
  }

  synchronized long getBlockPoolUsed() {
    return blockPoolUsed;
  }

  synchronized int getXceiverCount() {
    return xceiverCount;
  }

  synchronized long getCacheCapacity() {
    return cacheCapacity;
  }

  synchronized long getCacheUsed() {
    return cacheUsed;
  }

  synchronized int getNodesInService() {
    return nodesInService;
  }

  synchronized int getNodesInServiceXceiverCount() {
    return nodesInServiceXceiverCount;
  }

  synchronized int getExpiredHeartbeats() {
    return expiredHeartbeats;
  }

  synchronized float getCapacityRemainingPercent() {
    return DFSUtil.getPercentRemaining(capacityRemaining, capacityTotal);
  }

  synchronized float getPercentBlockPoolUsed() {
    return DFSUtil.getPercentUsed(blockPoolUsed, capacityTotal);
  }

  synchronized long getCapacityUsedNonDFS() {
    return capacityUsedNonDfs;
  }

  synchronized float getCapacityUsedPercent() {
    return DFSUtil.getPercentUsed(capacityUsed, capacityTotal);
  }

  static final class StorageTypeStatsMap {

    private Map<StorageType, StorageTypeStats> storageTypeStatsMap =
        new EnumMap<>(StorageType.class);

    private Map<StorageType, StorageTypeStats> get() {
      return new EnumMap<>(storageTypeStatsMap);
    }

    private void addNode(StorageType storageType,
        final DatanodeDescriptor node) {
      StorageTypeStats storageTypeStats =
          storageTypeStatsMap.get(storageType);
      if (storageTypeStats == null) {
        storageTypeStats = new StorageTypeStats();
        storageTypeStatsMap.put(storageType, storageTypeStats);
      }
      storageTypeStats.addNode(node);
    }

    private void addStorage(final DatanodeStorageInfo info,
        final DatanodeDescriptor node) {
      StorageTypeStats storageTypeStats =
          storageTypeStatsMap.get(info.getStorageType());
      if (storageTypeStats == null) {
        storageTypeStats = new StorageTypeStats();
        storageTypeStatsMap.put(info.getStorageType(), storageTypeStats);
      }
      storageTypeStats.addStorage(info, node);
    }

    private void subtractStorage(final DatanodeStorageInfo info,
        final DatanodeDescriptor node) {
      StorageTypeStats storageTypeStats =
          storageTypeStatsMap.get(info.getStorageType());
      if (storageTypeStats != null) {
        storageTypeStats.subtractStorage(info, node);
      }
    }

    private void subtractNode(StorageType storageType,
        final DatanodeDescriptor node) {
      StorageTypeStats storageTypeStats =
          storageTypeStatsMap.get(storageType);
      if (storageTypeStats != null) {
        storageTypeStats.subtractNode(node);
      }
    }
  }
}
