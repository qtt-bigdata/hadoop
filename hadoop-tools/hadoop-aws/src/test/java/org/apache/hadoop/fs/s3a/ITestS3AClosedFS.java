/*
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

package org.apache.hadoop.fs.s3a;

import java.io.IOException;

import org.junit.Test;

import org.apache.hadoop.fs.Path;

import static org.apache.hadoop.fs.s3a.S3AUtils.E_FS_CLOSED;

/**
 * Tests of the S3A FileSystem which is closed; just make sure
 * that that basic file Ops fail meaningfully.
 */
public class ITestS3AClosedFS extends AbstractS3ATestBase {

  private Path root = new Path("/");

  @Override
  public void setup() throws Exception {
    super.setup();
    root = getFileSystem().makeQualified(new Path("/"));
    getFileSystem().close();
  }

  @Override
  public void teardown()  {
    // no op, as the FS is closed
  }

  @Test
  public void testClosedGetFileStatus() throws Exception {
    try {
      getFileSystem().getFileStatus(root);
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedListStatus() throws Exception {
    try {
      getFileSystem().listStatus(root);
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedListFile() throws Exception {
    try {
      getFileSystem().listFiles(root, false);
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedListLocatedStatus() throws Exception {
    try {
      getFileSystem().listLocatedStatus(root);
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedCreate() throws Exception {
    try {
      getFileSystem().create(path("to-create")).close();
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedDelete() throws Exception {
    try {
      getFileSystem().delete(path("to-delete"), false);
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

  @Test
  public void testClosedOpen() throws Exception {
    try {
      getFileSystem().open(path("to-open"));
    } catch (IOException e) {
      assertTrue("Exception did not contain expected text",
          e.getMessage().contains(E_FS_CLOSED));
      return;
    }
    fail("Expected exception was not thrown");
  }

}
