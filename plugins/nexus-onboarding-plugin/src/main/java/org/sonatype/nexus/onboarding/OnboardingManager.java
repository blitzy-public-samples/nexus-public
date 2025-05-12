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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manage the onboarding process, will maintain a list of {@link OnboardingItem}s that need to be acted upon.
 * <p>
 * This interface has been updated for Java 21 compatibility, including support for Virtual Threads
 * to improve concurrency when processing onboarding items.
 *
 * @since 3.17
 */
public interface OnboardingManager
{
  /**
   * Check if there are any {@link OnboardingItem}s that need processing
   *
   * @return true if there are onboarding items that need to be processed
   */
  boolean needsOnboarding();

  /**
   * Retrieve list of {@link OnboardingItem}s that need to be processed
   *
   * @return a list of onboarding items that need to be processed
   */
  List<OnboardingItem> getOnboardingItems();
  
  /**
   * Process onboarding items concurrently using Virtual Threads.
   * <p>
   * This method leverages Java 21 Virtual Threads to efficiently process multiple onboarding items
   * concurrently, which is particularly useful for items that may involve I/O operations.
   *
   * @param processor the function to process each onboarding item
   * @param <R> the type of result produced by the processor
   * @return a list of CompletableFuture containing the results of processing each item
   * @since 3.31
   */
  default <R> List<CompletableFuture<R>> processItemsConcurrently(Function<OnboardingItem, R> processor) {
    return getOnboardingItems().stream()
        .map(item -> CompletableFuture.supplyAsync(() -> processor.apply(item)))
        .toList();
  }
  
  /**
   * Process onboarding items concurrently using Virtual Threads with a consumer.
   * <p>
   * This method leverages Java 21 Virtual Threads to efficiently process multiple onboarding items
   * concurrently, which is particularly useful for items that may involve I/O operations.
   *
   * @param consumer the consumer to process each onboarding item
   * @return a CompletableFuture that completes when all items have been processed
   * @since 3.31
   */
  default CompletableFuture<Void> processItemsConcurrently(Consumer<OnboardingItem> consumer) {
    List<CompletableFuture<Void>> futures = getOnboardingItems().stream()
        .map(item -> CompletableFuture.runAsync(() -> consumer.accept(item)))
        .toList();
    
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
  }
  
  /**
   * Get a reactive publisher of onboarding items that need to be processed.
   * <p>
   * This method provides a reactive way to process onboarding items, leveraging Java 21's
   * Flow API for reactive programming.
   *
   * @return a publisher of onboarding items
   * @since 3.31
   */
  default Publisher<OnboardingItem> getOnboardingItemsPublisher() {
    return subscriber -> {
      List<OnboardingItem> items = getOnboardingItems();
      subscriber.onSubscribe(new OnboardingItemSubscription(subscriber, items));
    };
  }
}
