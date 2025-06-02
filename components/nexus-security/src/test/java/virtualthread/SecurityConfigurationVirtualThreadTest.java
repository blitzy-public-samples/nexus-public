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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.MutableTestSecurityContributor;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.config.SecurityContributor;
import org.sonatype.nexus.security.config.TestSecurityContributor2;
import org.sonatype.nexus.security.config.TestSecurityContributor3;
import org.sonatype.nexus.security.config.InitialSecurityConfiguration;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests concurrent loading, merging, and access of security configurations under Java 21's Virtual Thread execution model.
 * This validates that configuration operations remain thread-safe and benefit from the improved concurrency of Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class SecurityConfigurationVirtualThreadTest
    extends AbstractSecurityTest
{
  private SecurityConfigurationManager manager;

  private int expectedPrivilegeCount = 0;

  @Inject
  private List<SecurityContributor> testContributors;

  @Inject
  private List<MutableTestSecurityContributor> mutableTestContributors;

  @Inject
  private EventManager eventManager;

  // Number of virtual threads to use in tests
  private static final int VIRTUAL_THREAD_COUNT = 5000;
  
  // Timeout for waiting on test completion
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return InitialSecurityConfiguration.getConfiguration();
  }

  @Override
  protected void customizeModules(final List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        bindStaticContributor("static-default", new TestSecurityContributor2());
        bindDynamicContributor("dynamic-default", new MutableTestSecurityContributor());

        int staticResourceCount = 100;
        for (int ii = 0; ii < staticResourceCount - 1; ii++) { // 99 more
          bindStaticContributor("static-" + ii, new TestSecurityContributor3());
        }

        int dynamicResourceCount = 100;
        for (int ii = 0; ii < dynamicResourceCount - 1; ii++) { // 99 more
          bindDynamicContributor("dynamic-" + ii, new MutableTestSecurityContributor());
        }
      }

      private void bindStaticContributor(final String name, final SecurityContributor instance) {
        bind(SecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
      }

      private void bindDynamicContributor(final String name, final MutableTestSecurityContributor instance) {
        bind(MutableTestSecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
        bind(SecurityContributor.class).annotatedWith(Names.named(name)).toInstance(instance);
      }
    });
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    this.manager = lookup(SecurityConfigurationManager.class);

    // mimic EventManager auto-registration
    eventManager.register(manager);

    // test the lookup, make sure we have 200
    Assert.assertEquals(200, testContributors.size());

    this.expectedPrivilegeCount = this.manager.listPrivileges().size();

    // 100 static items with 3 privs each + 100 dynamic items + 2 from default config
    Assert.assertEquals((100 * 3) + 100 + 2, expectedPrivilegeCount);
  }

  /**
   * Tests concurrent loading of security configuration with thousands of virtual threads.
   * This validates that the configuration loading operations remain thread-safe and
   * benefit from the improved concurrency of Virtual Threads.
   */
  @Test
  public void testConcurrentLoadingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean testCompleted = new AtomicBoolean(false);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int index = i % mutableTestContributors.size();
        executor.submit(() -> {
          try {
            // Mark contributor as dirty to force configuration reload
            if (index < mutableTestContributors.size()) {
              mutableTestContributors.get(index).setDirty(true);
            }
            
            // Verify privilege count remains consistent
            Assert.assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
          } catch (Exception e) {
            errorCount.incrementAndGet();
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      testCompleted.set(true);
      
      // Verify results
      Assert.assertTrue("Test timed out before all virtual threads completed", completed);
      Assert.assertEquals("No errors should occur during concurrent operations", 0, errorCount.get());
      
      // Verify all contributors were accessed
      for (MutableTestSecurityContributor contributor : mutableTestContributors) {
        Assert.assertTrue(
            "Get config should be called on each contributor after any changed: " + contributor.getId(),
            contributor.wasConfigRequested());
      }
    } finally {
      executor.shutdown();
      if (!testCompleted.get()) {
        System.err.println("Test did not complete normally, shutting down executor");
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests concurrent contributor merging operations with virtual threads.
   * This validates that the security configuration merging remains consistent
   * under high concurrent access patterns.
   */
  @Test
  public void testConcurrentContributorMergingWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a list to track which contributors were modified
    List<Integer> modifiedContributors = new ArrayList<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int index = i % mutableTestContributors.size();
        
        // Track which contributors we're modifying
        if (!modifiedContributors.contains(index)) {
          modifiedContributors.add(index);
        }
        
        executor.submit(() -> {
          try {
            // Mark contributor as dirty to force configuration reload
            if (index < mutableTestContributors.size()) {
              mutableTestContributors.get(index).setDirty(true);
            }
            
            // Access the security configuration to trigger merging
            manager.listPrivileges();
          } catch (Exception e) {
            errorCount.incrementAndGet();
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      
      // Verify results
      Assert.assertTrue("Test timed out before all virtual threads completed", completed);
      Assert.assertEquals("No errors should occur during concurrent operations", 0, errorCount.get());
      
      // Verify the final privilege count is still correct
      Assert.assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      
      // Verify all modified contributors were accessed
      for (Integer index : modifiedContributors) {
        Assert.assertTrue(
            "Get config should be called on modified contributor: " + mutableTestContributors.get(index).getId(),
            mutableTestContributors.get(index).wasConfigRequested());
      }
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent CRUD operations on security configuration entities with virtual threads.
   * This validates that the security configuration remains consistent under concurrent modifications.
   */
  @Test
  public void testConcurrentCrudOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int taskType = i % 4; // 4 different operation types
        
        executor.submit(() -> {
          try {
            switch (taskType) {
              case 0: // Read operation
                manager.listPrivileges();
                break;
              case 1: // Read operation with different method
                manager.listRoles();
                break;
              case 2: // Read operation with different method
                manager.listUsers();
                break;
              case 3: // Trigger configuration reload
                int index = (int) (Math.random() * mutableTestContributors.size());
                mutableTestContributors.get(index).setDirty(true);
                manager.listPrivileges();
                break;
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            System.err.println("Error in virtual thread: " + e.getMessage());
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      
      // Verify results
      Assert.assertTrue("Test timed out before all virtual threads completed", completed);
      Assert.assertEquals("No errors should occur during concurrent operations", 0, errorCount.get());
      
      // Verify the final privilege count is still correct
      Assert.assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for security configuration operations.
   * This benchmark demonstrates the efficiency benefits of using Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testThreadPerformanceComparison() throws Exception {
    // Number of threads to use for the comparison test
    final int THREAD_COUNT = 1000;
    
    // Run benchmark with platform threads
    long platformThreadTime = runBenchmark(Thread.ofPlatform().factory(), THREAD_COUNT);
    
    // Run benchmark with virtual threads
    long virtualThreadTime = runBenchmark(Thread.ofVirtual().factory(), THREAD_COUNT);
    
    System.out.println("Platform Thread execution time (ms): " + platformThreadTime);
    System.out.println("Virtual Thread execution time (ms): " + virtualThreadTime);
    
    // Virtual threads should generally be more efficient, but we don't assert this
    // as it depends on the specific environment and could cause test flakiness
    // Just log the results for analysis
  }
  
  /**
   * Helper method to run a benchmark with the specified thread factory and count.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param threadCount The number of threads to create
   * @return The execution time in milliseconds
   */
  private long runBenchmark(ThreadFactory threadFactory, int threadCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i % mutableTestContributors.size();
        
        executor.submit(() -> {
          try {
            // Mark contributor as dirty to force configuration reload
            if (index < mutableTestContributors.size()) {
              mutableTestContributors.get(index).setDirty(true);
            }
            
            // Access the security configuration
            manager.listPrivileges();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      
      // Verify no errors occurred
      Assert.assertEquals(0, errorCount.get());
      
      return System.currentTimeMillis() - startTime;
    } finally {
      executor.shutdown();
    }
  }
}