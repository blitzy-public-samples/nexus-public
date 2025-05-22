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
package virtualthread;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
 import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.pax.logging.NexusLogActivator;
import org.sonatype.nexus.pax.logging.RemoteTimeBasedRollingPolicy;
import org.sonatype.nexus.pax.logging.RollingPolicyUploader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that verify {@link RemoteTimeBasedRollingPolicy} correctly functions when initialized and accessed from
 * Java 21 virtual threads. This ensures the policy's core operations (doStart(), file rolling, ThreadPoolExecutor setup,
 * unuploaded file tracking) work properly in a virtual thread environment.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadRemoteTimeBasedRollingPolicyTest
    extends TestSupport
{
  @Mock
  private NexusLogActivator mockNexusLogActivator;

  @Mock
  private BundleContext bundleContext;
  
  @Captor
  private ArgumentCaptor<ServiceReference<RollingPolicyUploader>> serviceReferenceCaptor;

  private RemoteTimeBasedRollingPolicy<Object> underTest;

  @BeforeEach
  public void setUp() {
    NexusLogActivator.INSTANCE = mockNexusLogActivator;
    when(NexusLogActivator.INSTANCE.getContext()).thenReturn(bundleContext);

    underTest = new RemoteTimeBasedRollingPolicy<>();
    underTest.setMaxHistory(5);
  }

  /**
   * Verifies that policy initialization works correctly when executed from a virtual thread.
   */
  @Test
  public void startSetupPolicyCorrectlyFromVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify we're running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), equalTo(true));
      
      // Initialize the policy from within a virtual thread
      startPolicy("/my-test/path", "/my-test/path/log/test/test-%d{yyyy-MM-dd_HH-mm}.log.gz");
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    // Wait for the virtual thread to complete
    future.join();

    // Verify the policy was correctly initialized
    assertThat(underTest.getContextPrefix(), equalTo("log/test/"));
    assertThat(underTest.getFilenameDateFormat().toPattern(), equalTo("yyyy-MM-dd_HH-mm"));
    assertNotNull(underTest.getExecutor());
    assertNotNull(underTest.getNonUploadedFiles());
  }

  /**
   * Tests that upload operations work correctly when triggered from virtual threads.
   */
  @Test
  public void testUploadWorksAsExpectedFromVirtualThread() throws Exception {
    // Initialize the policy from the main thread
    startPolicy("/example-test/initial-data", "/example-test/initial-data/log-test/audit/audit-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Execute the upload operation from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify we're running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), equalTo(true));
      
      underTest.doUpload("/example-test/initial-data/log-test/audit/audit-2020-01-01.log.gz");
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    // Wait for the virtual thread to complete
    future.join();
    
    // Wait for the executor to process the upload task
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    // Verify the upload was processed correctly
    verify(mockUploader).rollover("log-test/audit/", "2020/1/1/",
        "/example-test/initial-data/log-test/audit/audit-2020-01-01.log.gz");
    verify(bundleContext).ungetService(mockServiceReference);

    assertThat(underTest.getNonUploadedFiles(), empty());
  }

  /**
   * Tests that the policy correctly handles the case when no service reference is available,
   * when triggered from a virtual thread.
   */
  @Test
  public void testUploadWaitSinceNoServiceReferenceFromVirtualThread() throws Exception {
    // Initialize the policy from the main thread
    startPolicy("/example-test/initial-data", "/example-test/initial-data/log-test/audit/audit-%d{yyyy-MM-dd}.log.gz");

    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.emptyList());

    // Execute the upload operation from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify we're running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), equalTo(true));
      
      underTest.doUpload("/example-test/initial-data/log-test/audit/audit-2010-11-26.log.gz");
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    // Wait for the virtual thread to complete
    future.join();
    
    // Wait for the executor to process the upload task
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(bundleContext, never()).getService(any(ServiceReference.class));
    verify(bundleContext, never()).ungetService(any(ServiceReference.class));
    assertThat(underTest.getNonUploadedFiles().size(), equalTo(1));
  }

  /**
   * Tests that multiple concurrent upload operations from virtual threads are handled correctly.
   */
  @Test
  public void testConcurrentUploadsFromVirtualThreads() throws Exception {
    // Initialize the policy from the main thread
    startPolicy("/example-test-4/initial-data-4",
        "/example-test-4/initial-data-4/log/nexus/nexus-%d{yyyy-MM-dd}.log.gz");

    RollingPolicyUploader mockUploader = mock(RollingPolicyUploader.class);
    ServiceReference<RollingPolicyUploader> mockServiceReference = mock(ServiceReference.class);

    // Initially no service references available
    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.emptyList());

    // Create a virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Number of concurrent uploads to perform
    int uploadCount = 3;
    CountDownLatch latch = new CountDownLatch(uploadCount);
    
    // Submit multiple concurrent upload tasks using virtual threads
    for (int i = 0; i < uploadCount; i++) {
      final int day = 24 + i;
      CompletableFuture.runAsync(() -> {
        try {
          // Verify we're running in a virtual thread
          assertThat(Thread.currentThread().isVirtual(), equalTo(true));
          
          underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-" + day + ".log.gz");
        } finally {
          latch.countDown();
        }
      }, virtualExecutor);
    }
    
    // Wait for all virtual threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Wait for the executor to process all upload tasks
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    verify(bundleContext, never()).getService(any(ServiceReference.class));
    verify(bundleContext, never()).ungetService(any(ServiceReference.class));
    // 3 files are not uploaded
    assertThat(underTest.getNonUploadedFiles().size(), equalTo(3));

    // Now make the service reference available
    when(bundleContext.getServiceReferences(eq(RollingPolicyUploader.class), anyString())).thenReturn(
        Collections.singletonList(mockServiceReference));
    when(bundleContext.getService(eq(mockServiceReference))).thenReturn(mockUploader);

    // Submit one more upload from a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      // Verify we're running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), equalTo(true));
      
      underTest.doUpload("/example-test-4/initial-data-4/log/nexus/nexus-2200-12-27.log.gz");
    }, virtualExecutor);
    
    // Wait for the virtual thread to complete
    future.join();
    
    // Wait for the executor to process the upload task
    await().atMost(10, TimeUnit.SECONDS).until(this::isExecutorIdle);

    // Verify all 4 files were processed
    verify(mockUploader, times(4)).rollover(eq("log/nexus/"), anyString(), anyString());
    verify(bundleContext).ungetService(mockServiceReference);

    assertThat(underTest.getNonUploadedFiles(), empty());
  }
  
  /**
   * Tests that the ThreadPoolExecutor created by the policy works correctly with virtual threads.
   */
  @Test
  public void testThreadPoolExecutorWithVirtualThreads() throws Exception {
    // Initialize the policy from the main thread
    startPolicy("/thread-test/path", "/thread-test/path/log/test/test-%d{yyyy-MM-dd_HH-mm}.log.gz");
    
    // Get the executor from the policy
    ThreadPoolExecutor executor = (ThreadPoolExecutor) underTest.getExecutor();
    assertNotNull(executor);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Number of tasks to submit
    int taskCount = 10;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Submit tasks from virtual threads that will use the policy's executor
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    for (int i = 0; i < taskCount; i++) {
      virtualExecutor.submit(() -> {
        try {
          // Verify we're running in a virtual thread
          assertThat(Thread.currentThread().isVirtual(), equalTo(true));
          
          // Submit a task to the policy's executor
          executor.submit(() -> {
            completedTasks.incrementAndGet();
            return null;
          });
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all virtual threads to complete submitting tasks
    latch.await(10, TimeUnit.SECONDS);
    
    // Wait for the executor to process all tasks
    await().atMost(10, TimeUnit.SECONDS).until(() -> completedTasks.get() == taskCount);
    
    // Verify all tasks were completed
    assertThat(completedTasks.get(), equalTo(taskCount));
  }

  @AfterEach
  public void tearDown() {
    System.getProperties().clear();
  }

  private Boolean isExecutorIdle() {
    ThreadPoolExecutor threadPool = (ThreadPoolExecutor) underTest.getExecutor();
    return threadPool.getQueue().isEmpty() && threadPool.getActiveCount() == 0;
  }

  private void startPolicy(final String karafData, final String fileNamePattern) {
    //set to test the correct setup of the policy
    System.setProperty("karaf.data", karafData);
    underTest.setFileNamePattern(fileNamePattern);
    underTest.doStart();
  }
}