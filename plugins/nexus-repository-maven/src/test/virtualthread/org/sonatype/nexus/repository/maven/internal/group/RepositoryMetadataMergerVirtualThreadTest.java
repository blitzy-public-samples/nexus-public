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
package org.sonatype.nexus.repository.maven.internal.group;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.group.RepositoryMetadataMerger.Envelope;
import org.sonatype.nexus.repository.view.Content;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link RepositoryMetadataMerger} using Java 21 Virtual Threads.
 * 
 * This test validates that the metadata merging operations function correctly
 * when executed in a highly concurrent environment using Virtual Threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
class RepositoryMetadataMergerVirtualThreadTest
    extends TestSupport
{
  @Mock
  OutputStream outputStream;

  @Mock
  MavenPath mavenPath;

  @Mock
  Repository repository;

  @Mock
  Content content;

  private RepositoryMetadataMerger merger;

  @BeforeEach
  void setUp() {
    merger = new RepositoryMetadataMerger();
  }

  /**
   * Creates a Plugin object with the given name.
   */
  private Plugin plugin(String name) {
    final Plugin p = new Plugin();
    p.setPrefix(name);
    p.setArtifactId(name + "-maven-plugin");
    p.setName("The " + name + " plugin");
    return p;
  }

  /**
   * Creates a group-level metadata object with the given plugin names.
   */
  private Metadata g(final String... pluginNames) {
    final Metadata m = new Metadata();
    for (String pluginName : pluginNames) {
      m.addPlugin(plugin(pluginName));
    }
    return m;
  }

  /**
   * Creates an artifact-level metadata object with the given parameters.
   */
  private Metadata a(final String groupId,
                     final String artifactId,
                     final String lastUpdated,
                     final String latest,
                     final String release,
                     final String... versions)
  {
    final Metadata m = new Metadata();
    m.setGroupId(groupId);
    m.setArtifactId(artifactId);
    m.setVersioning(new Versioning());
    final Versioning mv = m.getVersioning();
    if (!Strings.isNullOrEmpty(lastUpdated)) {
      mv.setLastUpdated(lastUpdated);
    }
    if (!Strings.isNullOrEmpty(latest)) {
      mv.setLatest(latest);
    }
    if (!Strings.isNullOrEmpty(release)) {
      mv.setRelease(release);
    }
    mv.getVersions().addAll(Arrays.asList(versions));
    return m;
  }

  /**
   * Creates a version-level metadata object with the given parameters.
   */
  private Metadata v(final String groupId,
                     final String artifactId,
                     final String versionPrefix,
                     final String timestamp,
                     final int buildNumber)
  {
    final Metadata m = new Metadata();
    m.setGroupId(groupId);
    m.setArtifactId(artifactId);
    m.setVersion(versionPrefix + "-SNAPSHOT");
    m.setVersioning(new Versioning());
    final Versioning mv = m.getVersioning();
    mv.setLastUpdated(timestamp.replace(".", ""));
    final Snapshot snapshot = new Snapshot();
    snapshot.setTimestamp(timestamp);
    snapshot.setBuildNumber(buildNumber);
    mv.setSnapshot(snapshot);
    final SnapshotVersion pom = new SnapshotVersion();
    pom.setExtension("pom");
    pom.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    pom.setUpdated(timestamp);
    mv.getSnapshotVersions().add(pom);

    final SnapshotVersion jar = new SnapshotVersion();
    jar.setExtension("jar");
    jar.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    jar.setUpdated(timestamp);
    mv.getSnapshotVersions().add(jar);

    final SnapshotVersion sources = new SnapshotVersion();
    sources.setExtension("jar");
    sources.setClassifier("sources");
    sources.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    sources.setUpdated(timestamp);
    mv.getSnapshotVersions().add(sources);

    return m;
  }

  /**
   * Tests that metadata equality checks work correctly with Virtual Threads.
   */
  @Test
  void metadataEqualityChecksWorkWithVirtualThreads() throws Exception {
    // Create test metadata objects
    final Metadata nullClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);
    final Metadata emptyClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);
    final Metadata spaceClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);

    nullClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier(null);
    emptyClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier("");
    spaceClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier("   ");

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test equality checks concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Verify equality checks work correctly
            if (!merger.metadataEquals(nullClassifier, emptyClassifier) ||
                !merger.metadataEquals(emptyClassifier, spaceClassifier) ||
                !merger.metadataEquals(spaceClassifier, nullClassifier)) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that group-level metadata merging works correctly with Virtual Threads.
   */
  @Test
  void groupLevelMetadataMergingWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    final Metadata m1 = g("foo");
    final Metadata m2 = g("foo", "bar");
    final Metadata m3 = g("baz");

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m, notNullValue());
      assertThat(m.getModelVersion(), equalTo("1.1.0"));
      assertThat(m.getPlugins(), hasSize(3));

      // Verify all plugins are present
      List<String> artifactIds = Lists.newArrayList();
      for (Plugin plugin : m.getPlugins()) {
        artifactIds.add(plugin.getArtifactId());
      }
      assertThat(artifactIds, containsInAnyOrder("foo-maven-plugin", "bar-maven-plugin", "baz-maven-plugin"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that artifact-level metadata merging works correctly with Virtual Threads.
   */
  @Test
  void artifactLevelMetadataMergingWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    final Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.1", "1.0.1", "1.0.0", "1.0.1");
    final Metadata m2 = a("org.foo", "some-project", "20150324121700", "1.0.2", "1.0.2", "1.0.2");
    final Metadata m3 = a("org.foo", "some-project", "20150324121600", "1.1.0-SNAPSHOT", null, "1.1.0-SNAPSHOT");

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m, notNullValue());
      assertThat(m.getModelVersion(), equalTo("1.1.0"));
      assertThat(m.getGroupId(), equalTo("org.foo"));
      assertThat(m.getArtifactId(), equalTo("some-project"));
      assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121700"));
      assertThat(m.getVersioning().getSnapshot(), nullValue());
      assertThat(m.getVersioning().getRelease(), equalTo("1.0.2"));
      assertThat(m.getVersioning().getLatest(), equalTo("1.1.0-SNAPSHOT"));
      assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1", "1.0.2", "1.1.0-SNAPSHOT"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that version-level metadata merging works correctly with Virtual Threads.
   */
  @Test
  void versionLevelMetadataMergingWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    final Metadata m1 = v("org.foo", "some-project", "1.0.0", "20150324.121500", 3);
    final Metadata m2 = v("org.foo", "some-project", "1.0.0", "20150323.121500", 2);
    final Metadata m3 = v("org.foo", "some-project", "1.0.0", "20150322.121500", 1);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m, notNullValue());
      assertThat(m.getModelVersion(), equalTo("1.1.0"));
      assertThat(m.getGroupId(), equalTo("org.foo"));
      assertThat(m.getArtifactId(), equalTo("some-project"));
      assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121500"));
      assertThat(m.getVersioning().getSnapshot(), notNullValue());
      assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150324.121500"));
      assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(3));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that mixed-level metadata merging works correctly with Virtual Threads.
   */
  @Test
  void mixedLevelMetadataMergingWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    final Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.1", "1.0.1", "1.0.0", "1.0.1");
    final Metadata m2 = g("foo", "bar");
    final Metadata m3 = v("org.foo", "some-project", "1.1.0", "20150322.121500", 3);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m, notNullValue());
      assertThat(m.getModelVersion(), equalTo("1.1.0"));
      assertThat(m.getGroupId(), equalTo("org.foo"));
      assertThat(m.getArtifactId(), equalTo("some-project"));
      assertThat(m.getVersion(), equalTo("1.1.0-SNAPSHOT"));
      assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121500"));
      assertThat(m.getVersioning().getSnapshot(), notNullValue());
      assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150322.121500"));
      assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(3));
      assertThat(m.getVersioning().getRelease(), equalTo("1.0.1"));
      assertThat(m.getVersioning().getLatest(), equalTo("1.0.1"));
      assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1"));
      assertThat(m.getPlugins(), hasSize(2));

      // Verify all plugins are present
      List<String> artifactIds = Lists.newArrayList();
      for (Plugin plugin : m.getPlugins()) {
        artifactIds.add(plugin.getArtifactId());
      }
      assertThat(artifactIds, containsInAnyOrder("foo-maven-plugin", "bar-maven-plugin"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that null snapshot timestamp handling works correctly with Virtual Threads.
   */
  @Test
  void nullSnapshotTimestampHandlingWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    Metadata m1 = v("org.foo", "some-project", "1.0.0", "20150322.121500", 1);
    m1.getVersioning().getSnapshot().setTimestamp(null);
    Metadata m2 = v("org.foo", "some-project", "1.0.0", "20150323.121500", 2);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m, notNullValue());
      assertThat(m.getModelVersion(), equalTo("1.1.0"));
      assertThat(m.getGroupId(), equalTo("org.foo"));
      assertThat(m.getArtifactId(), equalTo("some-project"));
      assertThat(m.getVersioning().getLastUpdated(), equalTo("20150323121500"));
      assertThat(m.getVersioning().getSnapshot(), notNullValue());
      assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150323.121500"));
      assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(2));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that version in artifact-level metadata handling works correctly with Virtual Threads.
   */
  @Test
  void versionInArtifactLevelMetadataWorksWithVirtualThreads() throws Exception {
    // Create test metadata objects
    Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.0", "1.0.0", "1.0.0");
    m1.setVersion("1.0.0");
    Metadata m2 = a("org.foo", "some-project", "20150324121501", "1.0.1", "1.0.1", "1.0.1");
    m2.setVersion("1.0.1");

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Test merging concurrently with virtual threads
      int taskCount = 1000;
      CountDownLatch latch = new CountDownLatch(taskCount);
      List<Metadata> results = new ArrayList<>(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform the merge operation
            Metadata merged = merger.merge(
                ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2))
            );
            synchronized (results) {
              results.add(merged);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
      assertThat(results, hasSize(taskCount));

      // Verify the first result (all should be identical)
      Metadata m = results.get(0);
      assertThat(m.getVersion(), is(m1.getVersion())); // target version is left intact, no attempt to merge
      assertThat(m.getVersioning().getRelease(), is(m2.getVersion()));
      assertThat(m.getVersioning().getLastUpdated(), is(m2.getVersioning().getLastUpdated()));
      assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1"));
    } finally {
      executor.shutdown();
    }
  }
}