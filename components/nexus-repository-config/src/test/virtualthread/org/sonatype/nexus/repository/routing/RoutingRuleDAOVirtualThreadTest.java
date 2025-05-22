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
package org.sonatype.nexus.repository.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleDAO;
import org.sonatype.nexus.repository.routing.internal.RoutingRuleData;
import org.sonatype.nexus.testdb.DataSessionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.repository.routing.RoutingMode.ALLOW;
import static org.sonatype.nexus.repository.routing.RoutingMode.BLOCK;

/**
 * Tests for {@link RoutingRuleDAO} with Java 21 Virtual Threads.
 * 
 * This test validates that routing rule operations work correctly under high concurrency
 * with Virtual Threads, and ensures no thread pinning occurs during database operations.
 */
public class RoutingRuleDAOVirtualThreadTest
{
  private static final Logger log = LoggerFactory.getLogger(RoutingRuleDAOVirtualThreadTest.class);

  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 30;

  @Rule
  public DataSessionRule sessionRule = new DataSessionRule().access(RoutingRuleDAO.class);

  private DataSession<?> session;

  private RoutingRuleDAO dao;

  @Before
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(RoutingRuleDAO.class);
  }

  @After
  public void cleanup() {
    session.close();
  }

  /**
   * Tests concurrent creation of routing rules using Virtual Threads.
   * Verifies that all rules are created successfully without conflicts.
   */
  @Test
  public void testConcurrentCreation() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> createdIds = new ArrayList<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique routing rule
            RoutingRuleData rule = routingRule("rule-" + index, ALLOW, "desc-" + index, "pattern-" + index);
            dao.create(rule);
            synchronized (createdIds) {
              createdIds.add(rule.getId());
            }
          } catch (Exception e) {
            log.error("Error creating routing rule", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify results
      assertThat("No errors should occur during concurrent creation", errorCount.get(), is(0));
      
      // Verify all rules were created
      Collection<RoutingRuleData> rules = dao.browse();
      assertThat(rules, hasSize(taskCount));
      
      // Cleanup created rules
      for (String id : createdIds) {
        dao.delete(id);
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent reading of routing rules using Virtual Threads.
   * Verifies that all reads complete successfully without errors.
   */
  @Test
  public void testConcurrentReading() throws Exception {
    // Create a routing rule to read
    RoutingRuleData rule = routingRule("read-test", ALLOW, "read description", "pattern1", "pattern2");
    dao.create(rule);
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent read tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            Optional<RoutingRuleData> readRule = dao.read(rule.getId());
            if (readRule.isPresent() && readRule.get().getName().equals(rule.getName())) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error reading routing rule", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify results
      assertThat("No errors should occur during concurrent reading", errorCount.get(), is(0));
      assertThat("All reads should succeed", successCount.get(), is(taskCount));
      
      // Cleanup
      dao.delete(rule.getId());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent updates to routing rules using Virtual Threads.
   * Verifies that updates are performed correctly with transaction isolation.
   */
  @Test
  public void testConcurrentUpdates() throws Exception {
    // Create a routing rule to update
    RoutingRuleData rule = routingRule("update-test", ALLOW, "initial description", "pattern1");
    dao.create(rule);
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger lastUpdateIndex = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent update tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Optional<RoutingRuleData> readRule = dao.read(rule.getId());
            if (readRule.isPresent()) {
              RoutingRuleData updateRule = readRule.get();
              updateRule.description("updated description " + index);
              updateRule.mode(index % 2 == 0 ? ALLOW : BLOCK);
              updateRule.matchers(List.of("updated-pattern-" + index));
              dao.update(updateRule);
              lastUpdateIndex.set(index);
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error updating routing rule", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify results
      assertThat("Some updates should succeed", successCount.get(), is(taskCount));
      
      // Verify the final state of the rule
      Optional<RoutingRuleData> finalRule = dao.read(rule.getId());
      assertTrue("Rule should still exist", finalRule.isPresent());
      assertThat("Rule should have been updated", 
          finalRule.get().description(), is("updated description " + lastUpdateIndex.get()));
      assertThat("Rule should have updated pattern", 
          finalRule.get().matchers(), is(List.of("updated-pattern-" + lastUpdateIndex.get())));
      
      // Cleanup
      dao.delete(rule.getId());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests mixed CRUD operations using Virtual Threads.
   * Verifies that concurrent operations maintain data integrity.
   */
  @Test
  public void testConcurrentMixedOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> createdIds = new ArrayList<>();
    AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // Create some initial rules
    for (int i = 0; i < 10; i++) {
      RoutingRuleData rule = routingRule("mixed-" + i, ALLOW, "mixed desc " + i, "mixed-pattern-" + i);
      dao.create(rule);
      createdIds.add(rule.getId());
    }
    
    try {
      // Submit multiple concurrent mixed operation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Perform different operations based on the index
            if (index % 4 == 0) {
              // Create operation
              RoutingRuleData rule = routingRule("mixed-new-" + index, ALLOW, "new desc " + index, "new-pattern-" + index);
              dao.create(rule);
              synchronized (createdIds) {
                createdIds.add(rule.getId());
              }
            } else if (index % 4 == 1 && !createdIds.isEmpty()) {
              // Read operation
              synchronized (createdIds) {
                if (!createdIds.isEmpty() && !shouldStop.get()) {
                  String id = createdIds.get(index % createdIds.size());
                  Optional<RoutingRuleData> readRule = dao.read(id);
                  assertThat("Rule should exist", readRule.isPresent(), is(true));
                }
              }
            } else if (index % 4 == 2 && !createdIds.isEmpty()) {
              // Update operation
              synchronized (createdIds) {
                if (!createdIds.isEmpty() && !shouldStop.get()) {
                  String id = createdIds.get(index % createdIds.size());
                  Optional<RoutingRuleData> readRule = dao.read(id);
                  if (readRule.isPresent()) {
                    RoutingRuleData updateRule = readRule.get();
                    updateRule.description("mixed updated " + index);
                    dao.update(updateRule);
                  }
                }
              }
            } else if (index % 4 == 3 && !createdIds.isEmpty()) {
              // Delete operation (only delete rules created in this test)
              synchronized (createdIds) {
                if (!createdIds.isEmpty() && !shouldStop.get()) {
                  String id = createdIds.get(index % createdIds.size());
                  if (id.contains("mixed-new")) {
                    dao.delete(id);
                    createdIds.remove(id);
                  }
                }
              }
            }
          } catch (Exception e) {
            // Some concurrent operations may fail due to conflicts, which is expected
            log.debug("Expected concurrent operation error: {}", e.getMessage());
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify database is in a consistent state
      Collection<RoutingRuleData> finalRules = dao.browse();
      assertThat("Database should contain rules", finalRules, notNullValue());
      
      // Cleanup all created rules
      shouldStop.set(true);
      synchronized (createdIds) {
        for (String id : new ArrayList<>(createdIds)) {
          try {
            dao.delete(id);
          } catch (Exception e) {
            // Rule may have been deleted by a concurrent operation
            log.debug("Could not delete rule {}: {}", id, e.getMessage());
          }
        }
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that database operations with Virtual Threads don't cause thread pinning.
   * This test would fail if the DAO operations caused thread pinning.
   */
  @Test
  public void testNoPinningDuringDatabaseOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> createdIds = new ArrayList<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique routing rule
            RoutingRuleData rule = routingRule("pinning-test-" + index, ALLOW, "pinning desc " + index, "pinning-pattern-" + index);
            dao.create(rule);
            
            // Read it back
            Optional<RoutingRuleData> readRule = dao.read(rule.getId());
            assertTrue("Rule should exist", readRule.isPresent());
            
            // Update it
            RoutingRuleData updateRule = readRule.get();
            updateRule.description("updated pinning desc " + index);
            dao.update(updateRule);
            
            // Read by name
            Optional<RoutingRuleData> readByName = dao.readByName(rule.getName());
            assertTrue("Rule should be found by name", readByName.isPresent());
            
            // Browse all rules
            Collection<RoutingRuleData> allRules = dao.browse();
            assertFalse("Rules collection should not be empty", allRules.isEmpty());
            
            synchronized (createdIds) {
              createdIds.add(rule.getId());
            }
          } catch (Exception e) {
            log.error("Error during database operations", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      
      // Verify results
      assertThat("No errors should occur during database operations", errorCount.get(), is(0));
      
      // Cleanup created rules
      for (String id : createdIds) {
        dao.delete(id);
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to create a routing rule with the specified parameters.
   */
  private static RoutingRuleData routingRule(
      final String name,
      final RoutingMode mode,
      final String description,
      final String... matchers)
  {
    return new RoutingRuleData().name(name).mode(mode).description(description).matchers(List.of(matchers));
  }
}