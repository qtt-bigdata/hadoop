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
package org.apache.hadoop.hdfs.tools;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY;

import com.google.common.collect.Lists;
import org.apache.commons.lang.text.StrBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.ReconfigurationUtil;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.PathUtils;
import org.apache.hadoop.util.ToolRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestDFSAdmin {
  private static final Log LOG = LogFactory.getLog(TestDFSAdmin.class);
  private Configuration conf = null;
  private MiniDFSCluster cluster;
  private DFSAdmin admin;
  private DataNode datanode;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Before
  public void setUp() throws Exception {
    conf = new Configuration();
    restartCluster();

    admin = new DFSAdmin();
  }

  @After
  public void tearDown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  private void restartCluster() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    datanode = cluster.getDataNodes().get(0);
  }

  private List<String> getReconfigureStatus(String nodeType, String address)
      throws IOException {
    ByteArrayOutputStream bufOut = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bufOut);
    ByteArrayOutputStream bufErr = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(bufErr);
    admin.getReconfigurationStatus(nodeType, address, out, err);
    Scanner scanner = new Scanner(bufOut.toString());
    List<String> outputs = Lists.newArrayList();
    while (scanner.hasNextLine()) {
      outputs.add(scanner.nextLine());
    }
    return outputs;
  }

  /**
   * Test reconfiguration and check the status outputs.
   * @param expectedSuccuss set true if the reconfiguration task should success.
   * @throws IOException
   * @throws InterruptedException
   */
  private void testGetReconfigurationStatus(boolean expectedSuccuss)
      throws IOException, InterruptedException {
    ReconfigurationUtil ru = mock(ReconfigurationUtil.class);
    datanode.setReconfigurationUtil(ru);

    List<ReconfigurationUtil.PropertyChange> changes =
        new ArrayList<>();
    File newDir = new File(cluster.getDataDirectory(), "data_new");
    if (expectedSuccuss) {
      newDir.mkdirs();
    } else {
      // Inject failure.
      newDir.createNewFile();
    }
    changes.add(new ReconfigurationUtil.PropertyChange(
        DFS_DATANODE_DATA_DIR_KEY, newDir.toString(),
        datanode.getConf().get(DFS_DATANODE_DATA_DIR_KEY)));
    changes.add(new ReconfigurationUtil.PropertyChange(
        "randomKey", "new123", "old456"));
    when(ru.parseChangedProperties(any(Configuration.class),
        any(Configuration.class))).thenReturn(changes);

    final int port = datanode.getIpcPort();
    final String address = "localhost:" + port;

    assertThat(admin.startReconfiguration("datanode", address), is(0));

    List<String> outputs = null;
    int count = 100;
    while (count > 0) {
      outputs = getReconfigureStatus("datanode", address);
      if (!outputs.isEmpty() && outputs.get(0).contains("finished")) {
        break;
      }
      count--;
      Thread.sleep(100);
    }
    assertTrue(count > 0);
    if (expectedSuccuss) {
      assertThat(outputs.size(), is(4));
    } else {
      assertThat(outputs.size(), is(6));
    }

    List<StorageLocation> locations = DataNode.getStorageLocations(
        datanode.getConf());
    if (expectedSuccuss) {
      assertThat(locations.size(), is(1));
      assertThat(locations.get(0).getFile(), is(newDir));
      // Verify the directory is appropriately formatted.
      assertTrue(new File(newDir, Storage.STORAGE_DIR_CURRENT).isDirectory());
    } else {
      assertTrue(locations.isEmpty());
    }

    int offset = 1;
    if (expectedSuccuss) {
      assertThat(outputs.get(offset),
          containsString("SUCCESS: Changed property " +
              DFS_DATANODE_DATA_DIR_KEY));
    } else {
      assertThat(outputs.get(offset),
          containsString("FAILED: Change property " +
              DFS_DATANODE_DATA_DIR_KEY));
    }
    assertThat(outputs.get(offset + 1),
        is(allOf(containsString("From:"), containsString("data1"),
            containsString("data2"))));
    assertThat(outputs.get(offset + 2),
        is(not(anyOf(containsString("data1"), containsString("data2")))));
    assertThat(outputs.get(offset + 2),
        is(allOf(containsString("To"), containsString("data_new"))));
  }

  @Test(timeout = 30000)
  public void testGetReconfigurationStatus()
      throws IOException, InterruptedException {
    testGetReconfigurationStatus(true);
    restartCluster();
    testGetReconfigurationStatus(false);
  }

  private List<String> getReconfigurationAllowedProperties(
      String nodeType, String address)
      throws IOException {
    ByteArrayOutputStream bufOut = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bufOut);
    ByteArrayOutputStream bufErr = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(bufErr);
    admin.getReconfigurableProperties(nodeType, address, out, err);
    Scanner scanner = new Scanner(bufOut.toString());
    List<String> outputs = Lists.newArrayList();
    while (scanner.hasNextLine()) {
      outputs.add(scanner.nextLine());
    }
    return outputs;
  }

  @Test(timeout = 30000)
  public void testGetReconfigAllowedProperties() throws IOException {
    final int port = datanode.getIpcPort();
    final String address = "localhost:" + port;
    List<String> outputs =
        getReconfigurationAllowedProperties("datanode", address);
    assertEquals(3, outputs.size());
    assertEquals(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY,
        outputs.get(1));
  }

  private void redirectStream() {
    System.setOut(new PrintStream(out));
    System.setErr(new PrintStream(err));
  }

  private void resetStream() {
    out.reset();
    err.reset();
  }

  private static String scanIntoString(final ByteArrayOutputStream baos) {
    final StrBuilder sb = new StrBuilder();
    final Scanner scanner = new Scanner(baos.toString());
    while (scanner.hasNextLine()) {
      sb.appendln(scanner.nextLine());
    }
    scanner.close();
    return sb.toString();
  }

  @Test(timeout = 300000L)
  public void testListOpenFiles() throws Exception {
    redirectStream();

    final Configuration dfsConf = new HdfsConfiguration();
    dfsConf.setInt(
        DFSConfigKeys.DFS_NAMENODE_HEARTBEAT_RECHECK_INTERVAL_KEY, 500);
    dfsConf.setLong(DFS_HEARTBEAT_INTERVAL_KEY, 1);
    dfsConf.setLong(DFSConfigKeys.DFS_NAMENODE_LIST_OPENFILES_NUM_RESPONSES, 5);
    final Path baseDir = new Path(
        PathUtils.getTestDir(getClass()).getAbsolutePath(),
        GenericTestUtils.getMethodName());
    dfsConf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, baseDir.toString());

    final int numDataNodes = 3;
    final int numClosedFiles = 25;
    final int numOpenFiles = 15;

    MiniDFSCluster miniCluster = new MiniDFSCluster
        .Builder(dfsConf).numDataNodes(numDataNodes).build();
    {
      final short replFactor = 1;
      final long fileLength = 512L;
      final FileSystem fs = miniCluster.getFileSystem();
      final Path parentDir = new Path("/tmp/files/");

      fs.mkdirs(parentDir);
      HashSet<Path> closedFileSet = new HashSet<>();
      for (int i = 0; i < numClosedFiles; i++) {
        Path file = new Path(parentDir, "closed-file-" + i);
        DFSTestUtil.createFile(fs, file, fileLength, replFactor, 12345L);
        closedFileSet.add(file);
      }

      HashMap<Path, FSDataOutputStream> openFilesMap = new HashMap<>();
      for (int i = 0; i < numOpenFiles; i++) {
        Path file = new Path(parentDir, "open-file-" + i);
        DFSTestUtil.createFile(fs, file, fileLength, replFactor, 12345L);
        FSDataOutputStream outputStream = fs.append(file);
        openFilesMap.put(file, outputStream);
      }

      final DFSAdmin dfsAdmin = new DFSAdmin(dfsConf);
      assertEquals(0, ToolRunner.run(dfsAdmin,
          new String[]{"-listOpenFiles"}));
      verifyOpenFilesListing(closedFileSet, openFilesMap);

      for (int count = 0; count < numOpenFiles; count++) {
        closedFileSet.addAll(DFSTestUtil.closeOpenFiles(openFilesMap, 1));
        resetStream();
        assertEquals(0, ToolRunner.run(dfsAdmin,
            new String[]{"-listOpenFiles"}));
        verifyOpenFilesListing(closedFileSet, openFilesMap);
      }

      // test -listOpenFiles command with option <path>
      openFilesMap.clear();
      Path file;
      HashMap<Path, FSDataOutputStream> openFiles1 = new HashMap<>();
      HashMap<Path, FSDataOutputStream> openFiles2 = new HashMap<>();
      for (int i = 0; i < numOpenFiles; i++) {
        if (i % 2 == 0) {
          file = new Path(new Path("/tmp/files/a"), "open-file-" + i);
        } else {
          file = new Path(new Path("/tmp/files/b"), "open-file-" + i);
        }

        DFSTestUtil.createFile(fs, file, fileLength, replFactor, 12345L);
        FSDataOutputStream outputStream = fs.append(file);

        if (i % 2 == 0) {
          openFiles1.put(file, outputStream);
        } else {
          openFiles2.put(file, outputStream);
        }
        openFilesMap.put(file, outputStream);
      }

      resetStream();
      // list all open files
      assertEquals(0,
          ToolRunner.run(dfsAdmin, new String[] {"-listOpenFiles"}));
      verifyOpenFilesListing(null, openFilesMap);

      resetStream();
      // list open files under directory path /tmp/files/a
      assertEquals(0, ToolRunner.run(dfsAdmin,
          new String[] {"-listOpenFiles", "-path", "/tmp/files/a"}));
      verifyOpenFilesListing(null, openFiles1);

      resetStream();
      // list open files without input path
      assertEquals(-1, ToolRunner.run(dfsAdmin,
          new String[] {"-listOpenFiles", "-path"}));
      // verify the error
      String outStr = scanIntoString(err);
      assertTrue(outStr.contains("listOpenFiles: option"
          + " -path requires 1 argument"));

      resetStream();
      // list open files with empty path
      assertEquals(0, ToolRunner.run(dfsAdmin,
          new String[] {"-listOpenFiles", "-path", ""}));
      // all the open files will be listed
      verifyOpenFilesListing(null, openFilesMap);

      resetStream();
      // list invalid path file
      assertEquals(0, ToolRunner.run(dfsAdmin,
          new String[] {"-listOpenFiles", "-path", "/invalid_path"}));
      outStr = scanIntoString(out);
      for (Path openFilePath : openFilesMap.keySet()) {
        assertThat(outStr, not(containsString(openFilePath.toString())));
      }
      DFSTestUtil.closeOpenFiles(openFilesMap, openFilesMap.size());
    }
    miniCluster.shutdown();
  }

  private void verifyOpenFilesListing(HashSet<Path> closedFileSet,
      HashMap<Path, FSDataOutputStream> openFilesMap) {
    final String outStr = scanIntoString(out);
    LOG.info("dfsadmin -listOpenFiles output: \n" + out);
    if (closedFileSet != null) {
      for (Path closedFilePath : closedFileSet) {
        assertThat(outStr,
            not(containsString(closedFilePath.toString() + "\n")));
      }
    }

    for (Path openFilePath : openFilesMap.keySet()) {
      assertThat(outStr, is(containsString(openFilePath.toString() + "\n")));
    }
  }
}