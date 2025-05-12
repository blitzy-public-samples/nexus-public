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
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A subscription for the {@link OnboardingManager#getOnboardingItemsPublisher()} method.
 * <p>
 * This class implements the Java 21 Flow API's Subscription interface to provide
 * reactive streaming of OnboardingItems.
 *
 * @since 3.31
 */
class OnboardingItemSubscription
    implements Subscription
{
  private final Subscriber<? super OnboardingItem> subscriber;
  private final List<OnboardingItem> items;
  private final AtomicLong requested = new AtomicLong(0);
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private int currentIndex = 0;

  /**
   * Constructor.
   *
   * @param subscriber the subscriber to receive onboarding items
   * @param items the list of onboarding items to publish
   */
  OnboardingItemSubscription(final Subscriber<? super OnboardingItem> subscriber, final List<OnboardingItem> items) {
    this.subscriber = subscriber;
    this.items = items;
  }

  @Override
  public void request(long n) {
    if (n <= 0) {
      subscriber.onError(new IllegalArgumentException("Requested amount must be positive: " + n));
      return;
    }

    if (cancelled.get()) {
      return;
    }

    long totalRequested = requested.addAndGet(n);
    
    // Use virtual thread for processing to avoid blocking the calling thread
    Thread.startVirtualThread(() -> {
      try {
        while (currentIndex < items.size() && requested.get() > 0 && !cancelled.get()) {
          subscriber.onNext(items.get(currentIndex++));
          requested.decrementAndGet();
        }
        
        if (currentIndex >= items.size() && !cancelled.get()) {
          subscriber.onComplete();
        }
      } catch (Exception e) {
        if (!cancelled.get()) {
          subscriber.onError(e);
        }
      }
    });
  }

  @Override
  public void cancel() {
    cancelled.set(true);
  }
}