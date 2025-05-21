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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("VirtualThreadTestGroup")
@Category(VirtualThreadTestGroup.class)
public class SecurityContributorThreadedTest
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
    assertEquals(200, testContributors.size());

    this.expectedPrivilegeCount = this.manager.listPrivileges().size();

    // 100 static items with 3 privs each + 100 dynamic items + 2 from default config
    assertEquals((100 * 3) + 100 + 2, expectedPrivilegeCount);
  }
  
  /**
   * Test concurrent access to security contributors using virtual threads.
   * This replaces the previous MultithreadedTestCase implementation with Java 21 Virtual Threads.
   */
  @Test
  void testThreading() throws Exception {
    // Create a latch to coordinate thread completion
    CountDownLatch latch = new CountDownLatch(5);
    
    // Start 5 virtual threads to perform concurrent operations
    Thread thread1 = Thread.ofVirtual().name("thread-1").start(() -> {
      try {
        mutableTestContributors.get(1).setDirty(true);
        assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      } finally {
        latch.countDown();
      }
    });
    
    Thread thread2 = Thread.ofVirtual().name("thread-2").start(() -> {
      try {
        assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      } finally {
        latch.countDown();
      }
    });
    
    Thread thread3 = Thread.ofVirtual().name("thread-3").start(() -> {
      try {
        mutableTestContributors.get(3).setDirty(true);
        assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      } finally {
        latch.countDown();
      }
    });
    
    Thread thread4 = Thread.ofVirtual().name("thread-4").start(() -> {
      try {
        assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      } finally {
        latch.countDown();
      }
    });
    
    Thread thread5 = Thread.ofVirtual().name("thread-5").start(() -> {
      try {
        mutableTestContributors.get(5).setDirty(true);
        assertEquals(expectedPrivilegeCount, manager.listPrivileges().size());
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not complete in time");
    
    // Verify all contributors were accessed
    for (MutableTestSecurityContributor contributor : mutableTestContributors) {
      assertTrue(
          contributor.wasConfigRequested(),
          "Get config should be called on each contributor after any changed: " + contributor.getId());
    }
  }
  
  /**
   * Test with a higher number of concurrent threads to validate scalability with virtual threads.
   * This test creates 100 virtual threads that concurrently access the security manager.
   */
  @Test
  void testHighConcurrencyWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i % mutableTestContributors.size();
        executor.submit(() -> {
          try {
            // Every third thread will modify a contributor
            if (index % 3 == 0) {
              mutableTestContributors.get(index).setDirty(true);
            }
            
            // All threads verify the privilege count remains consistent
            int actualCount = manager.listPrivileges().size();
            if (actualCount != expectedPrivilegeCount) {
              failed.set(true);
              System.err.println("Expected " + expectedPrivilegeCount + " privileges but got " + actualCount);
            }
          } catch (Exception e) {
            failed.set(true);
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "High concurrency test did not complete in time");
    }
    
    // Verify the test passed
    assertTrue(!failed.get(), "High concurrency test failed with inconsistent privilege counts");
    
    // Verify all contributors were accessed
    for (MutableTestSecurityContributor contributor : mutableTestContributors) {
      assertTrue(
          contributor.wasConfigRequested(),
          "Get config should be called on each contributor after high concurrency test: " + contributor.getId());
    }
  }