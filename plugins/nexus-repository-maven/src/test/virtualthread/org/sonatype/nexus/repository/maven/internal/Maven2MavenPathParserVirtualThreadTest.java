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
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.junit.experimental.categories.Category;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_MAIN_CHUNK_FILE_PATH;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_PROPERTY_FILE_PATH;

/**
 * Virtual Thread test for {@link Maven2MavenPathParser}
 * 
 * This test validates that Maven2MavenPathParser works correctly when used with Java 21 Virtual Threads.
 * It ensures that path parsing operations are thread-safe and produce correct results when executed
 * concurrently by multiple virtual threads.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class Maven2MavenPathParserVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
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
   * Tests concurrent parsing of artifact paths using virtual threads.
   * Verifies that the parser correctly handles multiple concurrent requests
   * and produces consistent results.
   */
  @Test
  public void concurrentArtifactParsing() throws Exception {
    String[] paths = {
        "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-25-javadoc.jar",
        "/com/sun/xml/ws/jaxws-local-transport/2.1.3/jaxws-local-transport-2.1.3.pom.md5",
        "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-2.jar",
        "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-2.jar.md5",
        "/com/stchome/products/dsms/services/dsms-intervention-service/2.4.2-64-SNAPSHOT/dsms-intervention-service-2.4.2-64-SNAPSHOT.jar.sha1",
        "/com/stchome/products/dsms/services/dsms-intervention-service/2.4.2-64-SNAPSHOT/dsms-intervention-service-2.4.2-64-SNAPSHOT-javadoc.jar.sha1",
        "/org/jruby/jruby/1.0/jruby-1.0-javadoc.jar",
        "/org/jruby/jruby/1.0/jruby-1.0-javadoc.jar.sha1",
        "/activemq/activemq-core/1.2/activemq-core-1.2.pom",
        "/junit/junit/3.8/junit-3.8.jar"
    };
    
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i % paths.length;
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(paths[index]);
          
          // Verify the path was parsed correctly
          assertThat(mavenPath, notNullValue());
          assertThat(mavenPath.getPath(), equalTo(paths[index].substring(1)));
          
          // Verify coordinates were extracted correctly
          assertThat(mavenPath.getCoordinates(), notNullValue());
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          logger.error("Error parsing path: {}", paths[index], e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify all paths were parsed successfully
    assertEquals(THREAD_COUNT, successCount.get(), 
        "Not all paths were parsed successfully under concurrent virtual thread execution");
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of snapshot paths using virtual threads.
   * Verifies that the parser correctly handles snapshot version parsing
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentSnapshotParsing() throws Exception {
    String snapshotPath = "/org/jruby/jruby/1.0RC1-SNAPSHOT/jruby-1.0RC1-20070504.160758-25-javadoc.jar";
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    List<Exception> exceptions = new ArrayList<>();
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse the same snapshot path concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(snapshotPath);
          
          // Verify snapshot-specific properties
          assertThat(mavenPath.getCoordinates().getBaseVersion(), equalTo("1.0RC1-SNAPSHOT"));
          assertThat(mavenPath.getCoordinates().getVersion(), equalTo("1.0RC1-20070504.160758-25"));
          assertThat(mavenPath.getCoordinates().getTimestamp(), equalTo(parseTimestamp("20070504.160758")));
          assertThat(mavenPath.getCoordinates().getBuildNumber(), equalTo(25));
          assertTrue(mavenPath.getCoordinates().isSnapshot());
        } 
        catch (Exception e) {
          synchronized (exceptions) {
            exceptions.add(e);
          }
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify no exceptions occurred
    assertTrue(exceptions.isEmpty(), 
        "Exceptions occurred during concurrent snapshot parsing: " + exceptions);
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of metadata paths using virtual threads.
   * Verifies that the parser correctly identifies metadata paths
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentMetadataParsing() throws Exception {
    String[] metadataPaths = {
        "/something/that/looks/maven-metadata.xml",
        "/something/that/looks/like-SNAPSHOT/maven-metadata.xml.sha1",
        "/org/codehaus/plexus/plexus-container-default/maven-metadata.xml.md5",
        "/org/jruby/jruby/1.0/maven-metadata.xml",
        "/org/jruby/jruby/1.0-SNAPSHOT/maven-metadata.xml"
    };
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger metadataCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse metadata paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i % metadataPaths.length;
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(metadataPaths[index]);
          
          // Verify it's recognized as metadata
          if (pathParser.isRepositoryMetadata(mavenPath)) {
            metadataCount.incrementAndGet();
          }
          
          // Verify it's not recognized as an index
          assertFalse(pathParser.isRepositoryIndex(mavenPath));
        } 
        catch (Exception e) {
          logger.error("Error parsing metadata path: {}", metadataPaths[index], e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify all metadata paths were correctly identified
    assertEquals(THREAD_COUNT, metadataCount.get(), 
        "Not all metadata paths were correctly identified under concurrent virtual thread execution");
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of index paths using virtual threads.
   * Verifies that the parser correctly identifies index paths
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentIndexParsing() throws Exception {
    String[] paths = {
        INDEX_PROPERTY_FILE_PATH,
        INDEX_MAIN_CHUNK_FILE_PATH,
        "/something/else/not-an-index.xml",
        "/another/non-index/path.jar"
    };
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger indexCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse index paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i % paths.length;
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(paths[index]);
          
          // Count if it's recognized as an index
          if (pathParser.isRepositoryIndex(mavenPath)) {
            indexCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing index path: {}", paths[index], e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify the correct number of index paths were identified
    // Half of the paths are index paths, so we expect half of THREAD_COUNT
    int expectedIndexCount = THREAD_COUNT / paths.length * 2; // 2 index paths out of 4 total paths
    assertEquals(expectedIndexCount, indexCount.get(), 
        "Incorrect number of index paths identified under concurrent virtual thread execution");
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of paths with complex extensions using virtual threads.
   * Verifies that the parser correctly handles extension parsing
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentExtensionParsing() throws Exception {
    String[] paths = {
        "/org/sonatype/nexus/nexus-webapp/1.0.0-beta-5/nexus-webapp-1.0.0-beta-5.tar.gz",
        "/org/sonatype/nexus/nexus-webapp/1.0.0-beta-5/nexus-webapp-1.0.0-beta-5-bundle.tar.gz",
        "/org/codehaus/tycho/tycho-distribution/0.3.0-SNAPSHOT/tycho-distribution-0.3.0-SNAPSHOT-bin.tar.gz",
        "/org/codehaus/tycho/tycho-distribution/SNAPSHOT/tycho-distribution-SNAPSHOT-bin.tar.gz",
        "/org/codehaus/tycho/tycho-distribution/0.3.0-SNAPSHOT/tycho-distribution-0.3.0-20080818.153246-33-bin.tar.gz"
    };
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse paths with complex extensions concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i % paths.length;
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(paths[index]);
          
          // Verify extension was parsed correctly
          assertThat(mavenPath.getCoordinates().getExtension(), equalTo("tar.gz"));
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          logger.error("Error parsing path with complex extension: {}", paths[index], e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify all paths were parsed successfully
    assertEquals(THREAD_COUNT, successCount.get(), 
        "Not all paths with complex extensions were parsed successfully under concurrent virtual thread execution");
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of paths with hash types using virtual threads.
   * Verifies that the parser correctly identifies hash types
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentHashTypeParsing() throws Exception {
    String md5Path = "/com/sun/xml/ws/jaxws-local-transport/2.1.3/jaxws-local-transport-2.1.3.pom.md5";
    String sha1Path = "/org/jruby/jruby/1.0/jruby-1.0-javadoc.jar.sha1";
    String nonHashPath = "/org/jruby/jruby/1.0/jruby-1.0-javadoc.jar";
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 3); // 3 paths to test
    AtomicInteger md5Count = new AtomicInteger(0);
    AtomicInteger sha1Count = new AtomicInteger(0);
    AtomicInteger nonHashCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse MD5 paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(md5Path);
          
          if (mavenPath.getHashType() == HashType.MD5) {
            md5Count.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing MD5 path", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Submit tasks to parse SHA1 paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(sha1Path);
          
          if (mavenPath.getHashType() == HashType.SHA1) {
            sha1Count.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing SHA1 path", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Submit tasks to parse non-hash paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(nonHashPath);
          
          if (mavenPath.getHashType() == null) {
            nonHashCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing non-hash path", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify all hash types were correctly identified
    assertEquals(THREAD_COUNT, md5Count.get(), 
        "Not all MD5 hash types were correctly identified under concurrent virtual thread execution");
    assertEquals(THREAD_COUNT, sha1Count.get(), 
        "Not all SHA1 hash types were correctly identified under concurrent virtual thread execution");
    assertEquals(THREAD_COUNT, nonHashCount.get(), 
        "Not all non-hash paths were correctly identified under concurrent virtual thread execution");
    
    executor.shutdown();
  }

  /**
   * Tests concurrent parsing of paths with signature types using virtual threads.
   * Verifies that the parser correctly identifies signature types
   * when executed by multiple concurrent virtual threads.
   */
  @Test
  public void concurrentSignatureTypeParsing() throws Exception {
    String signaturePath = "/org/apache/maven/artifact/maven-artifact/3.0-SNAPSHOT/maven-artifact-3.0-20080411.005221-75.pom.asc";
    String nonSignaturePath = "/org/apache/maven/artifact/maven-artifact/3.0-SNAPSHOT/maven-artifact-3.0-20080411.005221-75.pom";
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 2); // 2 paths to test
    AtomicInteger signatureCount = new AtomicInteger(0);
    AtomicInteger nonSignatureCount = new AtomicInteger(0);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to parse signature paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(signaturePath);
          
          if (mavenPath.getCoordinates().getSignatureType() == SignatureType.GPG) {
            signatureCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing signature path", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Submit tasks to parse non-signature paths concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          MavenPath mavenPath = pathParser.parsePath(nonSignaturePath);
          
          if (mavenPath.getCoordinates().getSignatureType() == null) {
            nonSignatureCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error parsing non-signature path", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "Timed out waiting for virtual threads to complete");
    
    // Verify all signature types were correctly identified
    assertEquals(THREAD_COUNT, signatureCount.get(), 
        "Not all signature types were correctly identified under concurrent virtual thread execution");
    assertEquals(THREAD_COUNT, nonSignatureCount.get(), 
        "Not all non-signature paths were correctly identified under concurrent virtual thread execution");
    
    executor.shutdown();
  }
}