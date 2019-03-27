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
package org.apache.hadoop.hdfs;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;

import org.junit.Test;

public class TestDFSInputStream {

  @Test(timeout = 60000)
  public void testOpenInfo() throws IOException {
    Configuration conf = new Configuration();
    conf.setInt(DFSConfigKeys.DFS_CLIENT_RETRY_TIMES_GET_LAST_BLOCK_LENGTH, 0);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    try {
      DistributedFileSystem fs = cluster.getFileSystem();

      int chunkSize = 512;
      Random r = new Random(12345L);
      byte[] data = new byte[chunkSize];
      r.nextBytes(data);

      Path file = new Path("/testfile");
      try (FSDataOutputStream fout = fs.create(file)) {
        fout.write(data);
      }

      DFSClient.Conf dcconf = new DFSClient.Conf(conf);
      int retryTimesForGetLastBlockLength =
          dcconf.retryTimesForGetLastBlockLength;
      assertEquals(0, retryTimesForGetLastBlockLength);

      try (DFSInputStream fin = fs.dfs.open("/testfile")) {
        long flen = fin.getFileLength();
        assertEquals(chunkSize, flen);

        long lastBlockBeingWrittenLength =
            fin.getlastBlockBeingWrittenLengthForTesting();
        assertEquals(0, lastBlockBeingWrittenLength);
      }
    } finally {
      cluster.shutdown();
    }
  }
}
