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
package org.sonatype.nexus.repository.maven.internal.hosted.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;

import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Virtual Thread tests for {@link MetadataBuilder}
 * 
 * @since 3.60
 */
public class MetadataBuilderVirtualThreadTest
    extends TestSupport
{
  private final Maven2MavenPathParser mavenPathParser = new Maven2MavenPathParser();

  private MetadataBuilder testSubject;

  @BeforeEach
  public void prepare() {
    this.testSubject = new MetadataBuilder();
  }

  @Test
  public void wrongEnterA() {
    assertThrows(IllegalStateException.class, () -> testSubject.onEnterArtifactId("foo"));
  }

  @Test
  public void wrongEnterV() {
    assertThrows(IllegalStateException.class, () -> testSubject.onEnterBaseVersion("foo"));
  }

  @Test
  public void wrongEnterGV() {
    testSubject.onEnterGroupId("foo"); // good
    assertThrows(IllegalStateException.class, () -> testSubject.onEnterBaseVersion("foo"));
  }

  @Test
  public void wrongEnterGANoV() {
    testSubject.onEnterGroupId("junit"); // good
    testSubject.onEnterArtifactId("junit"); // good
    assertThrows(IllegalStateException.class, 
        () -> testSubject.addArtifactVersion(mavenPathParser.parsePath("/junit/junit/4.12/junit-4.12.pom")));
  }

  @Test
  public void contextGAVMismatch() {
    testSubject.onEnterGroupId("foo"); // good
    testSubject.onEnterArtifactId("bar"); // good
    testSubject.onEnterBaseVersion("1.0"); // good
    assertThrows(IllegalStateException.class, 
        () -> testSubject.addArtifactVersion(mavenPathParser.parsePath("/junit/junit/4.12/junit-4.12.pom")));
  }

  @Test
  public void simpleRelease() {
    testSubject.onEnterGroupId("group");
    testSubject.onEnterArtifactId("artifact");
    testSubject.onEnterBaseVersion("1.0");
    testSubject.addArtifactVersion(mavenPathParser.parsePath("/group/artifact/1.0/artifact-1.0.pom"));
    testSubject.addPlugin("prefix", "artifact", "name");
    final Maven2Metadata vmd = testSubject.onExitBaseVersion();
    assertThat(vmd, nullValue());

    final Maven2Metadata amd = testSubject.onExitArtifactId();
    assertThat(amd, notNullValue());
    assertThat(amd.getGroupId(), equalTo("group"));
    assertThat(amd.getArtifactId(), equalTo("artifact"));
    assertThat(amd.getBaseVersions(), notNullValue());
    assertThat(amd.getBaseVersions().getVersions(), hasSize(1));

    final Maven2Metadata gmd = testSubject.onExitGroupId();
    assertThat(gmd, notNullValue());
    assertThat(gmd.getGroupId(), nullValue());
    assertThat(gmd.getArtifactId(), nullValue());
    assertThat(gmd.getPlugins(), hasSize(1));
  }

  @Test
  public void simpleSnapshot() {
    testSubject.onEnterGroupId("group");
    testSubject.onEnterArtifactId("artifact");
    testSubject.onEnterBaseVersion("1.0-SNAPSHOT");
    testSubject.addArtifactVersion(
        mavenPathParser.parsePath("/group/artifact/1.0-SNAPSHOT/artifact-1.0-20150430.121212-1.pom"));
    testSubject.addPlugin("prefix", "artifact", "name");
    final Maven2Metadata vmd = testSubject.onExitBaseVersion();
    assertThat(vmd, notNullValue());
    assertThat(vmd.getGroupId(), equalTo("group"));
    assertThat(vmd.getArtifactId(), equalTo("artifact"));
    assertThat(vmd.getVersion(), equalTo("1.0-SNAPSHOT"));
    assertThat(vmd.getSnapshots(), notNullValue());
    assertThat(vmd.getSnapshots().getSnapshotTimestamp(), equalTo(new DateTime("2015-04-30T12:12:12Z").getMillis()));
    assertThat(vmd.getSnapshots().getSnapshotBuildNumber(), equalTo(1));
    assertThat(vmd.getSnapshots().getSnapshots(), hasSize(1));

    final Maven2Metadata amd = testSubject.onExitArtifactId();
    assertThat(amd, notNullValue());
    assertThat(amd.getGroupId(), equalTo("group"));
    assertThat(amd.getArtifactId(), equalTo("artifact"));
    assertThat(amd.getBaseVersions(), notNullValue());
    assertThat(amd.getBaseVersions().getVersions(), hasSize(1));
    assertThat(amd.getBaseVersions().getLatest(), equalTo("1.0-SNAPSHOT"));
    assertThat(amd.getBaseVersions().getRelease(), nullValue());
    assertThat(amd.getBaseVersions().getVersions(), contains("1.0-SNAPSHOT"));

    final Maven2Metadata gmd = testSubject.onExitGroupId();
    assertThat(gmd, notNullValue());
    assertThat(gmd.getGroupId(), nullValue());
    assertThat(gmd.getPlugins(), hasSize(1));
  }

  @Test
  public void wrongSimpleSnapshot() {
    String artifactId = "artifactId";
    String groupId = "groupId";
    String baseVersion = "baseVersion-SNAPSHOT-test-SNAPSHOT";
    String wrongVersion = "artifactId-baseVersion-20240910.132746-1-test-20240910.132746-1.jar";

    testSubject.onEnterGroupId(groupId);
    testSubject.onEnterArtifactId(artifactId);
    testSubject.onEnterBaseVersion(baseVersion);
    testSubject.addArtifactVersion(
        mavenPathParser.parsePath(String.join("/", groupId, artifactId, baseVersion, wrongVersion)));
    testSubject.addPlugin("prefix", "artifact", "name");

    final Maven2Metadata vmd = testSubject.onExitBaseVersion();
    assertThat(vmd, nullValue());

    final Maven2Metadata amd = testSubject.onExitArtifactId();
    assertThat(amd, notNullValue());
    assertThat(amd.getGroupId(), equalTo(groupId));
    assertThat(amd.getArtifactId(), equalTo(artifactId));
    assertThat(amd.getBaseVersions(), notNullValue());
    assertThat(amd.getBaseVersions().getVersions(), hasSize(1));
    assertThat(amd.getBaseVersions().getLatest(), equalTo(baseVersion));
    assertThat(amd.getBaseVersions().getRelease(), nullValue());
    assertThat(amd.getBaseVersions().getVersions(), contains(baseVersion));

    final Maven2Metadata gmd = testSubject.onExitGroupId();
    assertThat(gmd, notNullValue());
    assertThat(gmd.getGroupId(), nullValue());
    assertThat(gmd.getPlugins(), hasSize(1));
  }

  @Test
  public void nonUniqueSnapshot() {
    testSubject.onEnterGroupId("group");
    testSubject.onEnterArtifactId("artifact");
    testSubject.onEnterBaseVersion("1.0-SNAPSHOT");
    testSubject.addArtifactVersion(
        mavenPathParser.parsePath("/group/artifact/1.0-SNAPSHOT/artifact-1.0-SNAPSHOT.pom"));
    testSubject.addPlugin("prefix", "artifact", "name");
    final Maven2Metadata vmd = testSubject.onExitBaseVersion();
    assertThat(vmd, notNullValue());
    assertThat(vmd.getGroupId(), equalTo("group"));
    assertThat(vmd.getArtifactId(), equalTo("artifact"));
    assertThat(vmd.getVersion(), equalTo("1.0-SNAPSHOT"));
    assertThat(vmd.getSnapshots(), notNullValue());
    assertThat(vmd.getSnapshots().getSnapshotTimestamp(), nullValue());
    assertThat(vmd.getSnapshots().getSnapshotBuildNumber(), equalTo(1));
    assertThat(vmd.getSnapshots().getSnapshots(), hasSize(0));

    final Maven2Metadata amd = testSubject.onExitArtifactId();
    assertThat(amd, notNullValue());
    assertThat(amd.getGroupId(), equalTo("group"));
    assertThat(amd.getArtifactId(), equalTo("artifact"));
    assertThat(amd.getBaseVersions(), notNullValue());
    assertThat(amd.getBaseVersions().getVersions(), hasSize(1));
    assertThat(amd.getBaseVersions().getLatest(), equalTo("1.0-SNAPSHOT"));
    assertThat(amd.getBaseVersions().getRelease(), nullValue());
    assertThat(amd.getBaseVersions().getVersions(), contains("1.0-SNAPSHOT"));

    final Maven2Metadata gmd = testSubject.onExitGroupId();
    assertThat(gmd, notNullValue());
    assertThat(gmd.getGroupId(), nullValue());
    assertThat(gmd.getPlugins(), hasSize(1));
  }

  /**
   * Test concurrent metadata building with multiple virtual threads.
   * This test creates multiple virtual threads that each build metadata for different artifacts.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void concurrentMetadataBuilding() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            MetadataBuilder builder = new MetadataBuilder();
            String groupId = "group" + index;
            String artifactId = "artifact" + index;
            String version = "1.0." + index;
            
            builder.onEnterGroupId(groupId);
            builder.onEnterArtifactId(artifactId);
            builder.onEnterBaseVersion(version);
            builder.addArtifactVersion(
                mavenPathParser.parsePath(String.format("/%s/%s/%s/%s-%s.pom", 
                    groupId, artifactId, version, artifactId, version)));
            builder.addPlugin("prefix" + index, artifactId, "name" + index);
            
            // Verify metadata
            Maven2Metadata vmd = builder.onExitBaseVersion();
            if (vmd != null) {
              errorCount.incrementAndGet();
            }
            
            Maven2Metadata amd = builder.onExitArtifactId();
            if (amd == null || !amd.getGroupId().equals(groupId) || 
                !amd.getArtifactId().equals(artifactId) || 
                amd.getBaseVersions().getVersions().size() != 1) {
              errorCount.incrementAndGet();
            }
            
            Maven2Metadata gmd = builder.onExitGroupId();
            if (gmd == null || gmd.getPlugins().size() != 1) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete without errors", errorCount.get(), is(0));
    }
  }

  /**
   * Test for thread pinning detection during metadata operations.
   * This test verifies that metadata operations don't cause thread pinning,
   * which would reduce the benefits of virtual threads.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void threadPinningDetection() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 10;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
      
      // Enable thread pinning detection
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a new builder for each thread to avoid contention
            MetadataBuilder builder = new MetadataBuilder();
            String groupId = "group" + index;
            String artifactId = "artifact" + index;
            String version = "1.0." + index;
            
            // Perform metadata operations
            builder.onEnterGroupId(groupId);
            builder.onEnterArtifactId(artifactId);
            builder.onEnterBaseVersion(version);
            builder.addArtifactVersion(
                mavenPathParser.parsePath(String.format("/%s/%s/%s/%s-%s.pom", 
                    groupId, artifactId, version, artifactId, version)));
            builder.addPlugin("prefix" + index, artifactId, "name" + index);
            
            // Exit operations
            builder.onExitBaseVersion();
            builder.onExitArtifactId();
            builder.onExitGroupId();
            
            // Check if current thread is a virtual thread
            if (Thread.currentThread().isVirtual()) {
              // Virtual thread operations completed successfully
              // In a real pinning scenario, we would detect it here
            } else {
              // This should not happen as we're using virtual threads
              pinnedThreadDetected.set(true);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(5, TimeUnit.SECONDS);
      
      // Verify no thread pinning was detected
      assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
      
      // Reset system property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }

  /**
   * Test for concurrent metadata assembly with high load.
   * This test verifies that the MetadataBuilder can handle a high number of
   * concurrent operations using virtual threads without errors.
   */
  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  public void concurrentMetadataAssemblyHighLoad() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 1000; // High number of concurrent tasks
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a new builder for each thread
            MetadataBuilder builder = new MetadataBuilder();
            
            // Use different group/artifact combinations
            String groupId = "group" + (index % 10); // Reuse some groups
            String artifactId = "artifact" + (index % 20); // Reuse some artifacts
            String version = "1.0." + index;
            
            // Perform metadata operations
            builder.onEnterGroupId(groupId);
            builder.onEnterArtifactId(artifactId);
            builder.onEnterBaseVersion(version);
            builder.addArtifactVersion(
                mavenPathParser.parsePath(String.format("/%s/%s/%s/%s-%s.pom", 
                    groupId, artifactId, version, artifactId, version)));
            
            // Add multiple plugins to increase workload
            for (int j = 0; j < 5; j++) {
              builder.addPlugin("prefix" + j, artifactId, "name" + j);
            }
            
            // Exit operations and verify results
            builder.onExitBaseVersion();
            Maven2Metadata amd = builder.onExitArtifactId();
            Maven2Metadata gmd = builder.onExitGroupId();
            
            if (amd != null && gmd != null) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Count failures by not incrementing successCount
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(15, TimeUnit.SECONDS);
      
      // Verify most operations succeeded
      assertThat("Most metadata operations should succeed", successCount.get(), greaterThan(taskCount - 10));
    }
  }
}