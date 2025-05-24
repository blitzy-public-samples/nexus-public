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

import java.time.Duration;
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
import org.sonatype.nexus.mime.RegexpMimeRulesSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RegexpMimeRulesSource} using Java 21 Virtual Threads.
 * 
 * This test validates that RegexpMimeRulesSource works correctly when accessed
 * concurrently by many virtual threads, ensuring thread safety and performance
 * under high concurrency scenarios.
 */
public class RegexpMimeRulesSourceVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 5000;
  private static final int RULE_COUNT = 100;
  
  /**
   * Test that verifies concurrent rule addition from many virtual threads.
   * 
   * This test creates thousands of virtual threads that simultaneously add rules
   * to the RegexpMimeRulesSource and verifies that all rules are correctly added.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentRuleAddition() throws Exception {
    final RegexpMimeRulesSource underTest = new RegexpMimeRulesSource();
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will all add rules concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread adds a unique rule
            String pattern = STR.".*\\.thread\{threadId}\\z";
            String mimeType = STR."application/thread-\{threadId}";
            underTest.addRule(pattern, mimeType);
            
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
      
      // Verify that rules were added correctly by checking a sample
      for (int i = 0; i < 100; i++) {
        int sampleId = i * (THREAD_COUNT / 100);
        String testPath = STR."test.thread\{sampleId}";
        MimeRule rule = underTest.getRuleForName(testPath);
        assertNotNull(rule, STR."Rule for \{testPath} should exist");
        assertEquals(STR."application/thread-\{sampleId}", rule.getMimetypes().get(0));
      }
    }
  }
  
  /**
   * Test that verifies concurrent rule resolution from many virtual threads.
   * 
   * This test creates a RegexpMimeRulesSource with predefined rules, then creates
   * thousands of virtual threads that simultaneously resolve MIME types for different
   * paths, ensuring that the correct MIME types are returned under high concurrency.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentRuleResolution() throws Exception {
    final RegexpMimeRulesSource underTest = new RegexpMimeRulesSource();
    
    // Add a set of rules before starting concurrent resolution
    for (int i = 0; i < RULE_COUNT; i++) {
      String pattern = STR.".*\\.type\{i}\\z";
      String mimeType = STR."application/type-\{i}";
      underTest.addRule(pattern, mimeType);
    }
    
    // Add some more complex rules
    underTest.addRule("(.*/maven-metadata.xml\\z)|(maven-metadata.xml\\z)", "application/x-maven-metadata");
    underTest.addRule("\\A/atom-service/.*\\.xml\\z", "application/atom+xml");
    underTest.addRule(".*\\.xml\\z", "application/xml");
    
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
    final AtomicInteger errors = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will all resolve rules concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Each thread resolves a mix of rules
            int ruleIndex = threadId % (RULE_COUNT + 3); // +3 for the complex rules
            String path;
            String expectedMimeType;
            
            if (ruleIndex < RULE_COUNT) {
              path = STR."file.type\{ruleIndex}";
              expectedMimeType = STR."application/type-\{ruleIndex}";
            } 
            else if (ruleIndex == RULE_COUNT) {
              path = "maven-metadata.xml";
              expectedMimeType = "application/x-maven-metadata";
            } 
            else if (ruleIndex == RULE_COUNT + 1) {
              path = "/atom-service/file.xml";
              expectedMimeType = "application/atom+xml";
            } 
            else {
              path = "regular.xml";
              expectedMimeType = "application/xml";
            }
            
            MimeRule rule = underTest.getRuleForName(path);
            if (rule == null) {
              errors.incrementAndGet();
              results.put(path, "null");
            } else {
              String actualMimeType = rule.getMimetypes().get(0);
              results.put(path, actualMimeType);
              if (!expectedMimeType.equals(actualMimeType)) {
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
      
      // Verify that all resolutions were correct
      assertEquals(0, errors.get(), "Some MIME type resolutions were incorrect");
      
      // Verify specific examples
      assertEquals("application/type-0", results.get("file.type0"));
      assertEquals("application/x-maven-metadata", results.get("maven-metadata.xml"));
      assertEquals("application/atom+xml", results.get("/atom-service/file.xml"));
      assertEquals("application/xml", results.get("regular.xml"));
    }
  }
  
  /**
   * Test that verifies mixed concurrent operations (adding and resolving rules) from many virtual threads.
   * 
   * This test creates virtual threads that simultaneously add rules and resolve MIME types,
   * ensuring that the RegexpMimeRulesSource handles mixed operations correctly under high concurrency.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testMixedConcurrentOperations() throws Exception {
    final RegexpMimeRulesSource underTest = new RegexpMimeRulesSource();
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a large number of virtual threads that will perform mixed operations concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Even threads add rules, odd threads resolve rules
            if (threadId % 2 == 0) {
              // Add a rule
              String pattern = STR.".*\\.mixed\{threadId}\\z";
              String mimeType = STR."application/mixed-\{threadId}";
              underTest.addRule(pattern, mimeType);
              results.put(STR."add-\{threadId}", true);
            } else {
              // Try to resolve a rule that might have been added by an even thread
              int targetThreadId = threadId - 1;
              String path = STR."test.mixed\{targetThreadId}";
              MimeRule rule = underTest.getRuleForName(path);
              
              // The rule might not exist yet if the even thread hasn't run
              results.put(STR."resolve-\{targetThreadId}", rule != null);
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
      
      // Verify that all added rules can now be resolved
      for (int i = 0; i < THREAD_COUNT; i += 2) {
        String path = STR."test.mixed\{i}";
        MimeRule rule = underTest.getRuleForName(path);
        assertNotNull(rule, STR."Rule for \{path} should exist after all threads complete");
        assertEquals(STR."application/mixed-\{i}", rule.getMimetypes().get(0));
      }
    }
  }
  
  /**
   * Test that compares the performance of virtual threads vs platform threads for regex operations.
   * 
   * This test creates both virtual threads and platform threads to perform regex-based MIME type
   * resolution and compares their performance, demonstrating the benefits of virtual threads for
   * concurrent regex operations.
   */
  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  public void compareVirtualAndPlatformThreadPerformance() throws Exception {
    // Create a RegexpMimeRulesSource with a large number of rules
    final RegexpMimeRulesSource underTest = new RegexpMimeRulesSource();
    for (int i = 0; i < 1000; i++) {
      String pattern = STR.".*\\.perf\{i}\\z";
      String mimeType = STR."application/perf-\{i}";
      underTest.addRule(pattern, mimeType);
    }
    
    // Add some complex rules that require more regex processing
    underTest.addRule("(.*/[a-z0-9]+/[a-z0-9]+/[0-9]+\\.[0-9]+\\.[0-9]+/.*\\.xml\\z)", "application/complex-xml");
    underTest.addRule("\\A/services/[a-z]+/v[0-9]+/.*\\.json\\z", "application/complex-json");
    
    // Test with virtual threads
    long virtualThreadTime = measurePerformance(underTest, true, 5000);
    
    // Test with platform threads (using a smaller number to avoid resource exhaustion)
    long platformThreadTime = measurePerformance(underTest, false, 500);
    
    // Scale the platform thread time to match the virtual thread count
    long scaledPlatformThreadTime = platformThreadTime * 10; // 5000/500 = 10
    
    log.info(STR."Performance comparison:\n" +
             STR."  Virtual Threads (5000): \{virtualThreadTime}ms\n" +
             STR."  Platform Threads (500): \{platformThreadTime}ms\n" +
             STR."  Scaled Platform Threads (equivalent to 5000): \{scaledPlatformThreadTime}ms");
    
    // We expect virtual threads to be more efficient, but we don't assert on exact numbers
    // as performance can vary across environments
  }
  
  /**
   * Helper method to measure the performance of regex operations using either virtual or platform threads.
   * 
   * @param rulesSource The RegexpMimeRulesSource to test
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @param threadCount The number of threads to create
   * @return The time in milliseconds taken to complete all operations
   */
  private long measurePerformance(
      RegexpMimeRulesSource rulesSource,
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
            t.setName(STR."platform-\{counter.incrementAndGet()}");
            return t;
          }
        });
    
    try {
      // Create threads that will perform regex operations
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Perform a mix of regex operations
            for (int j = 0; j < 10; j++) {
              int ruleIndex = (threadId * 10 + j) % 1000;
              String path = STR."file.perf\{ruleIndex}";
              MimeRule rule = rulesSource.getRuleForName(path);
              
              // Also test some complex paths
              if (j % 5 == 0) {
                String complexPath = STR."/org/sonatype/nexus/1.0.0/nexus-\{threadId}.xml";
                rulesSource.getRuleForName(complexPath);
              } else if (j % 5 == 1) {
                String complexPath = STR."/services/api/v1/resources/\{threadId}.json";
                rulesSource.getRuleForName(complexPath);
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
}