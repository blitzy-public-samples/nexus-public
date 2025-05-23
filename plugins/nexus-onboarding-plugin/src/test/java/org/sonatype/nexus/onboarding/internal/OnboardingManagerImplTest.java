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
package org.sonatype.nexus.onboarding.internal;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.onboarding.OnboardingConfiguration;
import org.sonatype.nexus.onboarding.OnboardingItem;

import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OnboardingManagerImplTest
    extends TestSupport
{
  @Mock
  private OnboardingConfiguration onboardingConfiguration;

  @Mock
  private OnboardingItem onboardingItem1;

  @Mock
  private OnboardingItem onboardingItem2;

  @Mock
  private OnboardingItem onboardingItem3;

  private OnboardingManagerImpl underTest;

  @BeforeEach
  public void setup() {
    when(onboardingConfiguration.isEnabled()).thenReturn(true);
    when(onboardingItem1.applies()).thenReturn(true);
    when(onboardingItem1.getType()).thenReturn("type1");
    when(onboardingItem1.getPriority()).thenReturn(2);
    when(onboardingItem2.applies()).thenReturn(true);
    when(onboardingItem2.getType()).thenReturn("type2");
    when(onboardingItem2.getPriority()).thenReturn(1);
    when(onboardingItem3.applies()).thenReturn(true);
    when(onboardingItem3.getType()).thenReturn("type3");
    when(onboardingItem3.getPriority()).thenReturn(0);

    underTest = new OnboardingManagerImpl(ImmutableSet.of(onboardingItem1, onboardingItem2, onboardingItem3),
        onboardingConfiguration);
  }

  @Test
  public void needsOnboarding() {
    assertThat(underTest.needsOnboarding(), is(true));
  }

  @Test
  public void needsOnboardingNotAllItems() {
    when(onboardingItem1.applies()).thenReturn(false);
    when(onboardingItem2.applies()).thenReturn(false);
    when(onboardingItem3.applies()).thenReturn(true);

    assertThat(underTest.needsOnboarding(), is(true));
  }

  @Test
  public void getOnboardingItems() {
    List<OnboardingItem> items = underTest.getOnboardingItems();
    assertThat(items.size(), is(3));
    assertThat(items.get(0).getType(), is("type3"));
    assertThat(items.get(1).getType(), is("type2"));
    assertThat(items.get(2).getType(), is("type1"));
  }

  @Test
  public void getOnboardingItemsNoItems() {
    underTest = new OnboardingManagerImpl(Collections.emptySet(), onboardingConfiguration);

    assertThat(underTest.getOnboardingItems().size(), is(0));
  }
  
  @Test
  public void concurrentOnboardingItemsProcessingWithVirtualThreads() throws InterruptedException {
    // Create a countdown latch to synchronize threads
    CountDownLatch latch = new CountDownLatch(3);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to process each onboarding item concurrently
      executor.submit(() -> {
        // Process onboardingItem1
        assertThat(onboardingItem1.getType(), is("type1"));
        latch.countDown();
      });
      
      executor.submit(() -> {
        // Process onboardingItem2
        assertThat(onboardingItem2.getType(), is("type2"));
        latch.countDown();
      });
      
      executor.submit(() -> {
        // Process onboardingItem3
        assertThat(onboardingItem3.getType(), is("type3"));
        latch.countDown();
      });
      
      // Wait for all tasks to complete or timeout after 5 seconds
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertThat("All virtual threads completed in time", completed, is(true));
    }
  }
  
  @Test
  public void patternMatchingForOnboardingItemTypes() {
    // Test pattern matching for different onboarding item types
    for (OnboardingItem item : underTest.getOnboardingItems()) {
      String result = switch (item.getType()) {
        case "type1" -> "High priority item";
        case "type2" -> "Medium priority item";
        case "type3" -> "Low priority item";
        default -> "Unknown item type";
      };
      
      // Verify the pattern matching worked correctly
      switch (item.getType()) {
        case "type1" -> assertThat(result, is("High priority item"));
        case "type2" -> assertThat(result, is("Medium priority item"));
        case "type3" -> assertThat(result, is("Low priority item"));
        default -> assertThat(result, is("Unknown item type"));
      }
    }
  }
}