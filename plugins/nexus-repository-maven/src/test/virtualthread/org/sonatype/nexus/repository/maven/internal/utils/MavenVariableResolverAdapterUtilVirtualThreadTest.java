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
package org.sonatype.nexus.repository.maven.internal.utils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;

import org.junit.jupiter.api.Test;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.repository.maven.MavenPath.SignatureType.GPG;
import static org.sonatype.nexus.repository.maven.internal.utils.MavenVariableResolverAdapterUtil.createCoordinateMap;

/**
 * Tests for {@link MavenVariableResolverAdapterUtil#createCoordinateMap} when executed in a Virtual Thread.
 * This test validates that coordinate mapping functionality remains reliable in a highly concurrent
 * Virtual Thread environment.
 */
public class MavenVariableResolverAdapterUtilVirtualThreadTest
    extends TestSupport
{
  /**
   * Tests that coordinate mapping works correctly when executed in a Virtual Thread.
   */
  @Test
  void shouldCopyCoordinatesToMapInVirtualThread() throws Exception {
    AtomicReference<Map<String, String>> resultMap = new AtomicReference<>();
    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    // Create and start a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      // Verify we're running in a virtual thread
      isVirtualThread.set(Thread.currentThread().isVirtual());
      
      // Create test coordinates
      Coordinates coordinates = new Coordinates(false, "org.mockito", "mockito-core",
          "3.24", 3600L, 100, "3.24", "test", ".jar", GPG);

      // Execute the method under test
      resultMap.set(createCoordinateMap(coordinates));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the results
    Map<String, String> map = resultMap.get();
    assertThat(map, hasEntry("groupId", "org.mockito"));
    assertThat(map, hasEntry("artifactId", "mockito-core"));
    assertThat(map, hasEntry("version", "3.24"));
    assertThat(map, hasEntry("extension", ".jar"));
    assertThat(map, hasEntry("classifier", "test"));
  }

  /**
   * Tests that classifier normalization works correctly when executed in a Virtual Thread.
   */
  @Test
  void classifierShouldBeEmptyStringWhenNotSetInVirtualThread() throws Exception {
    AtomicReference<Map<String, String>> resultMap = new AtomicReference<>();
    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    // Create and start a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      // Verify we're running in a virtual thread
      isVirtualThread.set(Thread.currentThread().isVirtual());
      
      // Create test coordinates with null classifier
      Coordinates coordinates = new Coordinates(false, "org.mockito", "mockito-core",
          "3.24", 3600L, 100, "3.24", null, ".jar", GPG);

      // Execute the method under test
      resultMap.set(createCoordinateMap(coordinates));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the test ran in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the results
    Map<String, String> map = resultMap.get();
    assertThat(map, hasEntry("classifier", EMPTY));
  }

  /**
   * Tests that coordinate mapping works correctly when executed concurrently in multiple Virtual Threads.
   * This validates thread safety of the createCoordinateMap method in a highly concurrent environment.
   */
  @Test
  void shouldHandleConcurrentCoordinateMappingInVirtualThreads() throws Exception {
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicBoolean allVirtualThreads = new AtomicBoolean(true);
    final AtomicBoolean allMappingsCorrect = new AtomicBoolean(true);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to the executor
    Future<?>[] futures = new Future<?>[threadCount];
    
    for (int i = 0; i < threadCount; i++) {
      final String suffix = String.valueOf(i);
      
      futures[i] = executor.submit(() -> {
        try {
          // Verify we're running in a virtual thread
          if (!Thread.currentThread().isVirtual()) {
            allVirtualThreads.set(false);
          }
          
          // Create test coordinates with unique values
          Coordinates coordinates = new Coordinates(
              false,
              "org.mockito" + suffix,
              "mockito-core" + suffix,
              "3.24" + suffix,
              3600L,
              100,
              "3.24" + suffix,
              "test" + suffix,
              ".jar",
              GPG);

          // Execute the method under test
          Map<String, String> map = createCoordinateMap(coordinates);
          
          // Verify the mapping is correct
          boolean mappingCorrect = 
              "org.mockito" + suffix.equals(map.get("groupId")) &&
              "mockito-core" + suffix.equals(map.get("artifactId")) &&
              "3.24" + suffix.equals(map.get("version")) &&
              ".jar".equals(map.get("extension")) &&
              "test" + suffix.equals(map.get("classifier"));
              
          if (!mappingCorrect) {
            allMappingsCorrect.set(false);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await();
    executor.shutdown();
    
    // Verify all tests ran in virtual threads
    assertThat("All tests should run in virtual threads", allVirtualThreads.get(), is(true));
    
    // Verify all mappings were correct
    assertThat("All coordinate mappings should be correct", allMappingsCorrect.get(), is(true));
    
    // Check for any exceptions
    for (Future<?> future : futures) {
      try {
        future.get(); // Will throw an exception if the task failed
      } catch (ExecutionException e) {
        throw new AssertionError("Task failed with exception", e.getCause());
      }
    }
  }
}