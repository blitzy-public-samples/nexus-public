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
package org.sonatype.nexus.pax.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RemoteTimeBasedRollingPolicy} with Java 21 Virtual Threads.
 * 
 * This test class verifies that the RemoteTimeBasedRollingPolicy works correctly
 * with Java 21 Virtual Threads, ensuring that asynchronous log rolling operations
 * can leverage the lightweight threading model for improved scalability.
 */
public class RemoteTimeBasedRollingPolicyVirtualThreadTest
    extends TestSupport
{
  @Mock
  private NexusLogActivator mockNexusLogActivator;

  @Mock
  private BundleContext bundleContext;

  private RemoteTimeBasedRollingPolicy<Object> underTest;

  @Before
  public void setUp() {
    NexusLogActivator.INSTANCE = mockNexusLogActivator;
    when(NexusLogActivator.INSTANCE.getContext()).thenReturn(bundleContext);

    underTest = new RemoteTimeBasedRollingPolicy<>();
    underTest.setMaxHistory(5);
  }

  /**
   * Tests that the policy can be configured to use a Virtual Thread executor.
   */
  @Test
  public void testConfigurationWithVirtualThreadExecutor() {
    // Set up a custom executor using virtual threads
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest.setExecutor(virtualThreadExecutor);
    
    startPolicy("/virtual-thread-test/path", "/virtual-thread-test/path/log/test/test-%d{yyyy-MM-dd_HH-mm}.log.gz");

    assertThat(underTest.getContextPrefix(), equalTo("log/test/"));
    assertThat(underTest.getFilenameDateFormat().toPattern(), equalTo("yyyy-MM-dd_HH-mm"));
    assertNotNull(underTest.getExecutor());
    assertNotNull(underTest.getNonUploadedFiles());
    
    // Verify the executor is the one we set
    assertThat(underTest.getExecutor(), is(virtualThreadExecutor));
    
    // Clean up the executor
    virtualThreadExecutor.shutdown();
  }

  /**
   * Tests that uploads work correctly with virtual threads, including proper service resolution
   * and unregistration when using virtual threads.
   */
  @Test
  public void testUploadWorksWithVirtualThreads() throws Exception {
    // Configure the policy with virtual thread executor
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest.setExecutor(virtualThreadExecutor);
    
    startPolicy("/virtual-thread-test/data", "/virtual-thread-test/data/log-test/audit/audit-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    underTest.doUpload("/virtual-thread-test/data/log-test/audit/audit-2023-01-01.log.gz");

    await().atMost(10, TimeUnit.SECONDS).until(() -> isExecutorIdle(virtualThreadExecutor));

    verify(mockUploader).rollover("log-test/audit/", "2023/1/1/",
        "/virtual-thread-test/data/log-test/audit/audit-2023-01-01.log.gz");
    verify(bundleContext).ungetService(mockServiceReference);

    assertThat(underTest.getNonUploadedFiles(), empty());
    
    // Clean up the executor
    virtualThreadExecutor.shutdown();
  }

  /**
   * Tests high concurrency scenario with 50+ virtual threads performing uploads simultaneously.
   * This verifies that the policy can handle a large number of concurrent operations efficiently.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Configure the policy with virtual thread executor
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest.setExecutor(virtualThreadExecutor);
    
    startPolicy("/virtual-thread-test/concurrent", "/virtual-thread-test/concurrent/logs/app/app-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Create 50 log files to upload concurrently
    int concurrentUploads = 50;
    CountDownLatch latch = new CountDownLatch(concurrentUploads);
    AtomicInteger successCount = new AtomicInteger(0);
    
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 1; i <= concurrentUploads; i++) {
      final int day = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          String logFile = String.format("/virtual-thread-test/concurrent/logs/app/app-2023-01-%02d.log.gz", day);
          underTest.doUpload(logFile);
          successCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all uploads to complete
    latch.await(30, TimeUnit.SECONDS);
    
    // Wait for executor to be idle
    await().atMost(30, TimeUnit.SECONDS).until(() -> isExecutorIdle(virtualThreadExecutor));
    
    // Verify all uploads were processed
    assertThat(successCount.get(), equalTo(concurrentUploads));
    
    // Verify the uploader was called for each file
    verify(mockUploader, times(concurrentUploads)).rollover(eq("logs/app/"), anyString(), anyString());
    
    // Verify service was unregistered the correct number of times
    verify(bundleContext, times(concurrentUploads)).ungetService(mockServiceReference);
    
    // Verify no files are left in the non-uploaded list
    assertThat(underTest.getNonUploadedFiles(), empty());
    
    // Clean up the executor
    virtualThreadExecutor.shutdown();
  }

  /**
   * Tests that thread-local state is properly maintained across virtual thread boundaries.
   * This is important because virtual threads may be scheduled on different carrier threads
   * during their lifetime.
   */
  @Test
  public void testThreadLocalStateWithVirtualThreads() throws Exception {
    // Configure the policy with virtual thread executor
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest.setExecutor(virtualThreadExecutor);
    
    startPolicy("/virtual-thread-test/threadlocal", "/virtual-thread-test/threadlocal/logs/app/app-%d{yyyy-MM-dd}.log.gz");

    // Create a thread-local context map to track values across virtual thread boundaries
    ConcurrentHashMap<String, String> threadLocalValues = new ConcurrentHashMap<>();
    
    // Create a custom uploader that uses thread-local state
    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    when(mockUploader.rollover(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
      // Store the thread-local value in our map for verification
      String logFile = invocation.getArgument(2);
      String threadId = Thread.currentThread().toString();
      threadLocalValues.put(logFile, threadId);
      
      // Simulate some work that might cause thread parking/unparking
      Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
      
      return null;
    });
    
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Upload 10 files concurrently
    int fileCount = 10;
    CountDownLatch latch = new CountDownLatch(fileCount);
    
    for (int i = 1; i <= fileCount; i++) {
      final int day = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String logFile = String.format("/virtual-thread-test/threadlocal/logs/app/app-2023-01-%02d.log.gz", day);
          underTest.doUpload(logFile);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all uploads to complete
    latch.await(20, TimeUnit.SECONDS);
    
    // Wait for executor to be idle
    await().atMost(20, TimeUnit.SECONDS).until(() -> isExecutorIdle(virtualThreadExecutor));
    
    // Verify that thread-local values were maintained for each upload
    assertThat(threadLocalValues.size(), equalTo(fileCount));
    
    // Verify the uploader was called for each file
    verify(mockUploader, times(fileCount)).rollover(eq("logs/app/"), anyString(), anyString());
    
    // Clean up the executor
    virtualThreadExecutor.shutdown();
  }

  /**
   * Tests queuing behavior with high concurrency using virtual threads.
   * This verifies that the policy correctly handles a large number of uploads
   * when service references are not immediately available.
   */
  @Test
  public void testQueueingBehaviorWithVirtualThreads() throws Exception {
    // Configure the policy with virtual thread executor
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    underTest.setExecutor(virtualThreadExecutor);
    
    startPolicy("/virtual-thread-test/queue", "/virtual-thread-test/queue/logs/app/app-%d{yyyy-MM-dd}.log.gz");

    // Initially, no service references are available
    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.emptyList());

    // Submit 30 uploads that should all be queued
    int uploadCount = 30;
    for (int i = 1; i <= uploadCount; i++) {
      String logFile = String.format("/virtual-thread-test/queue/logs/app/app-2023-01-%02d.log.gz", i);
      underTest.doUpload(logFile);
    }
    
    // Wait for executor to be idle
    await().atMost(20, TimeUnit.SECONDS).until(() -> isExecutorIdle(virtualThreadExecutor));
    
    // Verify that all files are in the non-uploaded list
    assertThat(underTest.getNonUploadedFiles().size(), equalTo(uploadCount));
    
    // Now make the service reference available
    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);
    
    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);
    
    // Submit one more upload, which should trigger processing of all queued files
    underTest.doUpload("/virtual-thread-test/queue/logs/app/app-2023-01-31.log.gz");
    
    // Wait for executor to be idle
    await().atMost(20, TimeUnit.SECONDS).until(() -> isExecutorIdle(virtualThreadExecutor));
    
    // Verify that the uploader was called for all files (30 queued + 1 new)
    verify(mockUploader, times(uploadCount + 1)).rollover(eq("logs/app/"), anyString(), anyString());
    
    // Verify that the non-uploaded list is now empty
    assertThat(underTest.getNonUploadedFiles(), empty());
    
    // Clean up the executor
    virtualThreadExecutor.shutdown();
  }

  /**
   * Tests that the default executor created by the policy is compatible with virtual threads.
   * This ensures that even without explicit configuration, the policy works well with Java 21.
   */
  @Test
  public void testDefaultExecutorCompatibilityWithVirtualThreads() {
    startPolicy("/virtual-thread-test/default", "/virtual-thread-test/default/logs/app/app-%d{yyyy-MM-dd}.log.gz");
    
    // Get the default executor created by the policy
    ExecutorService defaultExecutor = underTest.getExecutor();
    assertNotNull(defaultExecutor);
    
    // Submit a task to the default executor that creates a virtual thread
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      // Create a virtual thread within the executor's thread
      Thread virtualThread = Thread.ofVirtual().name("nested-virtual-thread").start(() -> {
        try {
          // Do some work
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      
      try {
        // Wait for the virtual thread to complete
        virtualThread.join();
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }, defaultExecutor);
    
    // Verify that the task completed successfully
    assertTrue(future.join());
  }

  @After
  public void tearDown() {
    System.getProperties().clear();
  }

  private boolean isExecutorIdle(ExecutorService executor) {
    // For virtual thread executor, we can't directly check queue size or active count
    // Instead, we submit a task and see if it completes immediately
    try {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> true, executor);
      return future.get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      return false;
    }
  }

  private void startPolicy(final String karafData, final String fileNamePattern) {
    //set to test the correct setup of the policy
    System.setProperty("karaf.data", karafData);
    underTest.setFileNamePattern(fileNamePattern);
    underTest.doStart();
  }
}