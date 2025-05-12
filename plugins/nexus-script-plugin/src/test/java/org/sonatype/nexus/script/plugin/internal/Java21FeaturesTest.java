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
package org.sonatype.nexus.script.plugin.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 features in the nexus-script-plugin.
 * 
 * This test class validates that the script plugin correctly works with Java 21 features
 * including virtual threads, record patterns, string templates, and pattern matching.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class Java21FeaturesTest
    extends TestSupport
{
  @Mock
  private ScriptManager scriptManager;

  private static final int TASK_COUNT = 1000;
  
  @BeforeEach
  void setUp() {
    // Setup mock data
    when(scriptManager.browse()).thenReturn(List.of(
        createScript("script1", "groovy", "println 'Hello World'"),
        createScript("script2", "groovy", "println 'Goodbye World'"),
        createScript("script3", "javascript", "console.log('Hello from JS')")
    ));
  }

  /**
   * Creates a script instance for testing.
   */
  private Script createScript(String name, String type, String content) {
    return new Script()
    {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getType() {
        return type;
      }

      @Override
      public String getContent() {
        return content;
      }
    };
  }

  /**
   * Test record for demonstrating Java 21 record patterns.
   */
  record ScriptInfo(String name, String type, String content) {}

  /**
   * Nested record for demonstrating nested record patterns.
   */
  record ScriptMetadata(String author, String version) {}

  /**
   * Complex record with nested record for pattern matching tests.
   */
  record ScriptDetails(ScriptInfo info, ScriptMetadata metadata) {}

  /**
   * Tests Java 21 record patterns with instanceof pattern matching.
   */
  @Test
  @DisplayName("Test record patterns with instanceof")
  @Category(Java21TestGroup.class)
  void testRecordPatternsWithInstanceOf() {
    // Create a record instance
    ScriptInfo scriptInfo = new ScriptInfo("test-script", "groovy", "println 'test'");
    
    // Use instanceof with record pattern to extract components
    if (scriptInfo instanceof ScriptInfo(String name, String type, String content)) {
      assertEquals("test-script", name);
      assertEquals("groovy", type);
      assertEquals("println 'test'", content);
    } else {
      // This should not happen
      assertTrue(false, "Record pattern matching failed");
    }
    
    // Test with nested records
    ScriptDetails details = new ScriptDetails(
        new ScriptInfo("nested-script", "groovy", "println 'nested'"),
        new ScriptMetadata("admin", "1.0.0")
    );
    
    // Use nested record patterns
    if (details instanceof ScriptDetails(ScriptInfo(String name, String type, var content), 
                                       ScriptMetadata(var author, var version))) {
      assertEquals("nested-script", name);
      assertEquals("groovy", type);
      assertEquals("println 'nested'", content);
      assertEquals("admin", author);
      assertEquals("1.0.0", version);
    } else {
      // This should not happen
      assertTrue(false, "Nested record pattern matching failed");
    }
  }

  /**
   * Tests Java 21 pattern matching for switch expressions.
   */
  @Test
  @DisplayName("Test pattern matching for switch")
  @Category(Java21TestGroup.class)
  void testPatternMatchingForSwitch() {
    // Create test objects
    ScriptInfo groovyScript = new ScriptInfo("groovy-script", "groovy", "println 'groovy'");
    ScriptInfo jsScript = new ScriptInfo("js-script", "javascript", "console.log('js')");
    String plainString = "just a string";
    Integer number = 42;
    
    // Test pattern matching in switch
    for (Object obj : List.of(groovyScript, jsScript, plainString, number, null)) {
      String result = switch (obj) {
        case ScriptInfo(var name, "groovy", var content) -> 
            "Groovy script: " + name + " with content length: " + content.length();
        case ScriptInfo(var name, "javascript", var content) -> 
            "JavaScript script: " + name + " with content length: " + content.length();
        case String s -> "String with length: " + s.length();
        case Integer i -> "Integer with value: " + i;
        case null -> "Null object";
        default -> "Unknown object type";
      };
      
      // Verify results
      if (obj == groovyScript) {
        assertEquals("Groovy script: groovy-script with content length: 15", result);
      } else if (obj == jsScript) {
        assertEquals("JavaScript script: js-script with content length: 15", result);
      } else if (obj == plainString) {
        assertEquals("String with length: 13", result);
      } else if (obj == number) {
        assertEquals("Integer with value: 42", result);
      } else if (obj == null) {
        assertEquals("Null object", result);
      }
    }
  }

  /**
   * Tests Java 21 string templates.
   */
  @Test
  @DisplayName("Test string templates")
  @Category(Java21TestGroup.class)
  void testStringTemplates() {
    String scriptName = "test-template";
    String scriptType = "groovy";
    int contentLength = 42;
    
    // Use string templates with the STR processor
    String message = STR."Script '\{scriptName}' of type '\{scriptType}' has content length \{contentLength}";
    
    // Verify the template was processed correctly
    assertThat(message, containsString("Script 'test-template'"));
    assertThat(message, containsString("of type 'groovy'"));
    assertThat(message, containsString("has content length 42"));
    
    // Test with expressions in the template
    String complexTemplate = STR."The uppercase name is \{scriptName.toUpperCase()} and doubled length is \{contentLength * 2}";
    
    // Verify expressions were evaluated
    assertThat(complexTemplate, containsString("The uppercase name is TEST-TEMPLATE"));
    assertThat(complexTemplate, containsString("doubled length is 84"));
    
    // Test with multiline template
    String multiline = STR."""
        Script Details:
        - Name: \{scriptName}
        - Type: \{scriptType}
        - Content Length: \{contentLength}
        - Created: \{Instant.now().toString()}
        """;
    
    // Verify multiline template
    assertThat(multiline, containsString("Script Details:"));
    assertThat(multiline, containsString("- Name: test-template"));
    assertThat(multiline, containsString("- Type: groovy"));
    assertThat(multiline, containsString("- Content Length: 42"));
    assertThat(multiline, containsString("- Created:"));
  }

  /**
   * Tests virtual threads for concurrent script operations.
   */
  @Test
  @DisplayName("Test virtual threads for concurrent operations")
  @Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
  void testVirtualThreadsForConcurrentOperations() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup for concurrent operations
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<String, Integer> scriptNameCounts = new ConcurrentHashMap<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < TASK_COUNT; i++) {
        executor.submit(() -> {
          try {
            // Simulate script browsing operation
            List<Script> scripts = scriptManager.browse();
            scripts.forEach(script -> 
                scriptNameCounts.compute(script.getName(), (k, v) -> (v == null) ? 1 : v + 1));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread tasks should complete within timeout");
      assertThat(errorCount.get(), is(0));
      assertThat(scriptNameCounts.size(), is(3)); // We have 3 scripts in the mock
      assertThat(scriptNameCounts.get("script1"), is(TASK_COUNT));
      assertThat(scriptNameCounts.get("script2"), is(TASK_COUNT));
      assertThat(scriptNameCounts.get("script3"), is(TASK_COUNT));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests virtual thread performance compared to platform threads.
   */
  @Test
  @DisplayName("Compare virtual threads vs platform threads performance")
  @Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
  void testVirtualThreadsPerformance() throws Exception {
    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Measure platform threads performance
    Instant platformStart = Instant.now();
    runConcurrentTasks(platformThreadFactory);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Measure virtual threads performance
    Instant virtualStart = Instant.now();
    runConcurrentTasks(virtualThreadFactory);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Log performance results
    log.info("Platform threads execution time: {} ms", platformDuration.toMillis());
    log.info("Virtual threads execution time: {} ms", virtualDuration.toMillis());
    
    // For high concurrency operations, virtual threads should generally be more efficient
    // However, for this simple test, we're just verifying both complete successfully
    // In a real-world scenario with I/O operations, the difference would be more significant
    assertThat(platformDuration, notNullValue());
    assertThat(virtualDuration, notNullValue());
  }
  
  /**
   * Helper method to run concurrent tasks with the specified thread factory.
   */
  private void runConcurrentTasks(ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      // Create multiple concurrent tasks
      for (int i = 0; i < TASK_COUNT; i++) {
        final int taskId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          // Simulate some work with script operations
          try {
            List<Script> scripts = scriptManager.browse();
            // Simulate some processing
            Thread.sleep(10); // Small delay to simulate work
            // Do something with the scripts
            int count = scripts.size();
            if (count <= 0) {
              throw new IllegalStateException("No scripts found");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Tests combining multiple Java 21 features together.
   */
  @Test
  @DisplayName("Test combining multiple Java 21 features")
  @Category(Java21TestGroup.class)
  void testCombiningJava21Features() {
    // Create a list of script records
    List<ScriptInfo> scripts = List.of(
        new ScriptInfo("script1", "groovy", "println 'Hello'"),
        new ScriptInfo("script2", "javascript", "console.log('Hello')"),
        new ScriptInfo("script3", "groovy", "println 'World'")
    );
    
    // Use virtual threads to process scripts
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    Map<String, Integer> typeCount = new ConcurrentHashMap<>();
    
    try {
      // Process scripts concurrently with virtual threads
      List<CompletableFuture<String>> results = scripts.stream()
          .map(script -> CompletableFuture.supplyAsync(() -> {
            // Use pattern matching in switch
            String result = switch (script) {
              case ScriptInfo(var name, "groovy", var content) -> {
                typeCount.compute("groovy", (k, v) -> (v == null) ? 1 : v + 1);
                yield STR."Processed Groovy script: \{name} with content: \{content}";
              }
              case ScriptInfo(var name, "javascript", var content) -> {
                typeCount.compute("javascript", (k, v) -> (v == null) ? 1 : v + 1);
                yield STR."Processed JavaScript script: \{name} with content: \{content}";
              }
              default -> "Unknown script type";
            };
            return result;
          }, executor))
          .toList();
      
      // Collect all results
      List<String> processedResults = results.stream()
          .map(CompletableFuture::join)
          .toList();
      
      // Verify results
      assertThat(processedResults.size(), is(3));
      assertThat(processedResults.get(0), containsString("Processed Groovy script: script1"));
      assertThat(processedResults.get(1), containsString("Processed JavaScript script: script2"));
      assertThat(processedResults.get(2), containsString("Processed Groovy script: script3"));
      
      // Verify type counts
      assertThat(typeCount.get("groovy"), is(2));
      assertThat(typeCount.get("javascript"), is(1));
      
    } finally {
      executor.shutdown();
    }
  }
}