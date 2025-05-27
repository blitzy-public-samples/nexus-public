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
package org.sonatype.nexus.repository.maven.internal.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.common.MultipleFailures;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.fluent.FluentAssets;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponents;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.scheduling.TaskInterruptedException;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import static java.lang.Thread.sleep;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * Tests for {@link MavenMetadataRebuilder} using Java 21 Virtual Threads.
 * 
 * This test suite validates that the MavenMetadataRebuilder component functions correctly
 * when executed with Virtual Threads, ensuring proper concurrency behavior, cancelability,
 * and performance characteristics.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class MavenMetadataRebuilderVirtualThreadTest
    extends TestSupport
{
  @Mock
  private MavenContentFacet mavenContentFacet;

  @Mock
  private MavenPathParser mavenPathParser;

  @Mock
  private Repository repository;

  @Mock
  private Appender<ILoggingEvent> mockAppender;

  @Mock
  private FluentAssets assets;

  @Mock
  private FluentComponents components;

  @BeforeEach
  public void setup() {
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);
    when(repository.getFormat()).thenReturn(new Maven2Format());
    when(mavenContentFacet.getMavenPathParser()).thenReturn(mavenPathParser);
    when(mavenPathParser.parsePath(anyString())).thenReturn(mock(MavenPath.class));
    when(mavenContentFacet.assets()).thenReturn(assets);
    when(mavenContentFacet.components()).thenReturn(components);

    Logger logger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
    logger.addAppender(mockAppender);
  }

  @AfterEach
  public void teardown() {
    Logger logger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
    logger.detachAppender(mockAppender);
  }

  /**
   * Tests that the rebuild operation is cancelable when executed on a Virtual Thread.
   * This ensures that long-running metadata rebuild operations can be safely interrupted
   * without leaving the system in an inconsistent state.
   */
  @Test
  public void rebuildIsCancelableWithVirtualThread() throws Exception {
    Component component = mock(Component.class);
    Asset asset = mock(Asset.class);
    doReturn(infiniteContinuation(component)).when(components).browse(anyInt(), anyString());
    doReturn(infiniteContinuation(asset)).when(assets).browse(anyInt(), anyString());

    final AtomicBoolean canceled = new AtomicBoolean(false);
    final List<Throwable> uncaught = new ArrayList<>();
    
    // Use a Virtual Thread for the task
    Thread taskThread = Thread.ofVirtual().name("metadata-rebuild-task").start(() -> {
      CancelableHelper.set(canceled);

      new MavenMetadataRebuilder(20, 10).rebuild(repository, true, false, true, null, null, null);
    });
    
    taskThread.setUncaughtExceptionHandler((t, e) -> {
      if (e instanceof TaskInterruptedException) {
        return;
      }

      uncaught.add(e);
    });

    sleep((long) (Math.random() * 1000)); // sleep for up to a second (emulate task running)
    canceled.set(true); // cancel the task
    taskThread.join(5000); // ensure task thread ends

    assertFalse(taskThread.isAlive(), "Task did not cancel properly when running on a Virtual Thread");

    if (!uncaught.isEmpty()) {
      fail("Unexpected exceptions during Virtual Thread execution: " + uncaught);
    }
  }

  /**
   * Tests that the rebuild operation is cancelable when executed on a Virtual Thread
   * with cascade disabled. This validates a specific configuration used in production.
   */
  @Test
  public void rebuildIsCancelableWithVirtualThread_CascadeDisabled() throws Exception {
    Component component = mock(Component.class);
    Asset asset = mock(Asset.class);
    doReturn(infiniteContinuation(component)).when(components).browse(anyInt(), anyString());
    doReturn(infiniteContinuation(asset)).when(assets).browse(anyInt(), anyString());

    final AtomicBoolean canceled = new AtomicBoolean(false);
    final List<Throwable> uncaught = new ArrayList<>();
    
    // Use a Virtual Thread for the task
    Thread taskThread = Thread.ofVirtual().name("metadata-rebuild-task-no-cascade").start(() -> {
      CancelableHelper.set(canceled);

      new MavenMetadataRebuilder(20, 10).rebuild(repository, true, false, false, "test_GroupId", "test_ArtifactId", null);
    });
    
    taskThread.setUncaughtExceptionHandler((t, e) -> {
      if (e instanceof TaskInterruptedException) {
        return;
      }

      uncaught.add(e);
    });

    sleep((long) (Math.random() * 1000)); // sleep for up to a second (emulate task running)
    canceled.set(true); // cancel the task
    taskThread.join(5000); // ensure task thread ends

    assertFalse(taskThread.isAlive(), "Task did not cancel properly when running on a Virtual Thread with cascade disabled");

    if (!uncaught.isEmpty()) {
      fail("Unexpected exceptions during Virtual Thread execution: " + uncaught);
    }
  }

  /**
   * Tests the complete GA (Group-Artifact) rebuild flow when executed on a Virtual Thread.
   * This validates that all steps of the metadata rebuild process function correctly
   * in a Virtual Thread environment.
   */
  @Test
  public void rebuild_GA_FlowWithVirtualThread() throws Exception {
    int bufferSize = 20;
    int maxThreads = 1;
    final String group1 = "group1";
    final String artifact1 = "artifact1";
    final String version1 = "1.0-SNAPSHOT";
    List<String> baseVersions = Collections.singletonList(version1);

    Content content = mock(Content.class);
    FluentComponent component = mock(FluentComponent.class);
    Continuation<FluentComponent> fluentComponents = new ContinuationArrayList<>();
    fluentComponents.add(component);
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(group1, artifact1)).thenReturn(baseVersions);
    when(mavenContentFacet.findComponentsForBaseVersion(anyInt(), eq(null), eq(group1), eq(artifact1), eq(version1)))
        .thenReturn(fluentComponents);

    MavenMetadataRebuilder mavenMetadataRebuilder = new MavenMetadataRebuilder(bufferSize, maxThreads);

    DatastoreMetadataUpdater metadataUpdaterSpy = Mockito.spy(new DatastoreMetadataUpdater(true, repository));
    MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, group1, artifact1, null, bufferSize);
    worker.setMetadataUpdater(metadataUpdaterSpy);
    MetadataRebuildWorker workerSpy = Mockito.spy(worker);

    doNothing().when(workerSpy).rebuildGroupMetadata(group1);
    doNothing().when(metadataUpdaterSpy).write(any(), any());

    // Execute the rebuild on a Virtual Thread
    Thread virtualThread = Thread.ofVirtual().name("ga-flow-test").start(() -> {
      mavenMetadataRebuilder.rebuildWithWorker(workerSpy, false, true, group1, artifact1, null);
    });
    
    virtualThread.join(10_000L); // Wait for completion with timeout
    assertFalse(virtualThread.isAlive(), "Virtual Thread did not complete in time");

    // Verify all expected methods were called
    verify(workerSpy, times(1)).rebuildGA(group1, artifact1);
    verify(workerSpy, times(1)).rebuildBaseVersionsAndChecksums(group1, artifact1, baseVersions, false);
    verify(workerSpy, times(1)).rebuildVersionsMetadata(group1, artifact1, baseVersions);
    verify(workerSpy, times(1)).rebuildArtifactMetadata(repository, group1, artifact1);

    MultipleFailures failures = worker.getFailures();
    assertThat(failures.size(), is(0));
  }

  /**
   * Tests the complete GA (Group-Artifact) rebuild flow for a non-SNAPSHOT version
   * when executed on a Virtual Thread. This validates that all steps of the metadata
   * rebuild process function correctly for release versions in a Virtual Thread environment.
   */
  @Test
  public void rebuild_GA_FlowWithVirtualThread_not_SNAPSHOT() throws Exception {
    int bufferSize = 20;
    int maxThreads = 1;
    final String group1 = "group1";
    final String artifact1 = "artifact1";
    final String version1 = "1.0";
    List<String> baseVersions = Collections.singletonList(version1);

    Content content = mock(Content.class);
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(group1, artifact1)).thenReturn(baseVersions);

    MavenMetadataRebuilder mavenMetadataRebuilder = new MavenMetadataRebuilder(bufferSize, maxThreads);

    DatastoreMetadataUpdater metadataUpdaterSpy = Mockito.spy(new DatastoreMetadataUpdater(true, repository));
    MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, group1, artifact1, null, bufferSize);
    worker.setMetadataUpdater(metadataUpdaterSpy);
    MetadataRebuildWorker workerSpy = Mockito.spy(worker);

    doNothing().when(workerSpy).rebuildGroupMetadata(group1);
    doNothing().when(metadataUpdaterSpy).write(any(), any());

    // Execute the rebuild on a Virtual Thread
    Thread virtualThread = Thread.ofVirtual().name("ga-flow-release-test").start(() -> {
      mavenMetadataRebuilder.rebuildWithWorker(workerSpy, false, true, group1, artifact1, null);
    });
    
    virtualThread.join(10_000L); // Wait for completion with timeout
    assertFalse(virtualThread.isAlive(), "Virtual Thread did not complete in time");

    // Verify all expected methods were called
    verify(workerSpy, times(1)).rebuildGA(group1, artifact1);
    verify(workerSpy, times(1)).rebuildBaseVersionsAndChecksums(group1, artifact1, baseVersions, false);
    verify(workerSpy, times(1)).rebuildVersionsMetadata(group1, artifact1, baseVersions);
    verify(workerSpy, times(1)).rebuildArtifactMetadata(repository, group1, artifact1);

    MultipleFailures failures = worker.getFailures();
    assertThat(failures.size(), is(0));
    
    // Verify the thread was actually a Virtual Thread
    assertTrue(virtualThread.isVirtual(), "Thread should be a Virtual Thread");
  }
  
  /**
   * Tests concurrent metadata rebuilds using many Virtual Threads to validate scalability.
   * This test creates a large number of Virtual Threads (1000+) to perform concurrent
   * metadata rebuilds, ensuring that the system can handle high concurrency efficiently.
   */
  @Test
  public void concurrentMetadataRebuildsWithManyVirtualThreads() throws Exception {
    // Setup test data
    final String group1 = "group1";
    final String artifact1 = "artifact1";
    final String version1 = "1.0";
    List<String> baseVersions = Collections.singletonList(version1);
    Content content = mock(Content.class);
    
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(anyString(), anyString())).thenReturn(baseVersions);
    
    // Create a Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("rebuild-worker-", 0).factory();
    
    // Create an executor service using Virtual Threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 1000; // Run 1000 concurrent rebuilds
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit tasks to rebuild metadata concurrently
      for (int i = 0; i < taskCount; i++) {
        final String groupId = "group" + (i % 10); // Use 10 different group IDs
        final String artifactId = "artifact" + (i % 20); // Use 20 different artifact IDs
        
        executor.submit(() -> {
          try {
            // Create a new rebuilder for each task
            MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
            
            // Create a worker for this specific GA coordinate
            MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, groupId, artifactId, null, 20);
            DatastoreMetadataUpdater updater = Mockito.spy(new DatastoreMetadataUpdater(true, repository));
            worker.setMetadataUpdater(updater);
            
            // Mock the actual rebuild operations to avoid real work
            doNothing().when(updater).write(any(), any());
            
            // Perform the rebuild
            rebuilder.rebuildWithWorker(worker, false, false, groupId, artifactId, null);
            
            // Check for failures
            if (worker.getFailures().size() > 0) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "Not all Virtual Thread tasks completed within the timeout period");
      assertThat("No errors should occur during concurrent Virtual Thread execution", 
          errorCount.get(), is(0));
    }
  }
  
  /**
   * Tests for thread pinning detection during metadata rebuild operations.
   * This test verifies that Virtual Threads don't get pinned to platform threads
   * during normal metadata rebuild operations, which would reduce the efficiency
   * of the Virtual Thread model.
   */
  @Test
  public void detectThreadPinningDuringMetadataRebuild() throws Exception {
    // Setup test data
    final String group1 = "group1";
    final String artifact1 = "artifact1";
    final String version1 = "1.0";
    List<String> baseVersions = Collections.singletonList(version1);
    Content content = mock(Content.class);
    
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(group1, artifact1)).thenReturn(baseVersions);
    
    // Create a rebuilder with a small buffer to increase I/O operations
    MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(5, 1);
    
    // Create a worker
    MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, group1, artifact1, null, 5);
    DatastoreMetadataUpdater updater = new DatastoreMetadataUpdater(true, repository);
    worker.setMetadataUpdater(updater);
    
    // Track execution time with Virtual Threads
    long startTime = System.currentTimeMillis();
    
    // Execute on a Virtual Thread
    Thread virtualThread = Thread.ofVirtual().name("pinning-detection-test").start(() -> {
      rebuilder.rebuildWithWorker(worker, false, true, group1, artifact1, null);
    });
    
    virtualThread.join(10_000L);
    long virtualThreadTime = System.currentTimeMillis() - startTime;
    
    // Now execute the same operation on a platform thread for comparison
    startTime = System.currentTimeMillis();
    
    Thread platformThread = new Thread(() -> {
      rebuilder.rebuildWithWorker(worker, false, true, group1, artifact1, null);
    });
    platformThread.start();
    platformThread.join(10_000L);
    
    long platformThreadTime = System.currentTimeMillis() - startTime;
    
    // In an efficient implementation without thread pinning, Virtual Threads should
    // not be significantly slower than platform threads for this operation
    // (they might even be faster due to reduced context switching overhead)
    assertThat("Virtual Thread execution time should not be significantly worse than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
    
    // Verify the thread was actually a Virtual Thread
    assertTrue(virtualThread.isVirtual(), "Thread should be a Virtual Thread");
    assertFalse(platformThread.isVirtual(), "Control thread should be a platform thread");
  }
  
  /**
   * Tests the performance characteristics of metadata rebuilds with many concurrent
   * Virtual Threads compared to platform threads. This test validates that Virtual Threads
   * provide better scalability and resource utilization for concurrent metadata operations.
   */
  @Test
  public void compareVirtualThreadVsPlatformThreadPerformance() throws Exception {
    // Setup test data
    final String group1 = "group1";
    final String artifact1 = "artifact1";
    final String version1 = "1.0";
    List<String> baseVersions = Collections.singletonList(version1);
    Content content = mock(Content.class);
    
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(anyString(), anyString())).thenReturn(baseVersions);
    
    // Parameters for the test
    int concurrentTasks = 100;
    CountDownLatch virtualThreadLatch = new CountDownLatch(concurrentTasks);
    CountDownLatch platformThreadLatch = new CountDownLatch(concurrentTasks);
    AtomicInteger virtualThreadErrors = new AtomicInteger(0);
    AtomicInteger platformThreadErrors = new AtomicInteger(0);
    
    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("virtual-rebuild-", 0).factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("platform-rebuild-", 0).factory();
    
    // Run with Virtual Threads
    long virtualStartTime = System.currentTimeMillis();
    try (ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      for (int i = 0; i < concurrentTasks; i++) {
        final String groupId = "group" + (i % 10);
        final String artifactId = "artifact" + (i % 20);
        
        virtualExecutor.submit(() -> {
          try {
            MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
            MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, groupId, artifactId, null, 20);
            DatastoreMetadataUpdater updater = Mockito.spy(new DatastoreMetadataUpdater(true, repository));
            worker.setMetadataUpdater(updater);
            doNothing().when(updater).write(any(), any());
            
            rebuilder.rebuildWithWorker(worker, false, false, groupId, artifactId, null);
          } 
          catch (Exception e) {
            virtualThreadErrors.incrementAndGet();
          } 
          finally {
            virtualThreadLatch.countDown();
          }
        });
      }
      
      virtualThreadLatch.await(30, TimeUnit.SECONDS);
    }
    long virtualThreadTime = System.currentTimeMillis() - virtualStartTime;
    
    // Run with Platform Threads
    long platformStartTime = System.currentTimeMillis();
    try (ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(platformThreadFactory)) {
      for (int i = 0; i < concurrentTasks; i++) {
        final String groupId = "group" + (i % 10);
        final String artifactId = "artifact" + (i % 20);
        
        platformExecutor.submit(() -> {
          try {
            MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
            MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, true, groupId, artifactId, null, 20);
            DatastoreMetadataUpdater updater = Mockito.spy(new DatastoreMetadataUpdater(true, repository));
            worker.setMetadataUpdater(updater);
            doNothing().when(updater).write(any(), any());
            
            rebuilder.rebuildWithWorker(worker, false, false, groupId, artifactId, null);
          } 
          catch (Exception e) {
            platformThreadErrors.incrementAndGet();
          } 
          finally {
            platformThreadLatch.countDown();
          }
        });
      }
      
      platformThreadLatch.await(30, TimeUnit.SECONDS);
    }
    long platformThreadTime = System.currentTimeMillis() - platformStartTime;
    
    // Verify results
    assertThat("Virtual Thread errors should be zero", virtualThreadErrors.get(), is(0));
    assertThat("Platform Thread errors should be zero", platformThreadErrors.get(), is(0));
    
    // Virtual Threads should be more efficient for concurrent I/O operations
    assertThat("Virtual Threads should complete faster than Platform Threads for concurrent operations",
        virtualThreadTime, lessThan(platformThreadTime));
    
    // Log the performance difference for analysis
    log.info("Performance comparison: Virtual Threads: {}ms, Platform Threads: {}ms, Improvement: {}%",
        virtualThreadTime, platformThreadTime, 
        Math.round((platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime));
  }

  private Continuation infiniteContinuation(final Object returnItem) {
    Continuation continuation = mock(Continuation.class);
    Iterator iterator = mock(Iterator.class);
    Spliterator spliterator = mock(Spliterator.class);

    when(continuation.spliterator()).thenReturn(spliterator);
    when(continuation.iterator()).thenReturn(iterator);
    when(iterator.hasNext()).thenReturn(true);
    when(iterator.next()).thenReturn(returnItem);

    return continuation;
  }

  private static class ContinuationArrayList<E>
      extends ArrayList<E>
      implements Continuation<E>
  {
    @Override
    public String nextContinuationToken() {
      return null;
    }
  }
}