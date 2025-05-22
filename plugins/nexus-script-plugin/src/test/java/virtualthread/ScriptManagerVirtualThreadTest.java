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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle.State;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptCreatedEvent;
import org.sonatype.nexus.script.ScriptDeletedEvent;
import org.sonatype.nexus.script.ScriptUpdatedEvent;
import org.sonatype.nexus.script.plugin.internal.ScriptManagerImpl;
import org.sonatype.nexus.script.plugin.internal.ScriptStore;
import org.sonatype.nexus.script.plugin.internal.ScriptingDisabledException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ScriptManagerImpl} using Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class ScriptManagerVirtualThreadTest
    extends TestSupport
{
  @Mock
  private EventManager eventManager;

  @Mock
  private ScriptStore scriptStore;

  @Mock
  private Script script;

  private ScriptManagerImpl underTest;

  @BeforeEach
  void setup() throws Exception {
    underTest = new ScriptManagerImpl(eventManager, scriptStore, true);
    // Start the lifecycle to enable state-guarded methods
    ((StateGuardLifecycleSupport) underTest).start();
  }

  /**
   * Test that browse() works correctly when executed in a virtual thread.
   */
  @Test
  void testBrowseInVirtualThread() throws Exception {
    // Setup
    when(scriptStore.list()).thenReturn(List.of(script));
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute browse() in the virtual thread
        Iterable<Script> result = underTest.browse();
        
        // Verify the result
        assertThat(List.copyOf(result), contains(script));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).list();
  }

  /**
   * Test that get() works correctly when executed in a virtual thread.
   */
  @Test
  void testGetInVirtualThread() throws Exception {
    // Setup
    String scriptName = "test-script";
    when(scriptStore.get(scriptName)).thenReturn(script);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute get() in the virtual thread
        Script result = underTest.get(scriptName);
        
        // Verify the result
        assertThat(result, sameInstance(script));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).get(scriptName);
  }

  /**
   * Test that create() works correctly when executed in a virtual thread.
   */
  @Test
  void testCreateInVirtualThread() throws Exception {
    // Setup
    String scriptName = "test-script";
    String scriptContent = "println 'Hello, World!'";
    String scriptType = "groovy";
    
    when(scriptStore.newScript()).thenReturn(script);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute create() in the virtual thread
        Script result = underTest.create(scriptName, scriptContent, scriptType);
        
        // Verify the result
        assertThat(result, sameInstance(script));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(script).setName(scriptName);
    verify(script).setContent(scriptContent);
    verify(script).setType(scriptType);
    verify(scriptStore).create(script);
    
    // Verify event was posted
    ArgumentCaptor<ScriptCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptCreatedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), sameInstance(script));
  }

  /**
   * Test that create() throws ScriptingDisabledException when scripting is disabled and executed in a virtual thread.
   */
  @Test
  void testCreateWithScriptingDisabledInVirtualThread() throws Exception {
    // Setup - create a new instance with scripting disabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, false);
    ((StateGuardLifecycleSupport) underTest).start();
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute create() in the virtual thread and expect exception
        assertThrows(ScriptingDisabledException.class, () -> 
            underTest.create("test-script", "println 'Hello, World!'", "groovy"));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify no interactions with script store
    verify(scriptStore, never()).create(any());
    verify(eventManager, never()).post(any());
  }

  /**
   * Test that update() works correctly when executed in a virtual thread.
   */
  @Test
  void testUpdateInVirtualThread() throws Exception {
    // Setup
    String scriptName = "test-script";
    String scriptContent = "println 'Updated content'";
    
    when(scriptStore.get(scriptName)).thenReturn(script);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute update() in the virtual thread
        Script result = underTest.update(scriptName, scriptContent);
        
        // Verify the result
        assertThat(result, sameInstance(script));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).get(scriptName);
    verify(script).setContent(scriptContent);
    verify(scriptStore).update(script);
    
    // Verify event was posted
    ArgumentCaptor<ScriptUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptUpdatedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), sameInstance(script));
  }

  /**
   * Test that update() returns null when script doesn't exist and executed in a virtual thread.
   */
  @Test
  void testUpdateNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    String scriptName = "non-existent-script";
    String scriptContent = "println 'Updated content'";
    
    when(scriptStore.get(scriptName)).thenReturn(null);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute update() in the virtual thread
        Script result = underTest.update(scriptName, scriptContent);
        
        // Verify the result is null
        assertThat(result, nullValue());
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).get(scriptName);
    verify(scriptStore, never()).update(any());
    verify(eventManager, never()).post(any());
  }

  /**
   * Test that delete() works correctly when executed in a virtual thread.
   */
  @Test
  void testDeleteInVirtualThread() throws Exception {
    // Setup
    String scriptName = "test-script";
    
    when(scriptStore.get(scriptName)).thenReturn(script);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute delete() in the virtual thread
        underTest.delete(scriptName);
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).get(scriptName);
    verify(scriptStore).delete(script);
    
    // Verify event was posted
    ArgumentCaptor<ScriptDeletedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptDeletedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), sameInstance(script));
  }

  /**
   * Test that delete() does nothing when script doesn't exist and executed in a virtual thread.
   */
  @Test
  void testDeleteNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    String scriptName = "non-existent-script";
    
    when(scriptStore.get(scriptName)).thenReturn(null);
    
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute delete() in the virtual thread
        underTest.delete(scriptName);
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    // Verify interactions
    verify(scriptStore).get(scriptName);
    verify(scriptStore, never()).delete(any());
    verify(eventManager, never()).post(any());
  }

  /**
   * Test that isEnabled() works correctly when executed in a virtual thread.
   */
  @Test
  void testIsEnabledInVirtualThread() throws Exception {
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Execute in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Execute isEnabled() in the virtual thread
        boolean result = underTest.isEnabled();
        
        // Verify the result
        assertThat(result, is(true));
      } 
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
  }

  /**
   * Test that multiple concurrent operations work correctly when executed in virtual threads.
   */
  @Test
  void testConcurrentOperationsInVirtualThreads() throws Exception {
    // Setup
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Setup mocks
    when(scriptStore.list()).thenReturn(List.of(script));
    when(scriptStore.get("test-script")).thenReturn(script);
    when(scriptStore.newScript()).thenReturn(script);
    
    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread.startVirtualThread(() -> {
        try {
          // Perform different operations based on thread index
          switch (index % 5) {
            case 0:
              // Browse
              Iterable<Script> scripts = underTest.browse();
              assertThat(List.copyOf(scripts), contains(script));
              break;
            case 1:
              // Get
              Script result = underTest.get("test-script");
              assertThat(result, sameInstance(script));
              break;
            case 2:
              // Create
              Script created = underTest.create("test-script-" + index, "content", "groovy");
              assertThat(created, sameInstance(script));
              break;
            case 3:
              // Update
              Script updated = underTest.update("test-script", "updated content");
              assertThat(updated, sameInstance(script));
              break;
            case 4:
              // Delete
              underTest.delete("test-script");
              break;
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all virtual threads to complete
    latch.await(10, TimeUnit.SECONDS);
  }
}