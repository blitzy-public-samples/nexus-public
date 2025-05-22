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
package com.sonatype.nexus.ssl.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Tag;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.sonatype.nexus.ssl.plugin.internal.keystore.KeyStoreDAO;
import com.sonatype.nexus.ssl.plugin.internal.keystore.KeyStoreData;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Test class to validate that KeyStoreDAO operations function correctly when executed within Java 21 Virtual Threads.
 * This extends the standard KeyStoreDAOTest functionality by running CRUD operations in virtual threads and verifying
 * that database interactions work properly with the lightweight threading model.
 */
@Tag("VirtualThreadTestGroup")
public class KeyStoreDAOVirtualThreadTest
{
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule().access(KeyStoreDAO.class);

  private DataSession<?> session;

  private KeyStoreDAO dao;

  @Before
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(KeyStoreDAO.class);
  }

  @After
  public void cleanup() {
    session.close();
  }

  /**
   * Test basic CRUD operations executed in a single virtual thread.
   */
  @Test
  public void testCrudOperationsInVirtualThread() throws Exception {
    // Create a thread that will run in virtual mode
    Thread virtualThread = Thread.ofVirtual().name("keystore-virtual-thread").start(() -> {
      // Verify this is actually running in a virtual thread
      assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
      
      // Create a KeyStoreData entity
      KeyStoreData entity = new KeyStoreData();
      entity.setName("virtual-keystore");
      entity.setBytes(new byte[]{1, 2, 3});
      
      // Save the KeyStoreData
      boolean saveResult = dao.save(entity);
      assertThat(saveResult, is(true));

      // Read back the KeyStoreData
      Optional<KeyStoreData> readBack = dao.load(entity.getName());
      assertThat(readBack.isPresent(), is(true));
      assertThat(readBack.get().getName(), is(entity.getName()));
      assertThat(readBack.get().getBytes(), is(entity.getBytes()));

      // Update the KeyStoreData
      entity.setBytes(new byte[]{4, 5, 6});
      boolean updateResult = dao.save(entity);
      assertThat(updateResult, is(true));

      // Read back the updated KeyStoreData
      Optional<KeyStoreData> updated = dao.load(entity.getName());
      assertThat(updated.isPresent(), is(true));
      assertThat(updated.get().getName(), is(entity.getName()));
      assertThat(updated.get().getBytes(), is(new byte[]{4, 5, 6}));

      // Delete the KeyStoreData
      boolean deleteResult = dao.delete(entity.getName());
      assertThat(deleteResult, is(true));

      // Verify the KeyStoreData does not exist anymore
      Optional<KeyStoreData> deleted = dao.load(entity.getName());
      assertThat(deleted.isPresent(), is(false));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Test concurrent operations with multiple virtual threads.
   * This verifies that the KeyStoreDAO can handle concurrent operations from multiple virtual threads.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    // Number of concurrent operations to perform
    int concurrentOperations = 50;
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    
    // Track any errors that occur during execution
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < concurrentOperations; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Verify we're running in a virtual thread
            assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
            
            // Create a unique name for this thread's keystore
            String keystoreName = "concurrent-keystore-" + index;
            
            // Create a KeyStoreData entity
            KeyStoreData entity = new KeyStoreData();
            entity.setName(keystoreName);
            entity.setBytes(new byte[]{(byte) index, (byte) (index + 1), (byte) (index + 2)});
            
            // Save the KeyStoreData
            boolean saveResult = dao.save(entity);
            assertThat(saveResult, is(true));

            // Read back the KeyStoreData
            Optional<KeyStoreData> readBack = dao.load(entity.getName());
            assertThat(readBack.isPresent(), is(true));
            assertThat(readBack.get().getName(), is(entity.getName()));

            // Update the KeyStoreData
            entity.setBytes(new byte[]{(byte) (index + 3), (byte) (index + 4), (byte) (index + 5)});
            boolean updateResult = dao.save(entity);
            assertThat(updateResult, is(true));

            // Read back the updated KeyStoreData
            Optional<KeyStoreData> updated = dao.load(entity.getName());
            assertThat(updated.isPresent(), is(true));
            assertThat(updated.get().getName(), is(entity.getName()));

            // Delete the KeyStoreData
            boolean deleteResult = dao.delete(entity.getName());
            assertThat(deleteResult, is(true));

            // Verify the KeyStoreData does not exist anymore
            Optional<KeyStoreData> deleted = dao.load(entity.getName());
            assertThat(deleted.isPresent(), is(false));
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all operations completed successfully
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent operations", errorCount.get(), is(0));
      
      if (!exceptions.isEmpty()) {
        // If there were exceptions, print the first one to help with debugging
        exceptions.get(0).printStackTrace();
        throw exceptions.get(0);
      }
    }
  }

  /**
   * Test mixed read operations with multiple virtual threads.
   * This verifies that the KeyStoreDAO can handle concurrent read operations from multiple virtual threads.
   */
  @Test
  public void testConcurrentReadOperationsWithVirtualThreads() throws Exception {
    // First create a test keystore in the main thread
    KeyStoreData entity = new KeyStoreData();
    entity.setName("shared-keystore");
    entity.setBytes(new byte[]{10, 20, 30});
    boolean saveResult = dao.save(entity);
    assertThat(saveResult, is(true));
    
    try {
      // Number of concurrent read operations to perform
      int concurrentReads = 100;
      
      // Create a countdown latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(concurrentReads);
      
      // Track any errors that occur during execution
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create a virtual thread per task executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit read tasks to the executor
        for (int i = 0; i < concurrentReads; i++) {
          executor.submit(() -> {
            try {
              // Verify we're running in a virtual thread
              assertTrue("Should be running in a virtual thread", Thread.currentThread().isVirtual());
              
              // Read the shared KeyStoreData
              Optional<KeyStoreData> readBack = dao.load(entity.getName());
              assertThat(readBack.isPresent(), is(true));
              assertThat(readBack.get().getName(), is(entity.getName()));
              assertThat(readBack.get().getBytes(), is(entity.getBytes()));
            }
            catch (Exception e) {
              errorCount.incrementAndGet();
              e.printStackTrace();
            }
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all operations to complete (with timeout)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        
        // Verify all operations completed successfully
        assertThat("All read operations should complete within the timeout", completed, is(true));
        assertThat("No errors should occur during concurrent read operations", errorCount.get(), is(0));
      }
    }
    finally {
      // Clean up the test keystore
      dao.delete(entity.getName());
    }
  }
}