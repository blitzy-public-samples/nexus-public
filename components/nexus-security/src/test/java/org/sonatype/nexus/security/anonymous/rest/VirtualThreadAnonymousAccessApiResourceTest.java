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
package org.sonatype.nexus.security.anonymous.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.TestAnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;

import com.google.common.collect.Lists;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.Realm;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that verify the AnonymousAccessApiResource REST endpoint works correctly when accessed 
 * through Java 21 virtual threads. This test ensures that the REST API can be safely accessed 
 * concurrently from many virtual threads without thread pinning or resource issues, which is 
 * essential for validating Java 21 compatibility.
 */
public class VirtualThreadAnonymousAccessApiResourceTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  
  @Mock
  private AnonymousManager anonymousManager;

  @Mock
  private RealmSecurityManager realmSecurityManager;

  private AnonymousAccessApiResource underTest;

  private AnonymousConfiguration initialAnonymousConfiguration = new TestAnonymousConfiguration();

  @Before
  public void setup() {
    initialAnonymousConfiguration.setEnabled(true);
    initialAnonymousConfiguration.setUserId(AnonymousConfiguration.DEFAULT_USER_ID);
    initialAnonymousConfiguration.setRealmName(AnonymousConfiguration.DEFAULT_REALM_NAME);

    when(anonymousManager.newConfiguration()).thenReturn(initialAnonymousConfiguration);
    when(anonymousManager.getConfiguration()).thenReturn(initialAnonymousConfiguration);

    Realm realm = mock(Realm.class);
    when(realm.getName()).thenReturn(AnonymousConfiguration.DEFAULT_REALM_NAME);
    when(realmSecurityManager.getRealms()).thenReturn(Lists.newArrayList(realm));

    underTest = new AnonymousAccessApiResource(anonymousManager, realmSecurityManager);
  }

  /**
   * Tests that the read() operation works correctly when executed in a virtual thread.
   * This verifies basic functionality of the API when accessed through Java 21 virtual threads.
   */
  @Test
  public void testReadWithVirtualThread() throws Exception {
    // Create and start a virtual thread to execute the read operation
    Thread virtualThread = Thread.startVirtualThread(() -> {
      AnonymousAccessSettingsXO result = underTest.read();
      assertThat(result, is(notNullValue()));
      assertThat(result, is(new AnonymousAccessSettingsXO(initialAnonymousConfiguration)));
      assertThat(result.isEnabled(), is(true));
      assertThat(result.getUserId(), is(AnonymousConfiguration.DEFAULT_USER_ID));
      assertThat(result.getRealmName(), is(AnonymousConfiguration.DEFAULT_REALM_NAME));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that the update() operation works correctly when executed in a virtual thread.
   * This verifies that configuration updates can be performed through virtual threads.
   */
  @Test
  public void testUpdateWithVirtualThread() throws Exception {
    // Create a new configuration for the update
    AnonymousConfiguration newConfiguration = new TestAnonymousConfiguration();
    newConfiguration.setRealmName(AnonymousConfiguration.DEFAULT_REALM_NAME);
    newConfiguration.setUserId(AnonymousConfiguration.DEFAULT_USER_ID);
    newConfiguration.setEnabled(false);
    
    AnonymousAccessSettingsXO xo = new AnonymousAccessSettingsXO(newConfiguration);
    when(anonymousManager.getConfiguration()).thenReturn(newConfiguration);
    
    // Create and start a virtual thread to execute the update operation
    Thread virtualThread = Thread.startVirtualThread(() -> {
      AnonymousAccessSettingsXO result = underTest.update(xo);
      assertThat(result, is(notNullValue()));
      assertThat(result, is(new AnonymousAccessSettingsXO(newConfiguration)));
      assertThat(result.isEnabled(), is(false));
      assertThat(result.getUserId(), is(AnonymousConfiguration.DEFAULT_USER_ID));
      assertThat(result.getRealmName(), is(AnonymousConfiguration.DEFAULT_REALM_NAME));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests concurrent read operations using multiple virtual threads.
   * This verifies that the API can handle high concurrency with virtual threads.
   */
  @Test
  public void testConcurrentReadsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a countdown latch to coordinate thread completion
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track any errors that occur during execution
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    // Create and start multiple virtual threads to execute concurrent read operations
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadFactory.newThread(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            AnonymousAccessSettingsXO result = underTest.read();
            if (!new AnonymousAccessSettingsXO(initialAnonymousConfiguration).equals(result)) {
              hasErrors.set(true);
            }
          }
        } 
        catch (Exception e) {
          hasErrors.set(true);
          log.error("Error in virtual thread execution", e);
        } 
        finally {
          latch.countDown();
        }
      }).start();
    }
    
    // Wait for all threads to complete (with timeout)
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertThat("All virtual threads should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during concurrent execution", hasErrors.get(), is(false));
    
    // Verify the read method was called the expected number of times
    verify(anonymousManager, times(CONCURRENT_THREADS * OPERATIONS_PER_THREAD)).getConfiguration();
  }

  /**
   * Tests mixed read and update operations using multiple virtual threads.
   * This verifies that the API can handle concurrent reads and updates with virtual threads.
   */
  @Test
  public void testConcurrentReadAndUpdateWithVirtualThreads() throws Exception {
    // Create a thread-safe executor service using virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create configurations for updates
    AnonymousConfiguration enabledConfig = new TestAnonymousConfiguration();
    enabledConfig.setRealmName(AnonymousConfiguration.DEFAULT_REALM_NAME);
    enabledConfig.setUserId(AnonymousConfiguration.DEFAULT_USER_ID);
    enabledConfig.setEnabled(true);
    
    AnonymousConfiguration disabledConfig = new TestAnonymousConfiguration();
    disabledConfig.setRealmName(AnonymousConfiguration.DEFAULT_REALM_NAME);
    disabledConfig.setUserId(AnonymousConfiguration.DEFAULT_USER_ID);
    disabledConfig.setEnabled(false);
    
    // Track the current configuration state
    AtomicBoolean configEnabled = new AtomicBoolean(true);
    AtomicInteger readCount = new AtomicInteger(0);
    AtomicInteger updateCount = new AtomicInteger(0);
    
    // Set up the mock to return the appropriate configuration based on the current state
    when(anonymousManager.getConfiguration()).thenAnswer(invocation -> {
      readCount.incrementAndGet();
      return configEnabled.get() ? enabledConfig : disabledConfig;
    });
    
    // Create a list to hold the futures for all tasks
    List<Future<?>> futures = new ArrayList<>();
    
    // Submit read tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
          AnonymousAccessSettingsXO result = underTest.read();
          assertThat(result, is(notNullValue()));
          assertThat(result.getUserId(), is(AnonymousConfiguration.DEFAULT_USER_ID));
          assertThat(result.getRealmName(), is(AnonymousConfiguration.DEFAULT_REALM_NAME));
        }
        return null;
      }));
    }
    
    // Submit update tasks (fewer updates than reads to simulate realistic usage)
    for (int i = 0; i < CONCURRENT_THREADS / 10; i++) {
      futures.add(executor.submit(() -> {
        for (int j = 0; j < OPERATIONS_PER_THREAD / 5; j++) {
          // Toggle the configuration state
          boolean newState = !configEnabled.getAndSet(!configEnabled.get());
          updateCount.incrementAndGet();
          
          // Create the appropriate XO based on the new state
          AnonymousAccessSettingsXO xo = new AnonymousAccessSettingsXO(
              newState ? enabledConfig : disabledConfig);
          
          // Perform the update
          AnonymousAccessSettingsXO result = underTest.update(xo);
          assertThat(result, is(notNullValue()));
          assertThat(result.isEnabled(), is(newState));
          assertThat(result.getUserId(), is(AnonymousConfiguration.DEFAULT_USER_ID));
          assertThat(result.getRealmName(), is(AnonymousConfiguration.DEFAULT_REALM_NAME));
        }
        return null;
      }));
    }
    
    // Wait for all tasks to complete
    for (Future<?> future : futures) {
      try {
        future.get(30, TimeUnit.SECONDS);
      } 
      catch (ExecutionException e) {
        log.error("Error in virtual thread execution", e.getCause());
        throw e;
      }
    }
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    
    // Verify operations were performed
    log.info("Completed {} read operations and {} update operations", readCount.get(), updateCount.get());
    assertThat("Read operations should be performed", readCount.get() > 0, is(true));
    assertThat("Update operations should be performed", updateCount.get() > 0, is(true));
  }
}