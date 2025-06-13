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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptCreatedEvent;
import org.sonatype.nexus.script.ScriptDeletedEvent;
import org.sonatype.nexus.script.ScriptUpdatedEvent;
import org.sonatype.nexus.script.plugin.internal.ScriptManagerImpl;
import org.sonatype.nexus.script.plugin.internal.ScriptStore;
import org.sonatype.nexus.script.plugin.internal.ScriptingDisabledException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ScriptManagerImpl} using Java 21 Virtual Threads.
 * 
 * This test ensures that script management operations (browse, get, create, update, delete)
 * work correctly when executed in virtual threads, which is important for maintaining
 * performance during high-concurrency scenarios.
 */
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

  @Before
  public void setUp() throws Exception {
    // Initialize with scripting enabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, true);
    underTest.start();

    // Set up common script mock behavior
    when(script.getName()).thenReturn("test-script");
    when(script.getContent()).thenReturn("println 'hello'");
    when(script.getType()).thenReturn("groovy");
  }

  /**
   * Tests that the browse operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testBrowseInVirtualThread() throws Exception {
    // Set up mock behavior
    when(scriptStore.list()).thenReturn(List.of(script));

    // Execute in virtual thread and capture result
    AtomicReference<List<Script>> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        result.set(List.copyOf(StreamSupport.stream(underTest.browse().spliterator(), false).toList()));
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result
    assertThat(result.get(), contains(script));
  }

  /**
   * Tests that the get operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testGetInVirtualThread() throws Exception {
    // Set up mock behavior
    when(scriptStore.get("test-script")).thenReturn(script);

    // Execute in virtual thread and capture result
    AtomicReference<Script> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.get("test-script"));
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result
    assertThat(result.get(), is(script));
  }

  /**
   * Tests that the create operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testCreateInVirtualThread() throws Exception {
    // Set up mock behavior
    when(scriptStore.newScript()).thenReturn(script);

    // Execute in virtual thread and capture result
    AtomicReference<Script> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.create("test-script", "println 'hello'", "groovy"));
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result and interactions
    assertThat(result.get(), is(script));
    verify(script).setName("test-script");
    verify(script).setContent("println 'hello'");
    verify(script).setType("groovy");
    verify(scriptStore).create(script);

    // Verify event was posted
    ArgumentCaptor<ScriptCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptCreatedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), is(script));
  }

  /**
   * Tests that the update operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testUpdateInVirtualThread() throws Exception {
    // Set up mock behavior
    when(scriptStore.get("test-script")).thenReturn(script);

    // Execute in virtual thread and capture result
    AtomicReference<Script> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.update("test-script", "println 'updated'"));
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result and interactions
    assertThat(result.get(), is(script));
    verify(script).setContent("println 'updated'");
    verify(scriptStore).update(script);

    // Verify event was posted
    ArgumentCaptor<ScriptUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptUpdatedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), is(script));
  }

  /**
   * Tests that the update operation returns null when the script doesn't exist, when executed in a virtual thread.
   */
  @Test
  public void testUpdateNonExistentScriptInVirtualThread() throws Exception {
    // Set up mock behavior - script doesn't exist
    when(scriptStore.get("non-existent")).thenReturn(null);

    // Execute in virtual thread and capture result
    AtomicReference<Script> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.update("non-existent", "println 'updated'"));
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result and interactions
    assertThat(result.get(), is(nullValue()));
    verify(scriptStore, never()).update(any(Script.class));
    verify(eventManager, never()).post(any(ScriptUpdatedEvent.class));
  }

  /**
   * Tests that the delete operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testDeleteInVirtualThread() throws Exception {
    // Set up mock behavior
    when(scriptStore.get("test-script")).thenReturn(script);

    // Execute in virtual thread
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        underTest.delete("test-script");
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify interactions
    verify(scriptStore).delete(script);

    // Verify event was posted
    ArgumentCaptor<ScriptDeletedEvent> eventCaptor = ArgumentCaptor.forClass(ScriptDeletedEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), is(script));
  }

  /**
   * Tests that the delete operation does nothing when the script doesn't exist, when executed in a virtual thread.
   */
  @Test
  public void testDeleteNonExistentScriptInVirtualThread() throws Exception {
    // Set up mock behavior - script doesn't exist
    when(scriptStore.get("non-existent")).thenReturn(null);

    // Execute in virtual thread
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        underTest.delete("non-existent");
        latch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify interactions
    verify(scriptStore, never()).delete(any(Script.class));
    verify(eventManager, never()).post(any(ScriptDeletedEvent.class));
  }

  /**
   * Tests that script creation is prevented when scripting is disabled, when executed in a virtual thread.
   */
  @Test
  public void testCreateWithScriptingDisabledInVirtualThread() throws Exception {
    // Initialize with scripting disabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, false);
    underTest.start();

    // Execute in virtual thread and capture exception
    AtomicReference<Exception> caughtException = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        underTest.create("test-script", "println 'hello'", "groovy");
        fail("Expected ScriptingDisabledException was not thrown");
      }
      catch (Exception e) {
        caughtException.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify exception
    assertThat(caughtException.get() instanceof ScriptingDisabledException, is(true));
    verify(scriptStore, never()).create(any(Script.class));
    verify(eventManager, never()).post(any(ScriptCreatedEvent.class));
  }

  /**
   * Tests that script update is prevented when scripting is disabled, when executed in a virtual thread.
   */
  @Test
  public void testUpdateWithScriptingDisabledInVirtualThread() throws Exception {
    // Initialize with scripting disabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, false);
    underTest.start();

    // Execute in virtual thread and capture exception
    AtomicReference<Exception> caughtException = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread.startVirtualThread(() -> {
      try {
        underTest.update("test-script", "println 'updated'");
        fail("Expected ScriptingDisabledException was not thrown");
      }
      catch (Exception e) {
        caughtException.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify exception
    assertThat(caughtException.get() instanceof ScriptingDisabledException, is(true));
    verify(scriptStore, never()).update(any(Script.class));
    verify(eventManager, never()).post(any(ScriptUpdatedEvent.class));
  }

  /**
   * Tests that the isEnabled method works correctly when executed in a virtual thread.
   */
  @Test
  public void testIsEnabledInVirtualThread() throws Exception {
    // Test with scripting enabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, true);
    underTest.start();

    // Execute in virtual thread and capture result
    AtomicReference<Boolean> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    CountDownLatch finalLatch = latch;
    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.isEnabled());
        finalLatch.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result
    assertThat(result.get(), is(true));

    // Test with scripting disabled
    underTest = new ScriptManagerImpl(eventManager, scriptStore, false);
    underTest.start();

    // Reset latch and result
    latch = new CountDownLatch(1);
    result.set(null);

    CountDownLatch finalLatch1 = latch;
    Thread.startVirtualThread(() -> {
      try {
        result.set(underTest.isEnabled());
        finalLatch1.countDown();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });

    // Wait for virtual thread to complete
    assertThat("Virtual thread operation timed out", latch.await(5, TimeUnit.SECONDS), is(true));

    // Verify result
    assertThat(result.get(), is(false));
  }
}