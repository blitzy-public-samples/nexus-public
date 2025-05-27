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
package org.sonatype.nexus.cleanup.internal.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionExtension;

import com.google.common.collect.Iterables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

public class CleanupPolicyDAOTest
{
  @RegisterExtension
  public DataSessionExtension sessionRule = new DataSessionExtension().access(CleanupPolicyDAO.class);

  private DataSession<?> session;

  private CleanupPolicyDAO dao;

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(CleanupPolicyDAO.class);
  }

  @AfterEach
  public void cleanup() {
    session.close();
  }

  @Test
  public void testCRUD() {
    // a cleanup policy
    CleanupPolicyData policy = policy("foo", "some text", "maven2", "deletion", Map.of("bar", "one", "baz", "two"));
    dao.create(policy);

    // it is read
    Optional<CleanupPolicy> readOptional = dao.read(policy.getName());
    assertTrue(readOptional.isPresent(), "Policy should be present after creation");
    
    // Using pattern matching with instanceof for cleaner optional value handling
    if (readOptional.isPresent() && readOptional.get() instanceof CleanupPolicy read) {
      // it matches the original policy
      assertAll("Policy should match the original policy",
          () -> assertThat(read.getName(), is(policy.getName())),
          () -> assertThat(read.getNotes(), is(policy.getNotes())),
          () -> assertThat(read.getFormat(), is(policy.getFormat())),
          () -> assertThat(read.getMode(), is(policy.getMode())),
          () -> assertThat(read.getCriteria(), is(Map.of("bar", "one", "baz", "two")))
      );
    }

    // the policy is updated
    policy.setNotes("some other text");
    policy.setFormat("npm");
    policy.setMode("other");
    policy.setCriteria(Map.of("one", "baz", "two", "bar"));
    dao.update(policy);
    
    // it is read
    Optional<CleanupPolicy> updateOptional = dao.read(policy.getName());
    assertTrue(updateOptional.isPresent(), "Policy should be present after update");
    
    // Using pattern matching with instanceof for cleaner optional value handling
    if (updateOptional.isPresent() && updateOptional.get() instanceof CleanupPolicy update) {
      // it matches the updated policy
      assertAll("Policy should match the updated policy",
          () -> assertThat(update.getName(), is(policy.getName())),
          () -> assertThat(update.getNotes(), is(policy.getNotes())),
          () -> assertThat(update.getFormat(), is(policy.getFormat())),
          () -> assertThat(update.getMode(), is(policy.getMode()))
      );
    }
    policy.setCriteria(Map.of("one", "baz", "two", "bar"));

    // the policy is deleted
    dao.delete(policy.getName());
    // it cannot be read anymore
    assertFalse(dao.read(policy.getName()).isPresent(), "Policy should not be present after deletion");

    // several policies are created
    IntStream.range(1, 6)
        .forEach(
            it -> dao.create(policy("foo" + it, "some text " + it, "maven" + it, "mode" + it, Map.of("bar", "" + it))));

    // browsing finds them all
    assertThat(dao.count(), is(5));
    Collection<CleanupPolicyData> items = collect(dao.browse());
    assertThat(items, hasSize(5));

    // getting by format
    List<CleanupPolicy> policies = collect(dao.browseByFormat("maven5"));

    // it finds the correct policies
    assertThat(policies, hasSize(1));

    CleanupPolicy found = Iterables.getFirst(policies, null);
    assertNotNull(found, "Found policy should not be null");
    assertAll("Found policy should match expected values",
        () -> assertThat(found.getName(), is("foo5")),
        () -> assertThat(found.getNotes(), is("some text 5")),
        () -> assertThat(found.getFormat(), is("maven5")),
        () -> assertThat(found.getMode(), is("mode5")),
        () -> assertThat(found.getCriteria(), hasEntry("bar", "5"))
    );
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void testConcurrentCRUDWithVirtualThreads() throws Exception {
    // Use Virtual Threads for concurrent operations
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int threadCount = 50;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Perform concurrent CRUD operations
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a policy
            String name = "vt-policy-" + index;
            CleanupPolicyData policy = policy(name, "VT test " + index, "format" + index, 
                "mode" + index, Map.of("key", "value" + index));
            dao.create(policy);
            
            // Read the policy
            Optional<CleanupPolicy> readOptional = dao.read(name);
            if (readOptional.isPresent() && readOptional.get().getName().equals(name)) {
              // Update the policy
              policy.setNotes("Updated VT test " + index);
              dao.update(policy);
              
              // Verify update
              Optional<CleanupPolicy> updatedOptional = dao.read(name);
              if (updatedOptional.isPresent() && 
                  updatedOptional.get().getNotes().equals("Updated VT test " + index)) {
                // Delete the policy
                dao.delete(name);
                
                // Verify deletion
                if (!dao.read(name).isPresent()) {
                  successCount.incrementAndGet();
                }
              }
            }
          } catch (Exception e) {
            // Log exception but don't fail the test
            System.err.println("Error in virtual thread operation: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(threadCount));
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void testConcurrentReadWithVirtualThreads() throws Exception {
    // Create test data
    for (int i = 1; i <= 10; i++) {
      dao.create(policy("concurrent-" + i, "Concurrent test " + i, "format", "mode", 
          Map.of("index", String.valueOf(i))));
    }
    
    // Use Virtual Threads for concurrent reads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Perform concurrent read operations
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Read all policies
            Collection<CleanupPolicyData> allPolicies = collect(dao.browse());
            if (allPolicies.size() == 10) {
              // Read a specific policy
              int policyNum = (int)(Math.random() * 10) + 1;
              Optional<CleanupPolicy> policy = dao.read("concurrent-" + policyNum);
              if (policy.isPresent()) {
                successCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            System.err.println("Error in concurrent read: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(threadCount));
      
      // Clean up test data
      for (int i = 1; i <= 10; i++) {
        dao.delete("concurrent-" + i);
      }
    } finally {
      executor.shutdown();
    }
  }

  private static <E> List<E> collect(final Iterable<E> policies) {
    if (policies instanceof List) {
      return (List<E>) policies;
    }
    return StreamSupport.stream(policies.spliterator(), false).toList();
  }

  private static CleanupPolicyData policy(
      final String name,
      final String notes,
      final String format,
      final String mode,
      final Map<String, String> criteria)
  {
    CleanupPolicyData policy = new CleanupPolicyData();
    policy.setName(name);
    policy.setNotes(notes);
    policy.setFormat(format);
    policy.setMode(mode);
    policy.setCriteria(criteria);
    return policy;
  }
}