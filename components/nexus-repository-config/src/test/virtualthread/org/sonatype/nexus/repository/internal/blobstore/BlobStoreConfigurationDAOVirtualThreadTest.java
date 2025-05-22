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
package org.sonatype.nexus.repository.internal.blobstore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Test for {@link BlobStoreConfigurationDAO} using Java 21 Virtual Threads.
 * This test validates that the DAO operations work correctly under high concurrency
 * with virtual threads and checks for thread pinning issues.
 */
@Category(VirtualThreadTestGroup.class)
public class BlobStoreConfigurationDAOVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 100;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule().access(BlobStoreConfigurationDAO.class);

  private DataSession<?> session;
  private BlobStoreConfigurationDAO mapper;
  private ExecutorService executorService;

  @Before
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    mapper = session.access(BlobStoreConfigurationDAO.class);
    
    // Create an executor service using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    executorService = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
  
  @After
  public void tearDown() throws Exception {
    if (executorService != null) {
      executorService.shutdown();
      if (!executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    }
  }

  /**
   * Test concurrent browse operations with virtual threads.
   */
  @Test
  public void testConcurrentBrowse() throws Exception {
    // Create some test data
    IntStream.range(1, 11).forEach(it -> mapper.create(blobStore("name-" + it, "type-" + it, new HashMap<>())));
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run concurrent browse operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      executorService.submit(() -> {
        try {
          Collection<BlobStoreConfigurationData> items = collect(mapper.browse());
          // Verify that we can see all 10 items
          if (items.size() != 10) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in concurrent browse", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent browse operations", errorCount.get(), is(0));
  }

  /**
   * Test concurrent create operations with virtual threads.
   */
  @Test
  public void testConcurrentCreate() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run concurrent create operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      executorService.submit(() -> {
        try {
          BlobStoreConfigurationData config = blobStore("concurrent-" + index, "type-" + index, new HashMap<>());
          mapper.create(config);
        } catch (Exception e) {
          log.error("Error in concurrent create", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent create operations", errorCount.get(), is(0));
    
    // Verify that all items were created
    Collection<BlobStoreConfigurationData> items = collect(mapper.browse());
    assertThat(items, hasSize(THREAD_COUNT));
  }

  /**
   * Test concurrent read operations with virtual threads.
   */
  @Test
  public void testConcurrentRead() throws Exception {
    // Create test data
    BlobStoreConfigurationData config = blobStore("read-test", "read-type", new HashMap<>());
    mapper.create(config);
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run concurrent read operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      executorService.submit(() -> {
        try {
          Optional<BlobStoreConfigurationData> readConfig = mapper.readByName("read-test");
          if (!readConfig.isPresent() || !"read-test".equals(readConfig.get().getName())) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in concurrent read", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent read operations", errorCount.get(), is(0));
  }

  /**
   * Test concurrent update operations with virtual threads.
   */
  @Test
  public void testConcurrentUpdate() throws Exception {
    // Create test data
    for (int i = 0; i < THREAD_COUNT; i++) {
      BlobStoreConfigurationData config = blobStore("update-" + i, "original-type", new HashMap<>());
      mapper.create(config);
    }
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run concurrent update operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      executorService.submit(() -> {
        try {
          Optional<BlobStoreConfigurationData> readConfig = mapper.readByName("update-" + index);
          if (readConfig.isPresent()) {
            BlobStoreConfigurationData config = readConfig.get();
            config.setType("updated-type-" + index);
            mapper.update(config);
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in concurrent update", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent update operations", errorCount.get(), is(0));
    
    // Verify that all items were updated
    for (int i = 0; i < THREAD_COUNT; i++) {
      Optional<BlobStoreConfigurationData> config = mapper.readByName("update-" + i);
      assertTrue(config.isPresent());
      assertThat(config.get().getType(), is("updated-type-" + i));
    }
  }

  /**
   * Test concurrent delete operations with virtual threads.
   */
  @Test
  public void testConcurrentDelete() throws Exception {
    // Create test data
    for (int i = 0; i < THREAD_COUNT; i++) {
      BlobStoreConfigurationData config = blobStore("delete-" + i, "delete-type", new HashMap<>());
      mapper.create(config);
    }
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run concurrent delete operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      executorService.submit(() -> {
        try {
          boolean deleted = mapper.deleteByName("delete-" + index);
          if (!deleted) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in concurrent delete", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent delete operations", errorCount.get(), is(0));
    
    // Verify that all items were deleted
    Collection<BlobStoreConfigurationData> items = collect(mapper.browse());
    assertThat(items, hasSize(0));
  }

  /**
   * Test concurrent parent-finding operations with virtual threads.
   */
  @Test
  public void testConcurrentFindParent() throws Exception {
    // Create test data - members and a parent group
    List<String> memberNames = List.of("A", "B", "C");
    
    List<BlobStoreConfigurationData> members = memberNames.stream()
        .map(it -> blobStore(it, "file", new HashMap<>()))
        .toList();
    
    BlobStoreConfigurationData parentConfig =
        blobStore("parent", BlobStoreGroup.TYPE, Map.of("group", Map.of("members", memberNames)));
    
    members.forEach(mapper::create);
    mapper.create(parentConfig);
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> testMembers = new ArrayList<>(memberNames);
    
    // Run concurrent findCandidateParents operations with virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i % testMembers.size();
      executorService.submit(() -> {
        try {
          String memberName = testMembers.get(index);
          Collection<BlobStoreConfiguration> parents = mapper.findCandidateParents(memberName);
          
          // Verify that we found the parent
          if (parents.isEmpty()) {
            errorCount.incrementAndGet();
          } else {
            BlobStoreConfiguration parent = parents.iterator().next();
            if (!"parent".equals(parent.getName())) {
              errorCount.incrementAndGet();
            }
          }
        } catch (Exception e) {
          log.error("Error in concurrent findCandidateParents", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during concurrent findCandidateParents operations", errorCount.get(), is(0));
  }
  
  /**
   * Test to detect thread pinning issues when running with virtual threads.
   * This test will only detect pinning if the JVM is run with -Djdk.tracePinnedThreads=full
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Create test data
    BlobStoreConfigurationData config = blobStore("pinning-test", "pinning-type", new HashMap<>());
    mapper.create(config);
    
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Run operations that might cause pinning
    for (int i = 0; i < THREAD_COUNT; i++) {
      executorService.submit(() -> {
        try {
          // Perform a database operation that might cause pinning if not properly implemented
          Optional<BlobStoreConfigurationData> readConfig = mapper.readByName("pinning-test");
          if (readConfig.isPresent()) {
            // Simulate some processing time to increase chance of detecting pinning
            Thread.sleep(10);
            
            // Perform another operation
            Collection<BlobStoreConfigurationData> items = collect(mapper.browse());
            if (items.isEmpty()) {
              errorCount.incrementAndGet();
            }
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in pinning detection test", e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for threads to complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify no errors occurred
    assertThat("Errors occurred during pinning detection test", errorCount.get(), is(0));
    
    // Note: Actual pinning detection happens via JVM logs when run with -Djdk.tracePinnedThreads=full
    log.info("Check JVM logs for thread pinning warnings if running with -Djdk.tracePinnedThreads=full");
  }

  private static <E> Collection<E> collect(final Iterable<E> iterable) {
    return (Collection<E>) iterable;
  }

  private static BlobStoreConfigurationData blobStore(
      final String name,
      final String type,
      final Map<String, Map<String, Object>> attributes)
  {
    BlobStoreConfigurationData data = new BlobStoreConfigurationData();
    data.setName(name);
    data.setType(type);
    data.setAttributes(attributes);
    return data;
  }
}