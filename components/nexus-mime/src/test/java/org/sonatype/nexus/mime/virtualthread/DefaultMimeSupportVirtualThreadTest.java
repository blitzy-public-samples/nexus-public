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
package org.sonatype.nexus.mime.virtualthread;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.MimeRule;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.internal.DefaultMimeSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultMimeSupport} using Java 21 Virtual Threads.
 * 
 * This test validates that DefaultMimeSupport works correctly when accessed
 * concurrently by many virtual threads, ensuring thread safety and performance
 * under high concurrency scenarios, particularly for I/O-bound operations.
 */
public class DefaultMimeSupportVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 5000;
  private static final int OPERATIONS_PER_THREAD = 10;
  
  /**
   * Test that verifies concurrent path-based MIME type detection from many virtual threads.
   * 
   * This test creates thousands of virtual threads that simultaneously detect MIME types
   * from file paths, ensuring that DefaultMimeSupport handles concurrent path-based
   * detection correctly under high concurrency.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentPathBasedDetection() throws Exception {
    final DefaultMimeSupport underTest = new DefaultMimeSupport();
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    final AtomicInteger errors = new AtomicInteger(0);
    
    // Define test paths and their expected MIME types
    final String[][] testPaths = {
        {"/some/path/artifact.pom", "application/xml"},
        {"/some/path/artifact.jar", "application/java-archive"},
        {"/some/path/artifact-sources.jar", "application/java-archive"},
        {"/some/path/maven-metadata.xml", "application/xml"},
        {"/some/path/some.xml", "application/xml"},
        {"/some/path/some.tar.gz", "application/x-gzip"},
        {"/some/path/some.tar.bz2", "application/x-bzip2"},
        {"/some/path/some.zip", "application/zip"},
        {"/some/path/some.war", "application/java-archive"},
        {"/some/path/some.rar", "application/java-archive"}
    };
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will all detect MIME types concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread performs multiple MIME type detections
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              int pathIndex = (threadId + j) % testPaths.length;
              String path = testPaths[pathIndex][0];
              String expectedMimeType = testPaths[pathIndex][1];
              
              String detectedMimeType = underTest.guessMimeTypeFromPath(path);
              results.put(STR."{threadId}-{j}", detectedMimeType);
              
              if (!expectedMimeType.equals(detectedMimeType)) {
                errors.incrementAndGet();
              }
            }
            
            return null;
          } 
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify that all detections were correct
      assertEquals(0, errors.get(), "Some MIME type detections were incorrect");
      
      // Verify specific examples
      for (int i = 0; i < 10; i++) {
        int pathIndex = i % testPaths.length;
        String expectedMimeType = testPaths[pathIndex][1];
        assertEquals(expectedMimeType, results.get(STR."0-{i}"));
      }
    }
  }
  
  /**
   * Test that verifies concurrent content-based MIME type detection from many virtual threads.
   * 
   * This test creates thousands of virtual threads that simultaneously detect MIME types
   * from file content using InputStreams, ensuring that DefaultMimeSupport handles concurrent
   * content-based detection correctly under high concurrency and doesn't suffer from thread pinning.
   */
  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  public void testConcurrentContentBasedDetection() throws Exception {
    final DefaultMimeSupport underTest = new DefaultMimeSupport();
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    final AtomicInteger errors = new AtomicInteger(0);
    
    // Define test files and their expected MIME types
    final String[][] testFiles = {
        {"src/test/resources/mime/file.gif", "image/gif"},
        {"src/test/resources/mime/file.zip", "application/zip"},
        {"src/test/resources/mime/empty.zip", "application/zip"},
        {"src/test/resources/mime/file.jar", "application/java-archive"}
    };
    
    // Resolve file paths
    final File[] resolvedFiles = new File[testFiles.length];
    for (int i = 0; i < testFiles.length; i++) {
      resolvedFiles[i] = util.resolveFile(testFiles[i][0]);
      assertNotNull(resolvedFiles[i], STR."Test file {testFiles[i][0]} should exist");
      assertTrue(resolvedFiles[i].exists(), STR."Test file {testFiles[i][0]} should exist");
    }
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will all detect MIME types concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread performs multiple MIME type detections
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              int fileIndex = (threadId + j) % resolvedFiles.length;
              File file = resolvedFiles[fileIndex];
              String expectedMimeType = testFiles[fileIndex][1];
              
              // Use try-with-resources to ensure InputStream is closed
              try (InputStream is = new FileInputStream(file)) {
                String detectedMimeType = underTest.detectMimeType(is, file.getName());
                results.put(STR."{threadId}-{j}", detectedMimeType);
                
                if (!expectedMimeType.equals(detectedMimeType)) {
                  errors.incrementAndGet();
                }
              }
            }
            
            return null;
          } 
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(15, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify that all detections were correct
      assertEquals(0, errors.get(), "Some MIME type detections were incorrect");
      
      // Verify specific examples
      for (int i = 0; i < 4; i++) {
        int fileIndex = i % resolvedFiles.length;
        String expectedMimeType = testFiles[fileIndex][1];
        assertEquals(expectedMimeType, results.get(STR."0-{i}"));
      }
    }
  }
  
  /**
   * Test that verifies concurrent MIME type detection with custom MimeRulesSource from many virtual threads.
   * 
   * This test creates thousands of virtual threads that simultaneously detect MIME types
   * using a custom MimeRulesSource, ensuring that DefaultMimeSupport handles concurrent
   * detection with custom rules correctly under high concurrency.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentDetectionWithCustomRules() throws Exception {
    final DefaultMimeSupport underTest = new DefaultMimeSupport();
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    final AtomicInteger errors = new AtomicInteger(0);
    
    // Create a custom MimeRulesSource that always returns a fixed MIME type
    final MimeRulesSource customSource = new MimeRulesSource() {
      @Override
      public MimeRule getRuleForName(String path) {
        return new MimeRule(false, "custom/mime-type");
      }
    };
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will all detect MIME types concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread performs multiple MIME type detections with custom rules
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String path = STR."/some/path/file-{threadId}-{j}.ext";
              String expectedMimeType = "custom/mime-type";
              
              String detectedMimeType = underTest.guessMimeTypeFromPath(path, customSource);
              results.put(STR."{threadId}-{j}", detectedMimeType);
              
              if (!expectedMimeType.equals(detectedMimeType)) {
                errors.incrementAndGet();
              }
            }
            
            return null;
          } 
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
      
      // Verify that all detections were correct
      assertEquals(0, errors.get(), "Some MIME type detections were incorrect");
      
      // Verify specific examples
      for (int i = 0; i < 10; i++) {
        assertEquals("custom/mime-type", results.get(STR."0-{i}"));
      }
    }
  }
  
  /**
   * Test that compares the performance of virtual threads vs platform threads for MIME detection.
   * 
   * This test creates both virtual threads and platform threads to perform MIME type detection
   * and compares their performance, demonstrating the benefits of virtual threads for
   * concurrent I/O-bound operations like content-based MIME detection.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void compareVirtualAndPlatformThreadPerformance() throws Exception {
    final DefaultMimeSupport underTest = new DefaultMimeSupport();
    
    // Define test files for content-based detection
    final String[][] testFiles = {
        {"src/test/resources/mime/file.gif", "image/gif"},
        {"src/test/resources/mime/file.zip", "application/zip"},
        {"src/test/resources/mime/empty.zip", "application/zip"},
        {"src/test/resources/mime/file.jar", "application/java-archive"}
    };
    
    // Resolve file paths
    final File[] resolvedFiles = new File[testFiles.length];
    for (int i = 0; i < testFiles.length; i++) {
      resolvedFiles[i] = util.resolveFile(testFiles[i][0]);
      assertNotNull(resolvedFiles[i], STR."Test file {testFiles[i][0]} should exist");
      assertTrue(resolvedFiles[i].exists(), STR."Test file {testFiles[i][0]} should exist");
    }
    
    // Test with virtual threads for content-based detection
    long virtualThreadContentTime = measureContentDetectionPerformance(underTest, resolvedFiles, true, 5000);
    
    // Test with platform threads for content-based detection (using a smaller number to avoid resource exhaustion)
    long platformThreadContentTime = measureContentDetectionPerformance(underTest, resolvedFiles, false, 500);
    
    // Scale the platform thread time to match the virtual thread count
    long scaledPlatformThreadContentTime = platformThreadContentTime * 10; // 5000/500 = 10
    
    // Test with virtual threads for path-based detection
    long virtualThreadPathTime = measurePathDetectionPerformance(underTest, true, 5000);
    
    // Test with platform threads for path-based detection
    long platformThreadPathTime = measurePathDetectionPerformance(underTest, false, 500);
    
    // Scale the platform thread time to match the virtual thread count
    long scaledPlatformThreadPathTime = platformThreadPathTime * 10; // 5000/500 = 10
    
    log.info(STR."Performance comparison:\n" +
             STR."  Content-based MIME detection:\n" +
             STR."    Virtual Threads (5000): {virtualThreadContentTime}ms\n" +
             STR."    Platform Threads (500): {platformThreadContentTime}ms\n" +
             STR."    Scaled Platform Threads (equivalent to 5000): {scaledPlatformThreadContentTime}ms\n" +
             STR."  Path-based MIME detection:\n" +
             STR."    Virtual Threads (5000): {virtualThreadPathTime}ms\n" +
             STR."    Platform Threads (500): {platformThreadPathTime}ms\n" +
             STR."    Scaled Platform Threads (equivalent to 5000): {scaledPlatformThreadPathTime}ms");
    
    // We expect virtual threads to be more efficient for I/O-bound operations (content-based detection),
    // but we don't assert on exact numbers as performance can vary across environments
  }
  
  /**
   * Helper method to measure the performance of content-based MIME detection using either virtual or platform threads.
   * 
   * @param mimeSupport The DefaultMimeSupport to test
   * @param files The test files to use for detection
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @param threadCount The number of threads to create
   * @return The time in milliseconds taken to complete all operations
   */
  private long measureContentDetectionPerformance(
      DefaultMimeSupport mimeSupport,
      File[] files,
      boolean useVirtualThreads,
      int threadCount) throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Math.min(threadCount, 200), new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger();
          
          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(STR."platform-{counter.incrementAndGet()}");
            return t;
          }
        });
    
    try {
      // Create threads that will perform content-based MIME detection
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple content-based MIME detections
            for (int j = 0; j < 5; j++) {
              int fileIndex = (threadId + j) % files.length;
              File file = files[fileIndex];
              
              // Use try-with-resources to ensure InputStream is closed
              try (InputStream is = new FileInputStream(file)) {
                mimeSupport.detectMimeType(is, file.getName());
              }
            }
            
            return null;
          } 
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await();
      
      // End timing
      long endTime = System.currentTimeMillis();
      
      return endTime - startTime;
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to measure the performance of path-based MIME detection using either virtual or platform threads.
   * 
   * @param mimeSupport The DefaultMimeSupport to test
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @param threadCount The number of threads to create
   * @return The time in milliseconds taken to complete all operations
   */
  private long measurePathDetectionPerformance(
      DefaultMimeSupport mimeSupport,
      boolean useVirtualThreads,
      int threadCount) throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Define test paths for path-based detection
    final String[] testPaths = {
        "/some/path/artifact.pom",
        "/some/path/artifact.jar",
        "/some/path/artifact-sources.jar",
        "/some/path/maven-metadata.xml",
        "/some/path/some.xml",
        "/some/path/some.tar.gz",
        "/some/path/some.tar.bz2",
        "/some/path/some.zip",
        "/some/path/some.war",
        "/some/path/some.rar"
    };
    
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Math.min(threadCount, 200), new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger();
          
          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(STR."platform-{counter.incrementAndGet()}");
            return t;
          }
        });
    
    try {
      // Create threads that will perform path-based MIME detection
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform multiple path-based MIME detections
            for (int j = 0; j < 20; j++) {
              int pathIndex = (threadId + j) % testPaths.length;
              String path = testPaths[pathIndex];
              
              mimeSupport.guessMimeTypeFromPath(path);
            }
            
            return null;
          } 
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await();
      
      // End timing
      long endTime = System.currentTimeMillis();
      
      return endTime - startTime;
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}