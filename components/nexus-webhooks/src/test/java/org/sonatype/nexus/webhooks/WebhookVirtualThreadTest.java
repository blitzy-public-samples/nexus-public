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
package org.sonatype.nexus.webhooks;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.event.EventManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests for {@link Webhook} with Java 21 Virtual Threads.
 * 
 * This test validates that the Webhook implementation correctly handles concurrent operations
 * when using Java 21's Virtual Threads, ensuring thread safety and proper event manager registration.
 */
@ExtendWith(MockitoExtension.class)
class WebhookVirtualThreadTest
{
  @Mock
  private EventManager eventManager;

  private static class TestWebhookType
      extends WebhookType
  {
    TestWebhookType() {
      super("test");
    }
  }

  private static class TestWebhook
      extends Webhook
  {
    @Override
    public WebhookType getType() {
      return new TestWebhookType();
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public Set<WebhookSubscription> getSubscriptions() {
      return super.getSubscriptions();
    }
  }

  private TestWebhook underTest;

  @BeforeEach
  void setUp() {
    underTest = new TestWebhook();
    underTest.setEventManager(eventManager);
  }

  @Test
  void prependsRmToEventId() {
    assertThat(underTest.getId().startsWith("rm:"), is(true));
  }

  /**
   * Tests concurrent subscription operations using Virtual Threads.
   * 
   * This test creates multiple Virtual Threads that concurrently subscribe to the webhook,
   * verifying that the EventManager is registered exactly once regardless of concurrency level.
   */
  @Test
  void concurrentSubscriptionsWithVirtualThreads() throws InterruptedException {
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create an executor service using Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to subscribe concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            underTest.subscribe(mock(WebhookConfiguration.class));
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
      
      // Verify that all subscriptions were added
      assertThat(underTest.getSubscriptions().size(), is(threadCount));
      
      // Verify that EventManager.register was called exactly once
      verify(eventManager, times(1)).register(underTest);
    }
  }

  /**
   * Tests concurrent cancellation operations using Virtual Threads.
   * 
   * This test creates multiple Virtual Threads that concurrently subscribe and then cancel
   * their subscriptions, verifying that the EventManager is unregistered exactly once when
   * all subscriptions are cancelled.
   */
  @Test
  void concurrentCancellationsWithVirtualThreads() throws InterruptedException {
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create subscriptions first
    WebhookSubscription[] subscriptions = new WebhookSubscription[threadCount];
    for (int i = 0; i < threadCount; i++) {
      subscriptions[i] = underTest.subscribe(mock(WebhookConfiguration.class));
    }
    
    // Verify that EventManager.register was called exactly once during setup
    verify(eventManager, times(1)).register(underTest);
    
    // Create an executor service using Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to cancel subscriptions concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            subscriptions[index].cancel();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
      
      // Verify that all subscriptions were removed
      assertThat(underTest.getSubscriptions(), empty());
      
      // Verify that EventManager.unregister was called exactly once
      verify(eventManager, times(1)).unregister(underTest);
    }
  }

  /**
   * Tests mixed concurrent subscription and cancellation operations using Virtual Threads.
   * 
   * This test creates multiple Virtual Threads that concurrently perform both subscription
   * and cancellation operations, verifying that the EventManager registration/unregistration
   * behaves correctly under high concurrency with Virtual Threads.
   */
  @Test
  void mixedConcurrentOperationsWithVirtualThreads() throws InterruptedException {
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount * 2); // For both subscribe and cancel operations
    
    AtomicInteger subscriptionCount = new AtomicInteger(0);
    WebhookSubscription[] subscriptions = new WebhookSubscription[threadCount];
    
    // Create an executor service using Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to subscribe concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            subscriptions[index] = underTest.subscribe(mock(WebhookConfiguration.class));
            subscriptionCount.incrementAndGet();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Submit tasks to cancel subscriptions concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            // Wait for the subscription to be created before cancelling
            while (subscriptions[index] == null) {
              Thread.yield();
            }
            subscriptions[index].cancel();
            subscriptionCount.decrementAndGet();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
      
      // Verify final subscription count
      assertThat(subscriptionCount.get(), is(0));
      assertThat(underTest.getSubscriptions(), empty());
      
      // Verify EventManager interactions
      // register should be called once when the first subscription is added
      verify(eventManager, times(1)).register(underTest);
      // unregister should be called once when the last subscription is removed
      verify(eventManager, times(1)).unregister(underTest);
      verifyNoMoreInteractions(eventManager);
    }
  }
}