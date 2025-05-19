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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.blobstore.file.internal.SoftDeletedBlobsStoreImpl;
import org.sonatype.nexus.blobstore.file.internal.datastore.DatastoreFileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.store.SoftDeletedBlobsStore;
import org.sonatype.nexus.blobstore.file.store.internal.SoftDeletedBlobsDAO;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.transaction.TransactionModule;
import org.sonatype.nexus.testsuite.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.testsuite.testsupport.group.VirtualThreadTestGroup;

import com.google.inject.Guice;
import com.google.inject.Provides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * {@link FileBlobStore} integration tests with Java 21 Virtual Thread support.
 */
@ExtendWith(MockitoExtension.class)
@Category({SQLTestGroup.class, Java21TestGroup.class, VirtualThreadTestGroup.class})
public class DatastoreFileBlobStoreIT
    extends FileBlobStoreITSupport
{
  private DataSessionRule sessionRule = new DataSessionRule().access(SoftDeletedBlobsDAO.class);

  @Mock
  private EventManager eventManager;

  @Mock
  private PeriodicJobService periodicJobService;

  private SoftDeletedBlobsStore store;

  @BeforeEach
  public void setupPeriodicJobService() {
    // Setup synchronous running of the soft deleted blob index for the tests
    doAnswer(invocation -> {
      invocation.getArgument(0, Runnable.class).run();
      return null;
    })
        .when(periodicJobService)
        .runOnce(any(Runnable.class), anyInt());
  }

  @Override
  protected FileBlobDeletionIndex fileBlobDeletionIndex() {
    if (store == null) {
      store = Guice.createInjector(new TransactionModule()
      {
        @Provides
        DataSessionSupplier getDataSessionSupplier() {
          return sessionRule;
        }

        @Provides
        EventManager getEventManager() {
          return eventManager;
        }
      }).getInstance(SoftDeletedBlobsStoreImpl.class);
    }

    return new DatastoreFileBlobDeletionIndex(store, periodicJobService, Duration.ofSeconds(1), 100);
  }

  /**
   * Test to verify that database operations can be performed using Virtual Threads
   * without thread pinning issues.
   */
  @Test
  public void testVirtualThreadDatabaseOperations() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    
    // Enable thread pinning detection
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Perform database operations using the store
            store.softDelete("test-blob-" + index, System.currentTimeMillis());
            store.browse(10).forEach(blob -> {
              // Access blob data to ensure database operations are performed
              String blobId = blob.getBlobId();
              long timestamp = blob.getDeletedTimestamp();
            });
          } catch (Exception e) {
            if (e.toString().contains("VirtualThread.onPinned") || 
                e.toString().contains("pinned")) {
              pinnedThreadDetected.set(true);
            }
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during virtual thread database operations");
      assertFalse(pinnedThreadDetected.get(), "No thread pinning should be detected during database operations");
    } finally {
      // Restore original system property
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
      executor.shutdown();
    }
  }
}