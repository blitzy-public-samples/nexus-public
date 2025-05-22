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
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;

import org.junit.jupiter.api.Test;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.repository.maven.MavenPath.SignatureType.GPG;
import static org.sonatype.nexus.repository.maven.internal.utils.MavenVariableResolverAdapterUtil.createCoordinateMap;

/**
 * Tests for {@link MavenVariableResolverAdapterUtil} running in Java 21 Virtual Threads.
 */
public class MavenVariableResolverAdapterUtilVirtualThreadTest
    extends TestSupport
{
  @Test
  void shouldCopyCoordinatesToMapInVirtualThread() throws Exception {
    AtomicReference<Map<String, String>> resultMap = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();
    
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
    
    // Verify the thread was actually a virtual thread
    assertTrue(isVirtualThread.get(), "Test should run in a virtual thread");
    
    // Verify the results
    Map<String, String> map = resultMap.get();
    assertThat(map, hasEntry("groupId", "org.mockito"));
    assertThat(map, hasEntry("artifactId", "mockito-core"));
    assertThat(map, hasEntry("version", "3.24"));
    assertThat(map, hasEntry("extension", ".jar"));
    assertThat(map, hasEntry("classifier", "test"));
  }

  @Test
  void classifierShouldBeEmptyStringWhenNotSetInVirtualThread() throws Exception {
    AtomicReference<Map<String, String>> resultMap = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThread = new AtomicReference<>();
    
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
    
    // Verify the thread was actually a virtual thread
    assertTrue(isVirtualThread.get(), "Test should run in a virtual thread");
    
    // Verify the results
    Map<String, String> map = resultMap.get();
    assertThat(map, hasEntry("classifier", EMPTY));
  }
  
  @Test
  void shouldHandleConcurrentCoordinateMapCreationInVirtualThreads() throws Exception {
    // Number of concurrent threads to run
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Exception> testException = new AtomicReference<>();
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit tasks to create coordinate maps concurrently
    Future<?>[] futures = new Future<?>[threadCount];
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      futures[i] = executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          latch.await();
          
          // Create unique coordinates for each thread
          Coordinates coordinates = new Coordinates(
              false, 
              "org.test" + index, 
              "artifact" + index,
              "1.0." + index, 
              3600L, 
              100, 
              "1.0." + index, 
              (index % 2 == 0) ? "test" + index : null, 
              ".jar", 
              GPG);

          // Create the coordinate map
          Map<String, String> map = createCoordinateMap(coordinates);
          
          // Verify the map contains the expected values
          if (!map.containsKey("groupId") || !map.get("groupId").equals("org.test" + index)) {
            throw new AssertionError("groupId mismatch for thread " + index);
          }
          if (!map.containsKey("artifactId") || !map.get("artifactId").equals("artifact" + index)) {
            throw new AssertionError("artifactId mismatch for thread " + index);
          }
          if (!map.containsKey("version") || !map.get("version").equals("1.0." + index)) {
            throw new AssertionError("version mismatch for thread " + index);
          }
          if (!map.containsKey("extension") || !map.get("extension").equals(".jar")) {
            throw new AssertionError("extension mismatch for thread " + index);
          }
          
          // Verify classifier handling
          String expectedClassifier = (index % 2 == 0) ? "test" + index : EMPTY;
          if (!map.containsKey("classifier") || !map.get("classifier").equals(expectedClassifier)) {
            throw new AssertionError("classifier mismatch for thread " + index + 
                ", expected: '" + expectedClassifier + "', actual: '" + map.get("classifier") + "'");
          }
        }
        catch (Exception e) {
          testException.set(e);
        }
      });
    }
    
    // Start all threads simultaneously
    latch.countDown();
    
    // Wait for all threads to complete
    for (Future<?> future : futures) {
      future.get();
    }
    
    // Shutdown the executor
    executor.shutdown();
    
    // Check if any exceptions occurred
    if (testException.get() != null) {
      throw new AssertionError("Test failed with exception", testException.get());
    }
  }
  
  @Test
  void shouldVerifyThreadIsVirtual() throws ExecutionException, InterruptedException {
    // Use the virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task that checks if it's running in a virtual thread
      Future<Boolean> future = executor.submit(() -> Thread.currentThread().isVirtual());
      
      // Verify the thread was a virtual thread
      boolean isVirtual = future.get();
      assertThat("Task should run in a virtual thread", isVirtual, is(true));
    }
  }
}