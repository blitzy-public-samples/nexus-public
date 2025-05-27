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
package org.sonatype.nexus.repository.maven.internal;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenPath.SignatureType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Category;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_MAIN_CHUNK_FILE_PATH;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_PROPERTY_FILE_PATH;

/**
 * Virtual Thread tests for {@link Maven2MavenPathParser}
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class Maven2MavenPathParserVirtualThreadTest
    extends TestSupport
{
  private Maven2MavenPathParser pathParser;

  @BeforeEach
  public void setUp() {
    pathParser = new Maven2MavenPathParser();
  }

  private long parseTimestamp(final String ts) throws ParseException {
    final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd.HHmmss");
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.parse(ts).getTime();
  }

  /**
   * Tests concurrent parsing of artifact paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentArtifactParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Parse a complex Maven path
            MavenPath mavenPath = pathParser.parsePath(
                "/org/apache/maven/artifact/maven-artifact/3.0-SNAPSHOT/maven-artifact-3.0-20080411.005221-75-some.strange.classifier.pom.asc.sha1");
            
            // Verify the parsed path is correct
            assertThat(mavenPath, notNullValue());
            assertThat(mavenPath.getPath(), equalTo(
                "org/apache/maven/artifact/maven-artifact/3.0-SNAPSHOT/maven-artifact-3.0-20080411.005221-75-some.strange.classifier.pom.asc.sha1"));
            assertThat(mavenPath.getFileName(),
                equalTo("maven-artifact-3.0-20080411.005221-75-some.strange.classifier.pom.asc.sha1"));
            assertThat(mavenPath.getHashType(), equalTo(HashType.SHA1));
            assertThat(mavenPath.getCoordinates(), notNullValue());
            assertThat(mavenPath.getCoordinates().getGroupId(), equalTo("org.apache.maven.artifact"));
            assertThat(mavenPath.getCoordinates().getArtifactId(), equalTo("maven-artifact"));
            assertThat(mavenPath.getCoordinates().getVersion(), equalTo("3.0-20080411.005221-75"));
            assertThat(mavenPath.getCoordinates().getBaseVersion(), equalTo("3.0-SNAPSHOT"));
            assertThat(mavenPath.getCoordinates().getClassifier(), equalTo("some.strange.classifier"));
            assertThat(mavenPath.getCoordinates().getExtension(), equalTo("pom.asc.sha1"));
            assertThat(mavenPath.getCoordinates().getSignatureType(), equalTo(SignatureType.GPG));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent parsing", errorCount.get(), equalTo(0));
    }
  }

  /**
   * Tests concurrent parsing of snapshot paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentSnapshotParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Parse a snapshot path
            MavenPath mavenPath = pathParser.parsePath(
                "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-25-javadoc.jar");
            
            // Verify the parsed path is correct
            assertThat(mavenPath, notNullValue());
            assertThat(mavenPath.getPath(), equalTo(
                "org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-25-javadoc.jar"));
            assertThat(mavenPath.getFileName(), equalTo("jruby-1.0RC1-20070504.160758-25-javadoc.jar"));
            assertThat(mavenPath.getHashType(), nullValue());
            assertThat(mavenPath.getCoordinates(), notNullValue());
            assertThat(mavenPath.getCoordinates().getGroupId(), equalTo("org.jruby"));
            assertThat(mavenPath.getCoordinates().getArtifactId(), equalTo("jruby"));
            assertThat(mavenPath.getCoordinates().getVersion(), equalTo("1.0RC1-20070504.160758-25"));
            assertThat(mavenPath.getCoordinates().getBaseVersion(), equalTo("1.0RC1-SNAPSHOT"));
            assertThat(mavenPath.getCoordinates().getClassifier(), equalTo("javadoc"));
            assertThat(mavenPath.getCoordinates().getExtension(), equalTo("jar"));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent snapshot parsing", errorCount.get(), equalTo(0));
    }
  }

  /**
   * Tests concurrent parsing of metadata paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentMetadataParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Parse a metadata path
            MavenPath mavenPath = pathParser.parsePath("/org/jruby/jruby/1.0-SNAPSHOT/maven-metadata.xml");
            
            // Verify the parsed path is correct
            assertThat(mavenPath.getCoordinates(), nullValue());
            assertThat(pathParser.isRepositoryMetadata(mavenPath), equalTo(true));
            assertThat(pathParser.isRepositoryIndex(mavenPath), equalTo(false));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent metadata parsing", errorCount.get(), equalTo(0));
    }
  }

  /**
   * Tests concurrent parsing of index paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentIndexParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i % 2; // Alternate between the two index paths
        executor.submit(() -> {
          try {
            // Parse an index path
            MavenPath mavenPath = pathParser.parsePath(
                index == 0 ? INDEX_PROPERTY_FILE_PATH : INDEX_MAIN_CHUNK_FILE_PATH);
            
            // Verify the parsed path is correct
            assertThat(mavenPath.getCoordinates(), nullValue());
            assertThat(pathParser.isRepositoryIndex(mavenPath), equalTo(true));
            assertThat(pathParser.isRepositoryMetadata(mavenPath), equalTo(false));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent index parsing", errorCount.get(), equalTo(0));
    }
  }

  /**
   * Tests concurrent parsing of complex extension paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentExtensionParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i % 3; // Use different extension types
        executor.submit(() -> {
          try {
            String path;
            switch (index) {
              case 0:
                path = "/org/sonatype/nexus/nexus-webapp/1.0.0-beta-5/nexus-webapp-1.0.0-beta-5.tar.gz";
                break;
              case 1:
                path = "/org/sonatype/nexus/nexus-webapp/1.0.0-beta-5/nexus-webapp-1.0.0-beta-5-bundle.tar.gz";
                break;
              default:
                path = "/org/codehaus/tycho/tycho-distribution/0.3.0-SNAPSHOT/tycho-distribution-0.3.0-20080818.153246-33-bin.tar.gz";
                break;
            }
            
            // Parse a path with complex extension
            MavenPath mavenPath = pathParser.parsePath(path);
            
            // Verify the parsed path is correct
            assertThat(mavenPath.getCoordinates(), notNullValue());
            assertThat(mavenPath.getCoordinates().getExtension(), equalTo("tar.gz"));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent extension parsing", errorCount.get(), equalTo(0));
    }
  }

  /**
   * Tests concurrent parsing of invalid paths using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentInvalidPathParsing() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Parse an invalid path
            MavenPath mavenPath = pathParser.parsePath(
                "/com/electrabel/connection-register-ear/1.2-SNAPSHOT/connection-register-ear-1.2-20101214.143755.ear");
            
            // Verify the parsed path is correct (should have null coordinates due to missing build number)
            assertThat(mavenPath.getCoordinates(), nullValue()); // filename lacks the -BBB build number
            assertThat(pathParser.isRepositoryMetadata(mavenPath), equalTo(false));
            assertThat(pathParser.isRepositoryIndex(mavenPath), equalTo(false));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            // We don't increment error count here as we expect this to succeed
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify all tasks succeeded
      assertThat("All invalid path parsing operations should succeed", successCount.get(), equalTo(threadCount));
    }
  }

  /**
   * Tests concurrent parsing of mixed path types using Virtual Threads.
   */
  @Test
  @Timeout(30)
  public void testConcurrentMixedPathParsing() throws Exception {
    final int threadCount = 5000; // Higher count to test more combinations
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Define an array of paths to parse concurrently
    final String[] paths = {
        "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-25-javadoc.jar",
        "/com/sun/xml/ws/jaxws-local-transport/2.1.3/jaxws-local-transport-2.1.3.pom.md5",
        "/org/jruby/jruby/1.0/maven-metadata.xml",
        INDEX_PROPERTY_FILE_PATH,
        "/org/sonatype/nexus/nexus-webapp/1.0.0-beta-5/nexus-webapp-1.0.0-beta-5.tar.gz",
        "/com/electrabel/connection-register-ear/1.2-SNAPSHOT/connection-register-ear-1.2-20101214.143755.ear",
        "/org/apache/maven/artifact/maven-artifact/3.0-SNAPSHOT/maven-artifact-3.0-20080411.005221-75-some.strange.classifier.pom.asc.sha1"
    };
    
    // Create an executor service with Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int pathIndex = i % paths.length;
        executor.submit(() -> {
          try {
            // Parse a path from the array
            MavenPath mavenPath = pathParser.parsePath(paths[pathIndex]);
            
            // Basic verification that the path was parsed
            assertThat(mavenPath, notNullValue());
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(20, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent mixed parsing", errorCount.get(), equalTo(0));
    }
  }
}