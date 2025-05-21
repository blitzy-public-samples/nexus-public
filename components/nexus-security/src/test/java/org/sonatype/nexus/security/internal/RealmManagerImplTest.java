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
package org.sonatype.nexus.security.internal;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.realm.RealmConfiguration;
import org.sonatype.nexus.security.realm.RealmConfigurationChangedEvent;
import org.sonatype.nexus.security.realm.RealmConfigurationEvent;
import org.sonatype.nexus.security.realm.RealmConfigurationStore;
import org.sonatype.nexus.security.realm.TestRealmConfiguration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.Realm;
import org.eclipse.sisu.inject.BeanLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealmManagerImplTest
    extends TestSupport
{
  @Mock
  private EventManager eventManager;

  @Mock
  private RealmConfigurationStore configStore;

  @Mock
  private RealmSecurityManager securityManager;

  @Mock
  private Realm realmA;

  @Mock
  private Realm realmB;

  @Mock
  private RealmConfigurationEvent configEvent;

  @Mock
  private BeanLocator beanLocator;

  private RealmManagerImpl manager;

  @BeforeEach
  void setUp() {
    Map<String, Realm> realms = ImmutableMap.of("A", realmA, "B", realmB);
    RealmConfiguration defaultConfig = new TestRealmConfiguration();
    defaultConfig.setRealmNames(ImmutableList.of("A"));
    manager = new RealmManagerImpl(beanLocator, eventManager, configStore, () -> defaultConfig, securityManager, realms,
        false);
  }

  @Test
  void testOnStoreChanged_LocalEvent() {
    when(configEvent.isLocal()).thenReturn(true);
    manager.on(configEvent);
    verifyNoInteractions(eventManager, configStore);
  }

  @Test
  void testOnStoreChanged_RemoteEvent() {
    RealmConfiguration eventConfig = new TestRealmConfiguration();
    eventConfig.setRealmNames(ImmutableList.of("B"));
    when(configEvent.isLocal()).thenReturn(false);
    when(configEvent.getConfiguration()).thenReturn(eventConfig);

    manager.on(configEvent);

    ArgumentCaptor<RealmConfigurationChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(RealmConfigurationChangedEvent.class);

    verify(eventManager).post(eventCaptor.capture());

    RealmConfiguration storeConfig = eventCaptor.getValue().getConfiguration();
    assertThat(storeConfig.getRealmNames(), is(eventConfig.getRealmNames()));
  }
  
  @Test
  void testConcurrentRealmConfigurationEvents() throws Exception {
    // Create multiple event configurations
    List<RealmConfiguration> eventConfigs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      RealmConfiguration config = new TestRealmConfiguration();
      config.setRealmNames(ImmutableList.of("B"));
      eventConfigs.add(config);
    }
    
    // Setup for concurrent execution
    int taskCount = eventConfigs.size();
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger processedEvents = new AtomicInteger(0);
    
    // Use virtual threads for concurrent execution
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (RealmConfiguration config : eventConfigs) {
        executor.submit(() -> {
          try {
            // Create a new event for each thread
            RealmConfigurationEvent event = new RealmConfigurationEvent(config);
            
            // Configure the mock to return non-local for this event
            when(event.isLocal()).thenReturn(false);
            when(event.getConfiguration()).thenReturn(config);
            
            // Process the event
            manager.on(event);
            
            // Count processed events
            processedEvents.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(5, TimeUnit.SECONDS);
      
      // Verify all events were processed
      assertEquals(taskCount, processedEvents.get(), "All events should be processed");
      
      // Verify event manager was called for each event
      ArgumentCaptor<RealmConfigurationChangedEvent> eventCaptor =
          ArgumentCaptor.forClass(RealmConfigurationChangedEvent.class);
      verify(eventManager).post(eventCaptor.capture());
      
      // Verify the configuration in the captured event
      RealmConfiguration storeConfig = eventCaptor.getValue().getConfiguration();
      assertThat(storeConfig.getRealmNames(), is(ImmutableList.of("B")));
    } finally {
      executor.shutdown();
    }
  }
}