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
package org.sonatype.nexus.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.nexus.onboarding.internal.OnboardingManagerImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OnboardingManager} with Java 21 features.
 *
 * @since 3.31
 */
@ExtendWith(MockitoExtension.class)
class OnboardingManagerTest
{
  @Mock
  private OnboardingConfiguration configuration;

  @Mock
  private OnboardingItem item1;

  @Mock
  private OnboardingItem item2;

  @Mock
  private OnboardingItem item3;

  private OnboardingManager underTest;

  @BeforeEach
  void setUp() {
    // Setup common test data
    when(configuration.isEnabled()).thenReturn(true);
    
    lenient().when(item1.getType()).thenReturn("item1");
    lenient().when(item1.applies()).thenReturn(true);
    lenient().when(item1.getPriority()).thenReturn(1);
    
    lenient().when(item2.getType()).thenReturn("item2");
    lenient().when(item2.applies()).thenReturn(true);
    lenient().when(item2.getPriority()).thenReturn(2);
    
    lenient().when(item3.getType()).thenReturn("item3");
    lenient().when(item3.applies()).thenReturn(false); // This one doesn't apply
    lenient().when(item3.getPriority()).thenReturn(3);
    
    // Create the manager with our mocked items
    underTest = new OnboardingManagerImpl(Set.of(item1, item2, item3), configuration);
  }

  /**
   * Test that the basic functionality works as expected.
   */
  @Test
  void testBasicFunctionality() {
    assertTrue(underTest.needsOnboarding());
    
    List<OnboardingItem> items = underTest.getOnboardingItems();
    assertEquals(2, items.size());
    assertEquals("item1", items.get(0).getType()); // First by priority
    assertEquals("item2", items.get(1).getType()); // Second by priority
  }

  /**
   * Test that the concurrent processing with Function works as expected.
   */
  @Test
  void testProcessItemsConcurrentlyWithFunction() throws Exception {
    // Process items concurrently and collect their types
    List<CompletableFuture<String>> futures = underTest.processItemsConcurrently(OnboardingItem::getType);
    
    // Wait for all futures to complete and collect results
    List<String> results = new ArrayList<>();
    for (CompletableFuture<String> future : futures) {
      results.add(future.get(5, TimeUnit.SECONDS));
    }
    
    // Verify results (order may vary due to concurrent execution)
    assertEquals(2, results.size());
    assertTrue(results.contains("item1"));
    assertTrue(results.contains("item2"));
  }

  /**
   * Test that the concurrent processing with Consumer works as expected.
   */
  @Test
  void testProcessItemsConcurrentlyWithConsumer() throws Exception {
    // Use an AtomicInteger to count processed items
    AtomicInteger processedCount = new AtomicInteger(0);
    
    // Process items concurrently
    CompletableFuture<Void> future = underTest.processItemsConcurrently(item -> {
      processedCount.incrementAndGet();
    });
    
    // Wait for processing to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify that all applicable items were processed
    assertEquals(2, processedCount.get());
  }

  /**
   * Test that the reactive publisher works as expected.
   */
  @Test
  void testOnboardingItemsPublisher() throws Exception {
    // Create a test subscriber
    TestSubscriber subscriber = new TestSubscriber();
    
    // Subscribe to the publisher
    underTest.getOnboardingItemsPublisher().subscribe(subscriber);
    
    // Request all items
    subscriber.subscription.request(Long.MAX_VALUE);
    
    // Wait a bit for processing to complete
    Thread.sleep(500);
    
    // Verify that all applicable items were received
    assertEquals(2, subscriber.receivedItems.size());
    assertTrue(subscriber.receivedItems.contains(item1));
    assertTrue(subscriber.receivedItems.contains(item2));
    assertTrue(subscriber.completed);
    assertFalse(subscriber.error);
  }

  /**
   * A test subscriber for the reactive publisher.
   */
  private static class TestSubscriber implements Subscriber<OnboardingItem>
  {
    List<OnboardingItem> receivedItems = new ArrayList<>();
    Subscription subscription;
    boolean completed = false;
    boolean error = false;

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
    }

    @Override
    public void onNext(OnboardingItem item) {
      receivedItems.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      error = true;
    }

    @Override
    public void onComplete() {
      completed = true;
    }
  }
}