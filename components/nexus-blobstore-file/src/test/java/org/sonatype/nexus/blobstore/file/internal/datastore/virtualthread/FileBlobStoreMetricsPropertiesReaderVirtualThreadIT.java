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
package org.sonatype.nexus.blobstore.file.internal.datastore.virtualthread;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.FileOperations;
import org.sonatype.nexus.blobstore.file.internal.datastore.metrics.FileBlobStoreMetricsPropertiesReader;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.common.property.PropertiesFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link FileBlobStoreMetricsPropertiesReader} with Java 21 virtual threads.
 * Tests concurrent file I/O operations using virtual threads and validates that the metrics reader
 * operations don't cause thread pinning.
 */
public class FileBlobStoreMetricsPropertiesReaderVirtualThreadIT
    extends TestSupport
{
  private FileBlobStoreMetricsPropertiesReader underTest;

  private Path blobStoreDirectory;

  private static final int METRICS_FLUSH_TIMEOUT = 5;
  private static final int CONCURRENT_THREADS = 100;
  private static final int THREAD_PINNING_TIMEOUT_MS = 500;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  FileBlobStore blobStore;

  @Mock
  FileOperations fileOperations;

  @Before
  public void setUp() {
    when(nodeAccess.getId()).thenReturn(UUID.randomUUID().toString());
    blobStoreDirectory = util.createTempDir().toPath();
  }

  @After
  public void tearDown() throws Exception {
    if (underTest != null && underTest.isStarted()) {
      underTest.stop();
    }
  }

  /**
   * Tests that metrics can be loaded concurrently by multiple virtual threads without thread pinning.
   * This test creates multiple virtual threads that all try to access the metrics simultaneously.
   */
  @Test
  public void testConcurrentMetricsLoadingWithVirtualThreads() throws Exception {
    // Initialize properties file with test data
    PropertiesFile props = new PropertiesFile(
        blobStoreDirectory.resolve(nodeAccess.getId() + "-" + FileBlobStoreMetricsPropertiesReader.METRICS_FILENAME)
            .toFile());

    props.put(FileBlobStoreMetricsPropertiesReader.BLOB_COUNT_PROP_NAME, "32");
    props.put(FileBlobStoreMetricsPropertiesReader.TOTAL_SIZE_PROP_NAME, "200");

    props.store();

    // Initialize the metrics reader
    init();

    // Wait for initial metrics to be loaded
    await().atMost(METRICS_FLUSH_TIMEOUT, SECONDS).until(() -> underTest.getMetrics().getBlobCount(), is(32L));
    await().atMost(METRICS_FLUSH_TIMEOUT, SECONDS).until(() -> underTest.getMetrics().getTotalSize(), is(200L));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
      
      // Submit concurrent tasks to read metrics
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to start at the same time
            startLatch.await();
            
            // Use a separate thread to monitor for pinning
            AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
            Thread monitorThread = new Thread(() -> {
              try {
                Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
                // If we reach here and the monitored thread is still running the same task,
                // it might be pinned
                if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
                  threadPinningDetected.set(true);
                }
              } catch (InterruptedException e) {
                // Monitor thread was interrupted, which is expected when the task completes normally
              }
            });
            
            monitorThread.start();
            
            // Perform the metrics read operation
            long blobCount = underTest.getMetrics().getBlobCount();
            long totalSize = underTest.getMetrics().getTotalSize();
            
            // Verify metrics are consistent
            assertEquals(32L, blobCount);
            assertEquals(200L, totalSize);
            
            // Task completed, clear the monitored thread reference and interrupt the monitor
            monitoredThread.set(null);
            monitorThread.interrupt();
            monitorThread.join(100); // Wait for monitor thread to finish
            
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // Verify no thread pinning was detected
      assertFalse("Thread pinning detected during concurrent metrics access", threadPinningDetected.get());
    }
  }

  /**
   * Tests that metrics can be updated concurrently by multiple virtual threads without thread pinning.
   * This test creates multiple virtual threads that all try to update the metrics simultaneously.
   */
  @Test
  public void testConcurrentMetricsUpdateWithVirtualThreads() throws Exception {
    // Initialize the metrics reader
    init();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
      
      // Submit concurrent tasks to update metrics
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int increment = i + 1;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to start at the same time
            startLatch.await();
            
            // Use a separate thread to monitor for pinning
            AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
            Thread monitorThread = new Thread(() -> {
              try {
                Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
                // If we reach here and the monitored thread is still running the same task,
                // it might be pinned
                if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
                  threadPinningDetected.set(true);
                }
              } catch (InterruptedException e) {
                // Monitor thread was interrupted, which is expected when the task completes normally
              }
            });
            
            monitorThread.start();
            
            // Perform the metrics update operation
            underTest.getMetrics().addBlobSize(increment);
            
            // Task completed, clear the monitored thread reference and interrupt the monitor
            monitoredThread.set(null);
            monitorThread.interrupt();
            monitorThread.join(100); // Wait for monitor thread to finish
            
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // Verify no thread pinning was detected
      assertFalse("Thread pinning detected during concurrent metrics updates", threadPinningDetected.get());
      
      // Calculate expected total size: sum of numbers from 1 to CONCURRENT_THREADS
      long expectedTotalSize = (long) CONCURRENT_THREADS * (CONCURRENT_THREADS + 1) / 2;
      
      // Verify metrics were updated correctly
      await().atMost(METRICS_FLUSH_TIMEOUT, SECONDS)
          .until(() -> underTest.getMetrics().getTotalSize(), is(expectedTotalSize));
    }
  }

  /**
   * Tests that backing files can be accessed concurrently by multiple virtual threads without thread pinning.
   */
  @Test
  public void testConcurrentBackingFilesAccessWithVirtualThreads() throws Exception {
    // Initialize the metrics reader
    init();

    // Create a properties file
    PropertiesFile props = new PropertiesFile(
        blobStoreDirectory.resolve(nodeAccess.getId() + "-" + FileBlobStoreMetricsPropertiesReader.METRICS_FILENAME)
            .toFile());
    props.store();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
      
      // Submit concurrent tasks to access backing files
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to start at the same time
            startLatch.await();
            
            // Use a separate thread to monitor for pinning
            AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
            Thread monitorThread = new Thread(() -> {
              try {
                Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
                // If we reach here and the monitored thread is still running the same task,
                // it might be pinned
                if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
                  threadPinningDetected.set(true);
                }
              } catch (InterruptedException e) {
                // Monitor thread was interrupted, which is expected when the task completes normally
              }
            });
            
            monitorThread.start();
            
            // Access backing files
            long count = underTest.backingFiles().count();
            assertEquals(1L, count);
            
            // Task completed, clear the monitored thread reference and interrupt the monitor
            monitoredThread.set(null);
            monitorThread.interrupt();
            monitorThread.join(100); // Wait for monitor thread to finish
            
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // Verify no thread pinning was detected
      assertFalse("Thread pinning detected during concurrent backing files access", threadPinningDetected.get());
    }
  }

  private void init() throws Exception {
    init(blobStoreDirectory);
  }

  private void init(final Path path) throws Exception {
    underTest = new FileBlobStoreMetricsPropertiesReader();

    when(blobStore.getAbsoluteBlobDir()).thenReturn(path);
    underTest.init(blobStore);
  }
}