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
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.common.MultipleFailures;
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
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThread")
public class MavenMetadataRebuilderVirtualThreadTest
    extends VirtualThreadTestSupport
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

  private ThreadPinningDetector pinningDetector;

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
    
    pinningDetector = new ThreadPinningDetector();
  }

  @AfterEach
  public void teardown() {
    Logger logger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
    logger.detachAppender(mockAppender);
  }

  @Test
  public void rebuildIsCancelableWithVirtualThreads() throws Exception {
    Component component = mock(Component.class);
    Asset asset = mock(Asset.class);
    doReturn(infiniteContinuation(component)).when(components).browse(anyInt(), anyString());
    doReturn(infiniteContinuation(asset)).when(assets).browse(anyInt(), anyString());

    final AtomicBoolean canceled = new AtomicBoolean(false);
    final List<Throwable> uncaught = new ArrayList<>();
    
    // Use a virtual thread instead of a platform thread
    Thread taskThread = Thread.ofVirtual().name("rebuild-task").start(() -> {
      CancelableHelper.set(canceled);

      new MavenMetadataRebuilder(20, 10).rebuild(repository, true, false, true, null, null, null);
    });
    
    taskThread.setUncaughtExceptionHandler((t, e) -> {
      if (e instanceof TaskInterruptedException) {
        return;
      }

      uncaught.add(e);
    });

    // Sleep for up to a second (emulate task running)
    Thread.sleep((long) (Math.random() * 1000)); 
    canceled.set(true); // cancel the task
    taskThread.join(5000); // ensure task thread ends

    if (taskThread.isAlive()) {
      fail("Task did not cancel");
    }

    if (!uncaught.isEmpty()) {
      fail("Unexpected exceptions: " + uncaught);
    }
  }

  @Test
  public void rebuildIsCancelable_CascadeDisabled_WithVirtualThreads() throws Exception {
    Component component = mock(Component.class);
    Asset asset = mock(Asset.class);
    doReturn(infiniteContinuation(component)).when(components).browse(anyInt(), anyString());
    doReturn(infiniteContinuation(asset)).when(assets).browse(anyInt(), anyString());

    final AtomicBoolean canceled = new AtomicBoolean(false);
    final List<Throwable> uncaught = new ArrayList<>();
    
    // Use a virtual thread instead of a platform thread
    Thread taskThread = Thread.ofVirtual().name("rebuild-task-no-cascade").start(() -> {
      CancelableHelper.set(canceled);

      new MavenMetadataRebuilder(20, 10).rebuild(repository, true, false, false, "test_GroupId", "test_ArtifactId", null);
    });
    
    taskThread.setUncaughtExceptionHandler((t, e) -> {
      if (e instanceof TaskInterruptedException) {
        return;
      }

      uncaught.add(e);
    });

    // Sleep for up to a second (emulate task running)
    Thread.sleep((long) (Math.random() * 1000)); 
    canceled.set(true); // cancel the task
    taskThread.join(5000); // ensure task thread ends

    if (taskThread.isAlive()) {
      fail("Task did not cancel");
    }

    if (!uncaught.isEmpty()) {
      fail("Unexpected exceptions: " + uncaught);
    }
  }

  @Test
  public void rebuild_GA_Flow_WithVirtualThreads() throws Exception {
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

    // Use a virtual thread to run the rebuild
    Thread.ofVirtual().name("rebuild-ga-flow").start(() -> {
      mavenMetadataRebuilder.rebuildWithWorker(workerSpy, false, true, group1, artifact1, null);
    }).join();

    verify(workerSpy, times(1)).rebuildGA(group1, artifact1);
    verify(workerSpy, times(1)).rebuildBaseVersionsAndChecksums(group1, artifact1, baseVersions, false);
    verify(workerSpy, times(1)).rebuildVersionsMetadata(group1, artifact1, baseVersions);
    verify(workerSpy, times(1)).rebuildArtifactMetadata(repository, group1, artifact1);

    MultipleFailures failures = worker.getFailures();
    assertThat(failures.size(), is(0));
  }

  @Test
  public void rebuild_GA_Flow_not_SNAPSHOT_WithVirtualThreads() throws Exception {
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

    // Use a virtual thread to run the rebuild
    Thread.ofVirtual().name("rebuild-ga-flow-not-snapshot").start(() -> {
      mavenMetadataRebuilder.rebuildWithWorker(workerSpy, false, true, group1, artifact1, null);
    }).join();

    verify(workerSpy, times(1)).rebuildGA(group1, artifact1);
    verify(workerSpy, times(1)).rebuildBaseVersionsAndChecksums(group1, artifact1, baseVersions, false);
    verify(workerSpy, times(1)).rebuildVersionsMetadata(group1, artifact1, baseVersions);
    verify(workerSpy, times(1)).rebuildArtifactMetadata(repository, group1, artifact1);

    MultipleFailures failures = worker.getFailures();
    assertThat(failures.size(), is(0));
  }
  
  @Test
  public void testThreadPinningDuringRebuild() throws Exception {
    Component component = mock(Component.class);
    Asset asset = mock(Asset.class);
    doReturn(infiniteContinuation(component)).when(components).browse(anyInt(), anyString());
    doReturn(infiniteContinuation(asset)).when(assets).browse(anyInt(), anyString());
    
    // Start pinning detection
    pinningDetector.start();
    
    try {
      // Run a short rebuild operation with a virtual thread
      Thread.ofVirtual().name("pinning-test-thread").start(() -> {
        new MavenMetadataRebuilder(20, 10).rebuild(repository, true, false, false, "test_GroupId", "test_ArtifactId", null);
      }).join(5000);
      
      // Check if any pinning was detected
      assertFalse(pinningDetector.hasPinningEvents(), 
          "Thread pinning detected during metadata rebuild. This could impact performance with Virtual Threads.");
    }
    finally {
      pinningDetector.stop();
    }
  }
  
  @Test
  public void testConcurrentRebuildsWithManyVirtualThreads() throws Exception {
    // Configure mocks for a simple rebuild operation
    Content content = mock(Content.class);
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(anyString(), anyString())).thenReturn(Collections.singletonList("1.0"));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("rebuild-", 0).factory();
    
    // Number of concurrent rebuilds to run
    int concurrentRebuilds = 1000;
    
    // Create a countdown latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentRebuilds);
    
    // Create an executor service with virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Track any exceptions
    AtomicInteger exceptionCount = new AtomicInteger(0);
    List<Future<?>> futures = new ArrayList<>();
    
    // Submit rebuild tasks
    for (int i = 0; i < concurrentRebuilds; i++) {
      final String groupId = "group" + (i % 10); // Use 10 different group IDs
      final String artifactId = "artifact" + (i % 20); // Use 20 different artifact IDs
      
      futures.add(executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Create a new rebuilder for each task
          MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
          rebuilder.rebuild(repository, false, false, false, groupId, artifactId, null);
          
          return null;
        }
        catch (Exception e) {
          exceptionCount.incrementAndGet();
          throw new RuntimeException(e);
        }
        finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start all threads at once
    startLatch.countDown();
    
    // Wait for completion with timeout
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
      boolean completed = completionLatch.await(25, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No exceptions should occur during concurrent rebuilds", exceptionCount.get(), is(0));
    });
    
    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }
  
  @Test
  public void testVirtualThreadPerformance() throws Exception {
    // Configure mocks for a simple rebuild operation
    Content content = mock(Content.class);
    when(mavenContentFacet.get(nullable(MavenPath.class))).thenReturn(Optional.of(content));
    when(mavenContentFacet.getBaseVersions(anyString(), anyString())).thenReturn(Collections.singletonList("1.0"));
    
    // Number of rebuilds to run
    int rebuildsCount = 100;
    
    // Run with virtual threads
    long startTimeVirtual = System.currentTimeMillis();
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    CountDownLatch virtualLatch = new CountDownLatch(rebuildsCount);
    for (int i = 0; i < rebuildsCount; i++) {
      final String groupId = "group" + (i % 10);
      final String artifactId = "artifact" + (i % 20);
      
      virtualExecutor.submit(() -> {
        try {
          MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
          rebuilder.rebuild(repository, false, false, false, groupId, artifactId, null);
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(10, TimeUnit.SECONDS);
    virtualExecutor.shutdown();
    long virtualThreadTime = System.currentTimeMillis() - startTimeVirtual;
    
    // Run with platform threads (limited pool)
    long startTimePlatform = System.currentTimeMillis();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(10); // Limited to 10 threads
    
    CountDownLatch platformLatch = new CountDownLatch(rebuildsCount);
    for (int i = 0; i < rebuildsCount; i++) {
      final String groupId = "group" + (i % 10);
      final String artifactId = "artifact" + (i % 20);
      
      platformExecutor.submit(() -> {
        try {
          MavenMetadataRebuilder rebuilder = new MavenMetadataRebuilder(20, 1);
          rebuilder.rebuild(repository, false, false, false, groupId, artifactId, null);
        }
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    platformLatch.await(10, TimeUnit.SECONDS);
    platformExecutor.shutdown();
    long platformThreadTime = System.currentTimeMillis() - startTimePlatform;
    
    // Virtual threads should be more efficient with many concurrent tasks
    // This is especially true for I/O bound operations like metadata rebuilds
    assertThat("Virtual threads should complete faster than limited platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
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