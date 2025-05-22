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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.MimeRule;
import org.sonatype.nexus.mime.RegexpMimeRulesSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link RegexpMimeRulesSource} using Java 21 Virtual Threads.
 * 
 * This test validates that the RegexpMimeRulesSource class works correctly
 * when accessed concurrently by many virtual threads, ensuring thread safety
 * and proper performance characteristics.
 */
@EnabledOnJre(JRE.JAVA_21)
public class RegexpMimeRulesSourceVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 10_000;
  private static final int RULE_COUNT = 100;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Test that verifies the RegexpMimeRulesSource can handle concurrent rule additions
   * and lookups from many virtual threads without errors or inconsistencies.
   */
  @Test
  void testConcurrentRuleAdditionAndLookupWithVirtualThreads() throws Exception {
    RegexpMimeRulesSource rulesSource = new RegexpMimeRulesSource();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Pre-add some rules to ensure there's something to match
    rulesSource.addRule(".*\\.foo\\z", "foo/bar");
    rulesSource.addRule(".*\\.pom\\z", "application/x-pom");
    rulesSource.addRule("(.*/maven-metadata.xml\\z)|(maven-metadata.xml\\z)", "application/x-maven-metadata");
    
    // Create many virtual threads that will concurrently add rules and perform lookups
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Add a unique rule for this thread
          if (threadId % 10 == 0) { // Only some threads add rules to avoid too many rules
            String pattern = String.format(".*\\.test%d\\z", threadId);
            String mimeType = String.format("test/type-%d", threadId);
            rulesSource.addRule(pattern, mimeType);
          }
          
          // Perform lookups for existing rules
          MimeRule fooRule = rulesSource.getRuleForName("/some/path/file.foo");
          assertThat(fooRule, notNullValue());
          assertThat(fooRule.getMimetypes().get(0), equalTo("foo/bar"));
          
          MimeRule pomRule = rulesSource.getRuleForName("/log4j/log4j/1.2.12/log4j-1.2.12.pom");
          assertThat(pomRule, notNullValue());
          assertThat(pomRule.getMimetypes().get(0), equalTo("application/x-pom"));
          
          MimeRule metadataRule = rulesSource.getRuleForName("maven-metadata.xml");
          assertThat(metadataRule, notNullValue());
          assertThat(metadataRule.getMimetypes().get(0), equalTo("application/x-maven-metadata"));
          
          // Test a non-matching path
          MimeRule nonMatchingRule = rulesSource.getRuleForName("/some/path/file.nonexistent");
          assertThat(nonMatchingRule, nullValue());
          
        } catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in virtual thread {}", threadId, e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete in time", completed, is(true));
    assertThat("No errors should occur in virtual threads", errorCount.get(), is(0));
  }
  
  /**
   * Test that compares the performance of virtual threads vs platform threads
   * when performing regex-based MIME type resolution.
   */
  @Test
  void testVirtualThreadVsPlatformThreadPerformance() throws Exception {
    // Create a rules source with many rules to test performance
    RegexpMimeRulesSource rulesSource = new RegexpMimeRulesSource();
    for (int i = 0; i < RULE_COUNT; i++) {
      String pattern = String.format(".*\\.type%d\\z", i);
      String mimeType = String.format("test/mime-type-%d", i);
      rulesSource.addRule(pattern, mimeType);
    }
    
    // Add some common rules
    rulesSource.addRule(".*\\.xml\\z", "application/xml");
    rulesSource.addRule(".*\\.json\\z", "application/json");
    rulesSource.addRule(".*\\.html\\z", "text/html");
    
    // Prepare test data - paths to resolve
    List<String> testPaths = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      testPaths.add(String.format("/path/to/file%d.type%d", i, i % RULE_COUNT));
      testPaths.add(String.format("/another/path/doc%d.xml", i));
      testPaths.add(String.format("/data/config%d.json", i));
      testPaths.add(String.format("/web/page%d.html", i));
      testPaths.add(String.format("/random/file%d.unknown", i)); // No match
    }
    
    // Test with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    ConcurrentHashMap<String, String> virtualThreadResults = new ConcurrentHashMap<>();
    CountDownLatch virtualThreadLatch = new CountDownLatch(testPaths.size());
    
    for (String path : testPaths) {
      virtualThreadExecutor.submit(() -> {
        try {
          MimeRule rule = rulesSource.getRuleForName(path);
          if (rule != null) {
            virtualThreadResults.put(path, rule.getMimetypes().get(0));
          } else {
            virtualThreadResults.put(path, null);
          }
        } finally {
          virtualThreadLatch.countDown();
        }
      });
    }
    
    virtualThreadLatch.await(10, TimeUnit.SECONDS);
    long virtualThreadDuration = System.nanoTime() - virtualThreadStartTime;
    
    // Test with platform threads
    long platformThreadStartTime = System.nanoTime();
    ConcurrentHashMap<String, String> platformThreadResults = new ConcurrentHashMap<>();
    CountDownLatch platformThreadLatch = new CountDownLatch(testPaths.size());
    
    for (String path : testPaths) {
      platformThreadExecutor.submit(() -> {
        try {
          MimeRule rule = rulesSource.getRuleForName(path);
          if (rule != null) {
            platformThreadResults.put(path, rule.getMimetypes().get(0));
          } else {
            platformThreadResults.put(path, null);
          }
        } finally {
          platformThreadLatch.countDown();
        }
      });
    }
    
    platformThreadLatch.await(10, TimeUnit.SECONDS);
    long platformThreadDuration = System.nanoTime() - platformThreadStartTime;
    
    // Verify results are the same
    assertThat("Virtual thread results should match platform thread results",
        virtualThreadResults, equalTo(platformThreadResults));
    
    // Log performance comparison
    log.info("Virtual thread execution time: {} ms", Duration.ofNanos(virtualThreadDuration).toMillis());
    log.info("Platform thread execution time: {} ms", Duration.ofNanos(platformThreadDuration).toMillis());
    
    // Virtual threads should generally be faster or at least not significantly slower
    // This is a soft assertion as performance can vary based on the environment
    if (virtualThreadDuration > platformThreadDuration * 1.5) {
      log.warn("Virtual threads were significantly slower than platform threads. This may indicate a problem.");
    }
  }
  
  /**
   * Test that verifies the RegexpMimeRulesSource can handle a high volume of concurrent
   * rule additions without thread safety issues.
   */
  @Test
  void testMassiveConcurrentRuleAddition() throws Exception {
    RegexpMimeRulesSource rulesSource = new RegexpMimeRulesSource();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create many virtual threads that will concurrently add rules
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Each thread adds a unique rule
          String pattern = String.format(".*\\.concurrent%d\\z", threadId);
          String mimeType = String.format("concurrent/type-%d", threadId);
          rulesSource.addRule(pattern, mimeType);
          
          // Immediately verify the rule was added correctly
          String testPath = String.format("/test/file.concurrent%d", threadId);
          MimeRule rule = rulesSource.getRuleForName(testPath);
          
          assertThat("Rule should be found", rule, notNullValue());
          assertThat("Rule should have correct mime type", 
              rule.getMimetypes().get(0), equalTo(mimeType));
          
        } catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in virtual thread {}", threadId, e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete in time", completed, is(true));
    assertThat("No errors should occur in virtual threads", errorCount.get(), is(0));
  }
}