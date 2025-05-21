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
package org.sonatype.nexus.security.config;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.config.memory.MemoryCUserRoleMapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class MemorySecurityConfigurationTest
    extends TestSupport
{
  static List<Arguments> userRoleMappingParams() {
    return List.of(
        arguments("default", false),
        arguments("ldap", true),
        arguments("crowd", true),
        arguments("other", false)
    );
  }

  private MemorySecurityConfiguration config;

  @BeforeEach
  public void setup() {
    config = new MemorySecurityConfiguration();
  }

  @ParameterizedTest(name = "userRoleMappings for source: '{0}' read isFound: {1}")
  @MethodSource("userRoleMappingParams")
  public void testGetUserRoleMapping(String src, boolean isFound) {
    MemoryCUserRoleMapping newUserRoleMapping =
        new MemoryCUserRoleMapping().withUserId("userid").withSource(src).withRoles("test-role");
    config.addUserRoleMapping(newUserRoleMapping);

    CUserRoleMapping roleMapping = config.getUserRoleMapping("USERID", src);

    assertThat(roleMapping != null, is(isFound));

    if (isFound) {
      roleMapping.setRoles(newUserRoleMapping.getRoles());
    }
  }
  
  /**
   * Test concurrent operations on MemorySecurityConfiguration using Virtual Threads.
   * This test validates that the configuration can handle multiple concurrent read/write operations
   * without data corruption or concurrency issues.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Add a user role mapping
            String userId = "user" + index;
            String source = (index % 4 == 0) ? "default" : 
                           (index % 4 == 1) ? "ldap" : 
                           (index % 4 == 2) ? "crowd" : "other";
            
            MemoryCUserRoleMapping mapping = new MemoryCUserRoleMapping()
                .withUserId(userId)
                .withSource(source)
                .withRoles("role" + index);
                
            config.addUserRoleMapping(mapping);
            
            // Read it back to verify
            CUserRoleMapping retrieved = config.getUserRoleMapping(userId, source);
            
            if (retrieved == null) {
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
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread tasks should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
      
      // Verify the total count of mappings
      assertEquals(taskCount, config.getUserRoleMappings().size(), 
          "All user role mappings should be successfully added");
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test concurrent read operations on MemorySecurityConfiguration using Virtual Threads.
   * This test validates that the configuration can handle multiple concurrent read operations
   * efficiently using virtual threads.
   */
  @Test
  public void testConcurrentReadOperationsWithVirtualThreads() throws Exception {
    // Prepare test data
    List<String> sources = List.of("default", "ldap", "crowd", "other");
    List<MemoryCUserRoleMapping> testMappings = new ArrayList<>();
    
    // Add 100 user role mappings
    for (int i = 0; i < 100; i++) {
      String userId = "user" + i;
      String source = sources.get(i % sources.size());
      MemoryCUserRoleMapping mapping = new MemoryCUserRoleMapping()
          .withUserId(userId)
          .withSource(source)
          .withRoles("role" + i);
      
      config.addUserRoleMapping(mapping);
      testMappings.add(mapping);
    }
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int readOperations = 1000;
    CountDownLatch latch = new CountDownLatch(readOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent read tasks using virtual threads
      for (int i = 0; i < readOperations; i++) {
        final int index = i % testMappings.size();
        executor.submit(() -> {
          try {
            MemoryCUserRoleMapping mapping = testMappings.get(index);
            CUserRoleMapping retrieved = config.getUserRoleMapping(mapping.getUserId(), mapping.getSource());
            
            if (retrieved == null) {
              errorCount.incrementAndGet();
            } else if (!retrieved.getRoles().equals(mapping.getRoles())) {
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
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All virtual thread read tasks should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent read operations");
    } finally {
      executor.shutdown();
    }
  }
}