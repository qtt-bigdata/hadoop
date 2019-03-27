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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.key.JavaKeyStoreProvider;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.protocol.OpenFileEntry;
import org.apache.hadoop.hdfs.protocol.OpenFilesIterator;
import org.apache.hadoop.hdfs.protocol.OpenFilesIterator.OpenFilesType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestHdfsAdmin {
  
  private static final Path TEST_PATH = new Path("/test");
  private static final short REPL = 1;
  private static final int SIZE = 128;
  private static final int OPEN_FILES_BATCH_SIZE = 5;
  private final Configuration conf = new Configuration();
  private MiniDFSCluster cluster;
  
  @Before
  public void setUpCluster() throws IOException {
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_LIST_OPENFILES_NUM_RESPONSES,
        OPEN_FILES_BATCH_SIZE);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(2).build();
    cluster.waitActive();
  }
  
  @After
  public void shutDownCluster() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  /**
   * Test that we can set and clear quotas via {@link HdfsAdmin}.
   */
  @Test
  public void testHdfsAdminSetQuota() throws Exception {
    HdfsAdmin dfsAdmin = new HdfsAdmin(
        FileSystem.getDefaultUri(conf), conf);
    FileSystem fs = null;
    try {
      fs = FileSystem.get(conf);
      assertTrue(fs.mkdirs(TEST_PATH));
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getQuota());
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getSpaceQuota());
      
      dfsAdmin.setSpaceQuota(TEST_PATH, 10);
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getQuota());
      assertEquals(10, fs.getContentSummary(TEST_PATH).getSpaceQuota());
      
      dfsAdmin.setQuota(TEST_PATH, 10);
      assertEquals(10, fs.getContentSummary(TEST_PATH).getQuota());
      assertEquals(10, fs.getContentSummary(TEST_PATH).getSpaceQuota());
      
      dfsAdmin.clearSpaceQuota(TEST_PATH);
      assertEquals(10, fs.getContentSummary(TEST_PATH).getQuota());
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getSpaceQuota());
      
      dfsAdmin.clearQuota(TEST_PATH);
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getQuota());
      assertEquals(-1, fs.getContentSummary(TEST_PATH).getSpaceQuota());
    } finally {
      if (fs != null) {
        fs.close();
      }
    }
  }
  
  /**
   * Make sure that a non-HDFS URI throws a helpful error.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testHdfsAdminWithBadUri() throws IOException, URISyntaxException {
    new HdfsAdmin(new URI("file:///bad-scheme"), conf);
  }

  private static String getKeyProviderURI() {
    FileSystemTestHelper helper = new FileSystemTestHelper();
    // Set up java key store
    String testRoot = helper.getTestRootDir();
    File testRootDir = new File(testRoot).getAbsoluteFile();
    return JavaKeyStoreProvider.SCHEME_NAME + "://file" +
        new Path(testRootDir.toString(), "test.jks").toUri();
  }

  @Test
  public void testGetKeyProvider() throws IOException {
    HdfsAdmin hdfsAdmin = new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
    Assert.assertNull("should return null for an non-encrypted cluster",
        hdfsAdmin.getKeyProvider());

    shutDownCluster();

    Configuration conf = new Configuration();
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_KEY_PROVIDER_PATH,
        getKeyProviderURI());

    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(2).build();
    cluster.waitActive();
    hdfsAdmin = new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);

    Assert.assertNotNull("should not return null for an encrypted cluster",
        hdfsAdmin.getKeyProvider());
  }

  @Test(timeout = 120000L)
  public void testListOpenFiles() throws IOException {
    HashSet<Path> closedFileSet = new HashSet<>();
    HashMap<Path, FSDataOutputStream> openFileMap = new HashMap<>();
    FileSystem fs = FileSystem.get(conf);
    verifyOpenFiles(closedFileSet, openFileMap);

    int numClosedFiles = OPEN_FILES_BATCH_SIZE * 4;
    int numOpenFiles = (OPEN_FILES_BATCH_SIZE * 3) + 1;
    for (int i = 0; i < numClosedFiles; i++) {
      Path filePath = new Path("/closed-file-" + i);
      DFSTestUtil.createFile(fs, filePath, SIZE, REPL, 0);
      closedFileSet.add(filePath);
    }
    verifyOpenFiles(closedFileSet, openFileMap);

    openFileMap.putAll(
        DFSTestUtil.createOpenFiles(fs, "open-file-1", numOpenFiles));
    verifyOpenFiles(closedFileSet, openFileMap);

    closedFileSet.addAll(DFSTestUtil.closeOpenFiles(openFileMap,
        openFileMap.size() / 2));
    verifyOpenFiles(closedFileSet, openFileMap);

    openFileMap.putAll(
        DFSTestUtil.createOpenFiles(fs, "open-file-2", 10));
    verifyOpenFiles(closedFileSet, openFileMap);

    while(openFileMap.size() > 0) {
      closedFileSet.addAll(DFSTestUtil.closeOpenFiles(openFileMap, 1));
      verifyOpenFiles(closedFileSet, openFileMap);
    }
  }

  private void verifyOpenFiles(HashSet<Path> closedFiles,
      HashMap<Path, FSDataOutputStream> openFileMap) throws IOException {
    HdfsAdmin hdfsAdmin = new HdfsAdmin(FileSystem.getDefaultUri(conf), conf);
    HashSet<Path> openFiles = new HashSet<>(openFileMap.keySet());
    RemoteIterator<OpenFileEntry> openFilesRemoteItr =
        hdfsAdmin.listOpenFiles(EnumSet.of(OpenFilesType.ALL_OPEN_FILES),
            OpenFilesIterator.FILTER_PATH_DEFAULT);
    while (openFilesRemoteItr.hasNext()) {
      String filePath = openFilesRemoteItr.next().getFilePath();
      assertFalse(filePath + " should not be listed under open files!",
          closedFiles.contains(filePath));
      assertTrue(filePath + " is not listed under open files!",
          openFiles.remove(new Path(filePath)));
    }
    assertTrue("Not all open files are listed!", openFiles.isEmpty());
  }
}
