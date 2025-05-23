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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

@Tag("SQLTestGroup")
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

  /**
   * Test concurrent database operations using Virtual Threads.
   * This test verifies that the DAO can handle multiple concurrent operations
   * when executed with Java 21 Virtual Threads.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    int threadCount = 50; // Number of virtual threads to create
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit tasks to create records using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final String blobId = "virtual-blob-" + i;
        executor.submit(() -> {
          try {
            // Create a record
            dao.createRecord(FAKE_BLOB_STORE_NAME, blobId, UTC.now());
            
            // Verify the record exists
            int limit = 10;
            Optional<SoftDeletedBlobsData> record = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME)
                .stream()
                .filter(data -> blobId.equals(data.getBlobId()))
                .findFirst();
            
            if (record.isPresent() && blobId.equals(record.get().getBlobId())) {
              successCount.incrementAndGet();
            }
            
            // Delete the record
            dao.deleteRecord(FAKE_BLOB_STORE_NAME, blobId);
          } 
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    }
    
    // Verify all operations were successful
    assertEquals(threadCount, successCount.get(), "All virtual thread operations should succeed");
    
    // Verify all records were properly deleted
    int limit = 100;
    Continuation<SoftDeletedBlobsData> remainingData = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME);
    assertEquals(0, remainingData.size(), "All records should be deleted");
  }
  
  /**
   * Test JDBC driver compatibility with Virtual Threads by performing
   * a large number of database operations concurrently.
   */
  @Test
  public void testJdbcDriverCompatibilityWithVirtualThreads() throws Exception {
    int operationCount = 100; // Number of operations to perform
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit a mix of create, read, and delete operations
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            final String blobId = "jdbc-test-" + index;
            
            // Perform different operations based on the index to test various JDBC operations
            if (index % 3 == 0) {
              // Create operation
              dao.createRecord(FAKE_BLOB_STORE_NAME, blobId, UTC.now());
            } 
            else if (index % 3 == 1) {
              // Read operation
              dao.readRecords(null, 10, FAKE_BLOB_STORE_NAME);
            } 
            else {
              // Create and then delete to test both operations
              dao.createRecord(FAKE_BLOB_STORE_NAME, blobId, UTC.now());
              dao.deleteRecord(FAKE_BLOB_STORE_NAME, blobId);
            }
          } 
          catch (Exception e) {
            log.error("JDBC operation failed in virtual thread", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for JDBC operations");
    }
    
    // Verify no errors occurred, which would indicate JDBC driver compatibility issues
    assertEquals(0, errorCount.get(), "No JDBC errors should occur with virtual threads");
    
    // Clean up any remaining records
    dao.deleteAllRecords(FAKE_BLOB_STORE_NAME, "1000");
  }

  @Test
  public void testDAOOperations() {
    int limit = 100;
    Continuation<SoftDeletedBlobsData> emptyData = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME);
    assertTrue(emptyData.isEmpty(), "Initial data should be empty");

    dao.createRecord(FAKE_BLOB_STORE_NAME, "blobID", UTC.now());
    Optional<SoftDeletedBlobsData> initialBlobID =
        dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).stream().findFirst();

    assertTrue(initialBlobID.isPresent(), "Blob record should be present");
    assertEquals("blobID", initialBlobID.get().getBlobId(), "Blob ID should match");

    dao.deleteRecord(FAKE_BLOB_STORE_NAME, "blobID");
    Continuation<SoftDeletedBlobsData> newBlobs = dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME);

    assertTrue(newBlobs.isEmpty(), "Data should be empty after deletion");

    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob1", UTC.now());
    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob2", UTC.now());
    dao.createRecord(FAKE_BLOB_STORE_NAME, "blob3", UTC.now());

    assertEquals(3, dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).size(), "Should have 3 records");

    dao.deleteAllRecords(FAKE_BLOB_STORE_NAME, "100");

    assertEquals(0, dao.readRecords(null, limit, FAKE_BLOB_STORE_NAME).size(), "Should have 0 records after deleteAll");
  }
}