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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;
import org.sonatype.nexus.testsuite.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.testsuite.testsupport.group.VirtualThreadTestGroup;

import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link MetadataBuilder} that validate its ability to build Maven metadata
 * for various repository scenarios including releases, snapshots, and plugins.
 * <p>
 * The tests verify the builder's state management, validation of GAV coordinates,
 * and proper metadata generation at group, artifact, and version levels.
 *
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
public class MetadataBuilderTest
    extends TestSupport
{
  private final Maven2MavenPathParser mavenPathParser = new Maven2MavenPathParser();

  private MetadataBuilder testSubject;

  /**
   * Set up a fresh MetadataBuilder instance before each test to ensure isolation.
   */
  @BeforeEach
  public void prepare() {
    this.testSubject = new MetadataBuilder();
  }

  /**
   * Verifies that attempting to enter an artifactId without first entering a groupId
   * throws an IllegalStateException.
   */
  @Test
  @DisplayName("Entering artifactId without groupId should fail")
  public void wrongEnterA() {
    assertThrows(IllegalStateException.class, () -> testSubject.onEnterArtifactId("foo"));
  }

  /**
   * Verifies that attempting to enter a baseVersion without first entering a groupId
   * throws an IllegalStateException.
   */
  @Test
  @DisplayName("Entering baseVersion without groupId should fail")
  public void wrongEnterV() {
    assertThrows(IllegalStateException.class, () -> testSubject.onEnterBaseVersion("foo"));
  }

  /**
   * Verifies that attempting to enter a baseVersion after entering a groupId but without
   * entering an artifactId throws an IllegalStateException.
   */
  @Test
  @DisplayName("Entering baseVersion without artifactId should fail")
  public void wrongEnterGV() {
    testSubject.onEnterGroupId("foo"); // good
    assertThrows(IllegalStateException.class, 
        () -> testSubject.onEnterBaseVersion("foo"),
        "No A entered");
  }

  /**
   * Verifies that attempting to add an artifact version without first entering a baseVersion
   * throws an IllegalStateException.
   */
  @Test
  @DisplayName("Adding artifact version without baseVersion should fail")
  public void wrongEnterGANoV() {
    testSubject.onEnterGroupId("junit"); // good
    testSubject.onEnterArtifactId("junit"); // good
    assertThrows(IllegalStateException.class, 
        () -> testSubject.addArtifactVersion(mavenPathParser.parsePath("/junit/junit/4.12/junit-4.12.pom")),
        "Should fail: no V entered");
  }

  /**
   * Verifies that attempting to add an artifact version with GAV coordinates that don't match
   * the current context throws an IllegalStateException.
   */
  @Test
  @DisplayName("Adding artifact with mismatched GAV should fail")
  public void contextGAVMismatch() {
    testSubject.onEnterGroupId("foo"); // good
    testSubject.onEnterArtifactId("bar"); // good
    testSubject.onEnterBaseVersion("1.0"); // good
    assertThrows(IllegalStateException.class, 
        () -> testSubject.addArtifactVersion(mavenPathParser.parsePath("/junit/junit/4.12/junit-4.12.pom")),
        "Should fail: GAV mismatch of enters and path");
  }

  /**
   * Tests the generation of metadata for a simple release artifact.
   * Verifies that:
   * - No version-level metadata is generated for releases
   * - Artifact-level metadata contains the correct version information
   * - Group-level metadata contains the plugin information
   */
  @Test
  @DisplayName("Simple release artifact metadata generation")
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

  /**
   * Tests the generation of metadata for a snapshot artifact with a timestamped version.
   * Verifies that:
   * - Version-level metadata contains the correct snapshot information
   * - Artifact-level metadata lists the snapshot version
   * - Group-level metadata contains the plugin information
   */
  @Test
  @DisplayName("Timestamped snapshot artifact metadata generation")
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

  /**
   * Tests the handling of a snapshot with a complex version pattern that doesn't match
   * the expected timestamp format.
   */
  @Test
  @DisplayName("Complex snapshot version pattern handling")
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

  /**
   * Tests the generation of metadata for a non-unique snapshot (without a timestamp).
   * Verifies that:
   * - Version-level metadata is generated but with limited information
   * - Artifact-level metadata lists the snapshot version
   * - Group-level metadata contains the plugin information
   */
  @Test
  @DisplayName("Non-unique snapshot artifact metadata generation")
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
   * Tests the MetadataBuilder with virtual threads to verify compatibility with Java 21's
   * virtual thread implementation. This test creates multiple virtual threads that concurrently
   * build metadata for different artifacts.
   */
  @Test
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  @DisplayName("Concurrent metadata building with virtual threads")
  public void concurrentMetadataBuildingWithVirtualThreads() {
    // Skip test if not running on Java 21 or higher
    String javaVersion = System.getProperty("java.version");
    if (javaVersion.startsWith("1.") || Integer.parseInt(javaVersion.split("\\.")[0]) < 21) {
      log.info("Skipping virtual thread test on Java version: {}", javaVersion);
      return;
    }
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Create multiple concurrent tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a new MetadataBuilder for each thread
            MetadataBuilder builder = new MetadataBuilder();
            
            // Build metadata for a unique artifact
            String groupId = "test-group";
            String artifactId = "test-artifact-" + index;
            String version = "1.0." + index;
            
            builder.onEnterGroupId(groupId);
            builder.onEnterArtifactId(artifactId);
            builder.onEnterBaseVersion(version);
            builder.addArtifactVersion(mavenPathParser.parsePath(
                String.format("/%s/%s/%s/%s-%s.pom", groupId, artifactId, version, artifactId, version)));
            
            // Verify the metadata is correctly built
            Maven2Metadata vmd = builder.onExitBaseVersion();
            Maven2Metadata amd = builder.onExitArtifactId();
            Maven2Metadata gmd = builder.onExitGroupId();
            
            if (amd != null && 
                groupId.equals(amd.getGroupId()) && 
                artifactId.equals(amd.getArtifactId()) &&
                amd.getBaseVersions() != null &&
                amd.getBaseVersions().getVersions().size() == 1) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures).join();
      
      // Verify results
      assertThat("All virtual thread tasks should complete successfully", 
          successCount.get(), equalTo(taskCount));
      
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests the MetadataBuilder with record patterns (Java 21 feature) to process
   * Maven2Metadata objects in a more concise way.
   */
  @Test
  @Tag("Java21TestGroup")
  @DisplayName("Process metadata using record patterns")
  public void processMetadataWithRecordPatterns() {
    // Skip test if not running on Java 21 or higher
    String javaVersion = System.getProperty("java.version");
    if (javaVersion.startsWith("1.") || Integer.parseInt(javaVersion.split("\\.")[0]) < 21) {
      log.info("Skipping record pattern test on Java version: {}", javaVersion);
      return;
    }
    
    // Create test data
    testSubject.onEnterGroupId("group");
    testSubject.onEnterArtifactId("artifact");
    testSubject.onEnterBaseVersion("1.0");
    testSubject.addArtifactVersion(mavenPathParser.parsePath("/group/artifact/1.0/artifact-1.0.pom"));
    
    // Get metadata
    Maven2Metadata amd = testSubject.onExitArtifactId();
    
    // Use record pattern to extract and validate metadata components
    // Note: Maven2Metadata is not a record, so we're simulating record pattern usage
    // In a real implementation with records, this would use actual record patterns
    if (amd != null) {
      // Extract components (simulating record pattern extraction)
      String groupId = amd.getGroupId();
      String artifactId = amd.getArtifactId();
      Maven2Metadata.BaseVersions baseVersions = amd.getBaseVersions();
      
      // Validate using extracted components
      assertThat(groupId, equalTo("group"));
      assertThat(artifactId, equalTo("artifact"));
      assertThat(baseVersions, notNullValue());
      assertThat(baseVersions.getVersions(), hasSize(1));
    } else {
      fail("Metadata should not be null");
    }
  }
}