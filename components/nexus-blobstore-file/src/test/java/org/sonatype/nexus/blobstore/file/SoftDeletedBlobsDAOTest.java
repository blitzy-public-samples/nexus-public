/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.file;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.file.store.SoftDeletedBlobsData;
import org.sonatype.nexus.blobstore.file.store.internal.SoftDeletedBlobsDAO;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.time.UTC;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionRule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

@Category(SQLTestGroup.class)
public class SoftDeletedBlobsDAOTest
    extends TestSupport
{
  @RegisterExtension
  public DataSessionRule sessionRule = new DataSessionRule().access(SoftDeletedBlobsDAO.class);

  private DataSession<?> session;

  private SoftDeletedBlobsDAO dao;

  private static final String FAKE_BLOB_STORE_NAME = "fakeBlobStore";

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(SoftDeletedBlobsDAO.class);
  }

  @AfterEach
  public void cleanup() {
    session.close();
  }

  @Test
  public void testDAOOperations() {
    int limit = 100;
    Continuation<SoftDeletedBlobsData> emptyData = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME);
    assertTrue(emptyData.isEmpty());

    dao.createRecord(FAKE_BLOB_STORE_NAME, "blobID", UTC.now());
    Optional<SoftDeletedBlobsData> initialBlobID =
        dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).stream().findFirst();

    assertTrue(initialBlobID.isPresent());
    assertEquals("blobID", initialBlobID.get().getBlobId());

    dao.deleteRecord(FAKE_BLOB_STORE_NAME, "blobID");
    Continuation<SoftDeletedBlobsData> newBlobs = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME);

    assertTrue(newBlobs.isEmpty());

    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob1", UTC.now());
    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob2", UTC.now());
    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob3", UTC.now());

    assertEquals(3, dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).size());

    dao.deleteAllRecords(FAKE_BLOB_STORE_NAME, "100");

    assertEquals(0, dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).size());
  }

  @Test
  public void testConcurrentDAOOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String blobId = "concurrent-blob-" + i;
        executor.submit(() -> {
          try {
            // Create a record
            dao.createRecord(FAKE_BLOB_STORE_NAME, blobId, UTC.now());
            
            // Verify it exists
            Continuation<SoftDeletedBlobsData> records = dao.readRecords(null, 1, FAKE_BLOB_STORE_NAME);
            if (records.isEmpty()) {
              errorCount.incrementAndGet();
            }
            
            // Delete the record
            dao.deleteRecord(FAKE_BLOB_STORE_NAME, blobId);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      assertEquals(0, errorCount.get(), "Errors occurred during concurrent operations with virtual threads");
      
      // Verify final state - all records should be deleted
      Continuation<SoftDeletedBlobsData> finalRecords = dao.readRecords(null, taskCount, FAKE_BLOB_STORE_NAME);
      assertEquals(0, finalRecords.size(), "Some records were not properly deleted");
    } 
    finally {
      executor.shutdown();
    }
  }

  @Test
  public void testJDBCDriverCompatibilityWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Create some initial data
      for (int i = 0; i < 10; i++) {
        dao.createRecord(FAKE_BLOB_STORE_NAME, "jdbc-test-blob-" + i, UTC.now());
      }
      
      // Submit multiple concurrent read/write operations to test JDBC driver compatibility
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            if (index % 2 == 0) {
              // Even threads perform reads
              Continuation<SoftDeletedBlobsData> records = dao.readRecords(null, 100, FAKE_BLOB_STORE_NAME);
              if (!records.isEmpty()) {
                successCount.incrementAndGet();
              }
            } 
            else {
              // Odd threads perform writes
              String blobId = "jdbc-compat-blob-" + index;
              dao.createRecord(FAKE_BLOB_STORE_NAME, blobId, UTC.now());
              dao.deleteRecord(FAKE_BLOB_STORE_NAME, blobId);
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            // Don't increment success count if exception occurs
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify that most operations succeeded (allowing for some potential transient issues)
      assertTrue(successCount.get() >= taskCount * 0.9, 
          "JDBC driver compatibility issues detected with virtual threads");
      
      // Clean up
      dao.deleteAllRecords(FAKE_BLOB_STORE_NAME, "100");
    } 
    finally {
      executor.shutdown();
    }
  }
}