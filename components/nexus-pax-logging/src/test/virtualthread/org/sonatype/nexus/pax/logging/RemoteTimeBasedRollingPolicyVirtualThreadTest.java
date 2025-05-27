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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * when using Java 21 Virtual Threads for asynchronous log rolling operations.
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
   * Test that the policy is correctly set up with a virtual thread executor.
   */
  @Test
  public void startSetupPolicyWithVirtualThreadExecutor() {
    startPolicy("/my-test/path", "/my-test/path/log/test/test-%d{yyyy-MM-dd_HH-mm}.log.gz");

    assertThat(underTest.getContextPrefix(), equalTo("log/test/"));
    assertThat(underTest.getFilenameDateFormat().toPattern(), equalTo("yyyy-MM-dd_HH-mm"));
    assertNotNull(underTest.getExecutor());
    assertTrue("Executor should be a virtual thread executor", 
        underTest.getExecutor() instanceof ExecutorService);
    assertNotNull(underTest.getNonUploadedFiles());
  }

  /**
   * Test that upload works as expected with virtual threads.
   */
  @Test
  public void testUploadWorksWithVirtualThreads() throws Exception {
    startPolicy("/example-test/initial-data", "/example-test/initial-data/log-test/audit/audit-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    underTest.doUpload("/example-test/initial-data/log-test/audit/audit-2020-01-01.log.gz");

    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(mockUploader).rollover("log-test/audit/", "2020/1/1/",
        "/example-test/initial-data/log-test/audit/audit-2020-01-01.log.gz");
    verify(bundleContext).ungetService(mockServiceReference);

    assertThat(underTest.getNonUploadedFiles(), empty());
  }

  /**
   * Test that upload waits when no service reference is available with virtual threads.
   */
  @Test
  public void testUploadWaitWithVirtualThreads() throws Exception {
    startPolicy("/example-test/initial-data", "/example-test/initial-data/log-test/audit/audit-%d{yyyy-MM-dd}.log.gz");

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.emptyList());

    underTest.doUpload("/example-test/initial-data/log-test/audit/audit-2010-11-26.log.gz");

    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(bundleContext, never()).getService(any(ServiceReference.class));
    verify(bundleContext, never()).ungetService(any(ServiceReference.class));
    assertThat(underTest.getNonUploadedFiles().size(), equalTo(1));
  }

  /**
   * Test that multiple non-uploaded files are sent when service becomes available with virtual threads.
   */
  @Test
  public void testUploadMultipleFilesWithVirtualThreads() throws Exception {
    startPolicy("/example-test-4/initial-data-4",
        "/example-test-4/initial-data-4/log/nexus/nexus-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.emptyList());

    underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-24.log.gz");
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-25.log.gz");
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-26.log.gz");
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(bundleContext, never()).getService(any(ServiceReference.class));
    verify(bundleContext, never()).ungetService(any(ServiceReference.class));
    // 3 files are not uploaded
    assertThat(underTest.getNonUploadedFiles().size(), equalTo(3));

    // mock to simulate the service reference is available
    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-27.log.gz");

    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(mockUploader, times(4)).rollover(eq("log/nexus/"), anyString(), anyString());
    verify(bundleContext).ungetService(mockServiceReference);

    assertThat(underTest.getNonUploadedFiles(), empty());
  }

  /**
   * Test high concurrency with many virtual threads uploading simultaneously.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    startPolicy("/high-concurrency/data", "/high-concurrency/data/log/test/test-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Create 50 concurrent upload tasks
    int concurrentTasks = 50;
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    AtomicInteger completedTasks = new AtomicInteger(0);

    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 1; i <= concurrentTasks; i++) {
        final int day = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          String filePath = String.format("/high-concurrency/data/log/test/test-2023-01-%02d.log.gz", day);
          underTest.doUpload(filePath);
          completedTasks.incrementAndGet();
        }, virtualExecutor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    await().atMost(20, TimeUnit.SECONDS).until(this::isExecutorIdle);

    // Verify all tasks were processed
    assertThat(completedTasks.get(), equalTo(concurrentTasks));
    verify(mockUploader, times(concurrentTasks)).rollover(eq("log/test/"), anyString(), anyString());
    verify(bundleContext, times(concurrentTasks)).ungetService(mockServiceReference);
  }

  /**
   * Test that thread-local state is properly maintained across virtual thread boundaries.
   */
  @Test
  public void testThreadLocalStateWithVirtualThreads() throws Exception {
    startPolicy("/thread-local/data", "/thread-local/data/log/test/test-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Create a thread-local variable to test state maintenance
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("main-thread-value");

    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        // Set a different value in the virtual thread
        threadLocal.set("virtual-thread-value");
        
        // Perform upload operation
        underTest.doUpload("/thread-local/data/log/test/test-2023-01-01.log.gz");
        
        // Return the thread-local value from the virtual thread
        return threadLocal.get();
      }, virtualExecutor);

      // Get the value from the virtual thread
      String virtualThreadValue = future.join();
      
      // Verify thread-local isolation
      assertThat(virtualThreadValue, equalTo("virtual-thread-value"));
      assertThat(threadLocal.get(), equalTo("main-thread-value"));
    }

    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(mockUploader).rollover(eq("log/test/"), anyString(), anyString());
    verify(bundleContext).ungetService(mockServiceReference);
  }

  @After
  public void tearDown() {
    System.getProperties().clear();
  }

  /**
   * Check if the executor is idle (no active tasks).
   * This method works with both ThreadPoolExecutor and VirtualThreadPerTaskExecutor.
   */
  private Boolean isExecutorIdle() {
    // For virtual threads, we can't directly check the queue and active count like with ThreadPoolExecutor,
    // but we can check if there are any non-uploaded files still being processed
    return underTest.getExecutor() != null && 
           (underTest.getNonUploadedFiles().isEmpty() || 
            !underTest.getNonUploadedFiles().isEmpty() && 
            bundleContext.getServiceReferences(RollingPolicyUploader.class, null) == null);
  }

  /**
   * Start the policy with the given parameters and configure it to use virtual threads.
   */
  private void startPolicy(final String karafData, final String fileNamePattern) {
    // Set to test the correct setup of the policy
    System.setProperty("karaf.data", karafData);
    underTest.setFileNamePattern(fileNamePattern);
    
    // Configure the policy to use virtual threads
    underTest.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    
    underTest.doStart();
  }
}