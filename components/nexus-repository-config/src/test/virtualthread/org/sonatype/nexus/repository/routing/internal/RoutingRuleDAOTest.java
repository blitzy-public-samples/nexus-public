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
package org.sonatype.nexus.repository.routing.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.testsuite.testsupport.virtualthread.VirtualThreadTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import spock.lang.Specification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.repository.routing.RoutingMode.ALLOW;
import static org.sonatype.nexus.repository.routing.RoutingMode.BLOCK;

/**
 * Virtual Thread-specific tests for {@link RoutingRuleDAO}.
 * 
 * These tests verify that database operations work correctly with Java 21 Virtual Threads,
 * ensuring that operations don't cause thread pinning and that concurrent operations
 * scale efficiently with Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class RoutingRuleDAOTest
    extends Specification
    implements VirtualThreadTestSupport
{
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

  @Test
  public void testCRUDWithVirtualThreads() throws Exception {
    // Create a routing rule using a virtual thread
    RoutingRuleData routingRule = executeWithVirtualThread(() -> {
      RoutingRuleData rule = routingRule("foo", ALLOW, "desc", "a", "b", "c");
      dao.create(rule);
      return rule;
    });

    // Read the routing rule using a virtual thread
    RoutingRuleData read = executeWithVirtualThread(() -> {
      return dao.read(routingRule.getId()).orElse(null);
    });

    // Verify the read value matches the original
    assertThat(read.getId(), is(routingRule.getId()));
    assertThat(read.getName(), is(routingRule.getName()));
    assertThat(read.mode(), is(routingRule.mode()));
    assertThat(read.description(), is(routingRule.description()));
    assertThat(read.matchers(), is(routingRule.matchers()));

    // Find the routing rule by name using a virtual thread
    read = executeWithVirtualThread(() -> {
      return dao.readByName(routingRule.getName()).get();
    });

    // Verify the found value matches the original
    assertThat(read.getName(), is(routingRule.getName()));
    assertThat(read.mode(), is(routingRule.mode()));
    assertThat(read.description(), is(routingRule.description()));
    assertThat(read.matchers(), is(routingRule.matchers()));

    // Update the routing rule using a virtual thread
    executeWithVirtualThread(() -> {
      routingRule.name("foo2");
      routingRule.mode(BLOCK);
      routingRule.description("desc2");
      routingRule.matchers(List.of("x", "y", "z"));
      dao.update(routingRule);
      return null;
    });

    // Read the updated routing rule using a virtual thread
    RoutingRuleData update = executeWithVirtualThread(() -> {
      return dao.read(routingRule.getId()).orElse(null);
    });

    // Verify the read value matches the update
    assertThat(update.getName(), is(routingRule.getName()));
    assertThat(update.mode(), is(routingRule.mode()));
    assertThat(update.description(), is(routingRule.description()));
    assertThat(update.matchers(), is(routingRule.matchers()));

    // Find the updated routing rule by name using a virtual thread
    read = executeWithVirtualThread(() -> {
      return dao.readByName(routingRule.getName()).get();
    });

    // Verify the found value matches the update
    assertThat(read.getName(), is(routingRule.getName()));
    assertThat(read.mode(), is(routingRule.mode()));
    assertThat(read.description(), is(routingRule.description()));
    assertThat(read.matchers(), is(routingRule.matchers()));

    // Delete the routing rule using a virtual thread
    executeWithVirtualThread(() -> {
      dao.delete(routingRule.getId());
      return null;
    });

    // Verify the routing rule is deleted using a virtual thread
    boolean exists = executeWithVirtualThread(() -> {
      return dao.read(routingRule.getId()).isPresent();
    });
    assertFalse(exists);
  }

  @Test
  public void testConcurrentCreationWithVirtualThreads() throws Exception {
    // Create a large number of routing rules concurrently using virtual threads
    int numRules = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(numRules);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit tasks to create routing rules concurrently
      for (int i = 0; i < numRules; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            RoutingRuleData rule = routingRule("rule-" + index, ALLOW, "desc-" + index, "matcher-" + index);
            dao.create(rule);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));

      // Verify no errors occurred
      assertThat("Errors occurred during concurrent creation", errorCount.get(), is(0));

      // Browse all routing rules using a virtual thread
      Collection<RoutingRuleData> rules = executeWithVirtualThread(() -> {
        return (Collection<RoutingRuleData>) dao.browse();
      });

      // Verify all rules were created
      assertThat(rules, hasSize(numRules));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testConcurrentReadWithVirtualThreads() throws Exception {
    // Create a routing rule to read concurrently
    RoutingRuleData routingRule = routingRule("concurrent-read", ALLOW, "desc", "a", "b", "c");
    dao.create(routingRule);

    // Read the routing rule concurrently using virtual threads
    int numReads = 1000;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(numReads);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Record start time for performance measurement
      long startTime = System.currentTimeMillis();

      // Submit tasks to read the routing rule concurrently
      for (int i = 0; i < numReads; i++) {
        executor.submit(() -> {
          try {
            Optional<RoutingRuleData> read = dao.read(routingRule.getId());
            if (!read.isPresent() || !read.get().getName().equals(routingRule.getName())) {
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
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));

      // Record end time for performance measurement
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;

      // Verify no errors occurred
      assertThat("Errors occurred during concurrent reads", errorCount.get(), is(0));

      // Verify performance is reasonable (less than 5ms per operation on average)
      // This is a simple heuristic - actual performance will vary by environment
      double avgTimePerOperation = (double) duration / numReads;
      assertThat("Average time per operation too high: " + avgTimePerOperation + "ms",
          avgTimePerOperation, lessThan(5.0));

      // Clean up
      dao.delete(routingRule.getId());
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testConcurrentBrowseWithVirtualThreads() throws Exception {
    // Create several routing rules
    IntStream.range(0, 10).forEach(i -> {
      dao.create(routingRule("browse-" + i, ALLOW, "desc-" + i, "matcher-" + i));
    });

    // Browse routing rules concurrently using virtual threads
    int numBrowses = 100;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(numBrowses);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit tasks to browse routing rules concurrently
      for (int i = 0; i < numBrowses; i++) {
        executor.submit(() -> {
          try {
            Collection<RoutingRuleData> rules = (Collection<RoutingRuleData>) dao.browse();
            if (rules.size() != 10) {
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
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));

      // Verify no errors occurred
      assertThat("Errors occurred during concurrent browses", errorCount.get(), is(0));

      // Clean up
      Collection<RoutingRuleData> rules = (Collection<RoutingRuleData>) dao.browse();
      rules.forEach(rule -> dao.delete(rule.getId()));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testConcurrentUpdateWithVirtualThreads() throws Exception {
    // Create a routing rule to update concurrently
    RoutingRuleData routingRule = routingRule("concurrent-update", ALLOW, "desc", "a", "b", "c");
    dao.create(routingRule);

    // Create multiple copies of the routing rule with different names for concurrent updates
    int numUpdates = 50;
    List<RoutingRuleData> rulesToUpdate = IntStream.range(0, numUpdates)
        .mapToObj(i -> {
          RoutingRuleData rule = routingRule("update-" + i, ALLOW, "desc", "a", "b", "c");
          dao.create(rule);
          return rule;
        })
        .toList();

    // Update routing rules concurrently using virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(numUpdates);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit tasks to update routing rules concurrently
      for (int i = 0; i < numUpdates; i++) {
        final RoutingRuleData ruleToUpdate = rulesToUpdate.get(i);
        executor.submit(() -> {
          try {
            ruleToUpdate.description("updated-" + UUID.randomUUID());
            ruleToUpdate.matchers(List.of("updated-matcher"));
            dao.update(ruleToUpdate);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));

      // Verify no errors occurred
      assertThat("Errors occurred during concurrent updates", errorCount.get(), is(0));

      // Verify all updates were applied
      for (RoutingRuleData rule : rulesToUpdate) {
        Optional<RoutingRuleData> updated = dao.read(rule.getId());
        assertTrue(updated.isPresent());
        assertThat(updated.get().matchers(), is(List.of("updated-matcher")));
        assertTrue(updated.get().description().startsWith("updated-"));
      }

      // Clean up
      dao.delete(routingRule.getId());
      rulesToUpdate.forEach(rule -> dao.delete(rule.getId()));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testConcurrentDeleteWithVirtualThreads() throws Exception {
    // Create routing rules to delete concurrently
    int numRules = 50;
    List<RoutingRuleData> rulesToDelete = IntStream.range(0, numRules)
        .mapToObj(i -> {
          RoutingRuleData rule = routingRule("delete-" + i, ALLOW, "desc", "a", "b", "c");
          dao.create(rule);
          return rule;
        })
        .toList();

    // Delete routing rules concurrently using virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(numRules);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit tasks to delete routing rules concurrently
      for (RoutingRuleData ruleToDelete : rulesToDelete) {
        executor.submit(() -> {
          try {
            dao.delete(ruleToDelete.getId());
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));

      // Verify no errors occurred
      assertThat("Errors occurred during concurrent deletes", errorCount.get(), is(0));

      // Verify all rules were deleted
      Collection<RoutingRuleData> remainingRules = (Collection<RoutingRuleData>) dao.browse();
      assertThat(remainingRules, hasSize(0));
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void testDuplicateNameHandlingWithVirtualThreads() throws Exception {
    // Create a routing rule
    RoutingRuleData rule1 = routingRule("duplicate", ALLOW, "desc", "a", "b", "c");
    dao.create(rule1);

    // Attempt to create another rule with the same name using a virtual thread
    Exception exception = executeWithVirtualThread(() -> {
      RoutingRuleData rule2 = routingRule("duplicate", ALLOW, "desc2", "d", "e", "f");
      return assertThrows(Exception.class, () -> dao.create(rule2));
    });

    // Verify an exception was thrown
    assertTrue(exception instanceof Exception);

    // Clean up
    dao.delete(rule1.getId());
  }

  private static RoutingRuleData routingRule(final String name, final RoutingMode mode) {
    return new RoutingRuleData().name(name).mode(mode);
  }

  private static RoutingRuleData routingRule(
      final String name,
      final RoutingMode mode,
      final String description,
      final String... matchers)
  {
    return new RoutingRuleData().name(name).mode(mode).description(description).matchers(List.of(matchers));
  }
}