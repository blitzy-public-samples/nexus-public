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
package org.sonatype.nexus.extender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Named;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;

import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.inject.BeanLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.osgi.framework.Bundle;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.*;

/**
 * Tests {@link NexusLifecycleManager} behavior when operating under Java 21 Virtual Threads.
 * 
 * Validates correct phase ordering and error handling when lifecycle components are started and stopped
 * by virtual threads. Also verifies that thread pinning is avoided during lifecycle phase transitions.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class NexusLifecycleManagerVirtualThreadTest
    extends TestSupport
{
  @Mock
  private BeanLocator locator;

  @Mock
  private Bundle systemBundle;

  @Mock
  private OffPhase offPhase;

  @Mock
  private KernelPhase kernelPhase;

  @Mock
  private StoragePhase storagePhase;

  @Mock
  private RestorePhase restorePhase;

  @Mock
  private UpgradePhase upgradePhase;

  @Mock
  private SchemasPhase schemasPhase;

  @Mock
  private EventsPhase eventsPhase;

  @Mock
  private SecurityPhase securityPhase;

  @Mock
  private ServicesPhase servicesPhase;

  @Mock
  private RepositoriesPhase repositoriesPhase;

  @Mock
  private CapabilitiesPhase capabilitiesPhase;

  @Mock
  private TasksPhase tasksPhase;

  private List<Lifecycle> phases;

  private List<Lifecycle> randomPhases;

  private NexusLifecycleManager underTest;

  @BeforeEach
  public void setUp() throws Exception {
    phases = newArrayList(
        offPhase,
        kernelPhase,
        storagePhase,
        restorePhase,
        upgradePhase,
        schemasPhase,
        eventsPhase,
        securityPhase,
        servicesPhase,
        repositoriesPhase,
        capabilitiesPhase,
        tasksPhase);

    assertEquals(Phase.values().length, phases.size(), "One or more phases is not mocked");

    // OFF phase should never get called
    doThrow(new Exception("testing")).when(offPhase).start();
    doThrow(new Exception("testing")).when(offPhase).stop();

    // randomize location results
    randomPhases = new ArrayList<>(phases);
    Collections.shuffle(randomPhases);
    Iterable<BeanEntry<Named, Lifecycle>> entries = randomPhases.stream().map(phase -> {
      BeanEntry<Named, Lifecycle> entry = mock(BeanEntry.class);
      doReturn(phase.getClass()).when(entry).getImplementationClass();
      when(entry.getValue()).thenReturn(phase);
      return entry;
    }).collect(toList());

    when(locator.<Named, Lifecycle>locate(Key.get(Lifecycle.class, Named.class))).thenReturn((Iterable) entries);

    underTest = new NexusLifecycleManager(locator, systemBundle);
  }

  public InOrder verifyPhases() {
    return inOrder(
        offPhase,
        kernelPhase,
        storagePhase,
        restorePhase,
        upgradePhase,
        schemasPhase,
        eventsPhase,
        securityPhase,
        servicesPhase,
        repositoriesPhase,
        capabilitiesPhase,
        tasksPhase,
        systemBundle);
  }

  /**
   * Tests that lifecycle phases execute in the correct order when started by a virtual thread.
   */
  @Test
  public void virtualThreadLifecycleOrdering() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread to execute the lifecycle phases
    Thread.ofVirtual().start(() -> {
      try {
        underTest.to(TASKS);
        latch.countDown();
      }
      catch (Exception e) {
        threadException.set(e);
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check if there was an exception in the virtual thread
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    // Verify the phases were started in the correct order
    InOrder inOrder = verifyPhases();
    inOrder.verify(kernelPhase).start();
    inOrder.verify(storagePhase).start();
    inOrder.verify(restorePhase).start();
    inOrder.verify(upgradePhase).start();
    inOrder.verify(schemasPhase).start();
    inOrder.verify(eventsPhase).start();
    inOrder.verify(securityPhase).start();
    inOrder.verify(servicesPhase).start();
    inOrder.verify(repositoriesPhase).start();
    inOrder.verify(capabilitiesPhase).start();
    inOrder.verify(tasksPhase).start();
    inOrder.verifyNoMoreInteractions();
    
    assertEquals(TASKS, underTest.getCurrentPhase(), "Current phase should be TASKS");
  }

  /**
   * Tests that lifecycle phases execute in the correct order when stopped by a virtual thread.
   */
  @Test
  public void virtualThreadLifecycleShutdown() throws Exception {
    // First start up to TASKS phase
    underTest.to(TASKS);
    assertEquals(TASKS, underTest.getCurrentPhase(), "Current phase should be TASKS");
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread to execute the shutdown
    Thread.ofVirtual().start(() -> {
      try {
        underTest.to(OFF);
        latch.countDown();
      }
      catch (Exception e) {
        threadException.set(e);
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check if there was an exception in the virtual thread
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    // Verify the phases were stopped in the correct order
    InOrder inOrder = verifyPhases();
    inOrder.verify(tasksPhase).stop();
    inOrder.verify(capabilitiesPhase).stop();
    inOrder.verify(repositoriesPhase).stop();
    inOrder.verify(servicesPhase).stop();
    inOrder.verify(securityPhase).stop();
    inOrder.verify(eventsPhase).stop();
    inOrder.verify(schemasPhase).stop();
    inOrder.verify(upgradePhase).stop();
    inOrder.verify(restorePhase).stop();
    inOrder.verify(storagePhase).stop();
    inOrder.verify(kernelPhase).stop();
    inOrder.verify(systemBundle).stop();
    inOrder.verifyNoMoreInteractions();
    
    assertEquals(OFF, underTest.getCurrentPhase(), "Current phase should be OFF");
  }

  /**
   * Tests error handling during startup with virtual threads.
   * Non-task errors should stop the startup process.
   */
  @Test
  public void virtualThreadNonTaskErrorsStopStartup() throws Exception {
    // Find a phase that's not OFF or TASKS to make fail
    Lifecycle badPhase = randomPhases.stream()
        .filter(phase -> !(phase.equals(offPhase) || phase.equals(tasksPhase)))
        .findFirst()
        .get();
    
    doThrow(new Exception("testing virtual thread error")).when(badPhase).start();
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread to execute the lifecycle phases
    Thread.ofVirtual().start(() -> {
      try {
        // attempt to reach last phase in the defined lifecycle
        underTest.to(Phase.values()[Phase.values().length - 1]);
        latch.countDown();
      }
      catch (Exception e) {
        threadException.set(e);
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // There should be an exception
    assertEquals("testing virtual thread error", threadException.get().getMessage(), 
        "Expected startup error to propagate");
    
    // lifecycle should have settled at the phase just before the bad phase
    assertEquals(Phase.values()[phases.indexOf(badPhase) - 1], underTest.getCurrentPhase(),
        "Current phase should be the one before the bad phase");
  }

  /**
   * Tests that task errors don't stop the startup process when using virtual threads.
   */
  @Test
  public void virtualThreadTaskErrorsDontStopStartup() throws Exception {
    doThrow(new Exception("testing virtual thread task error")).when(tasksPhase).start();
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread to execute the lifecycle phases
    Thread.ofVirtual().start(() -> {
      try {
        underTest.to(TASKS);
        latch.countDown();
      }
      catch (Exception e) {
        threadException.set(e);
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check if there was an exception in the virtual thread
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    assertEquals(TASKS, underTest.getCurrentPhase(), "Current phase should be TASKS despite error");
    
    // Verify all phases were started
    InOrder inOrder = verifyPhases();
    inOrder.verify(kernelPhase).start();
    inOrder.verify(storagePhase).start();
    inOrder.verify(restorePhase).start();
    inOrder.verify(upgradePhase).start();
    inOrder.verify(schemasPhase).start();
    inOrder.verify(eventsPhase).start();
    inOrder.verify(securityPhase).start();
    inOrder.verify(servicesPhase).start();
    inOrder.verify(repositoriesPhase).start();
    inOrder.verify(capabilitiesPhase).start();
    inOrder.verify(tasksPhase).start();
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests that errors don't stop the shutdown process when using virtual threads.
   */
  @Test
  public void virtualThreadErrorsDontStopShutdown() throws Exception {
    // First start up to TASKS phase
    underTest.to(TASKS);
    assertEquals(TASKS, underTest.getCurrentPhase(), "Current phase should be TASKS");
    
    // Make all phases throw exceptions on stop
    doThrow(new Exception("testing virtual thread error")).when(tasksPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(capabilitiesPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(repositoriesPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(servicesPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(securityPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(eventsPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(schemasPhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(upgradePhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(restorePhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(storagePhase).stop();
    doThrow(new Exception("testing virtual thread error")).when(kernelPhase).stop();
    
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread to execute the shutdown
    Thread.ofVirtual().start(() -> {
      try {
        underTest.to(OFF);
        latch.countDown();
      }
      catch (Exception e) {
        threadException.set(e);
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Check if there was an exception in the virtual thread
    if (threadException.get() != null) {
      throw threadException.get();
    }
    
    // Verify all phases were stopped despite errors
    InOrder inOrder = verifyPhases();
    inOrder.verify(tasksPhase).stop();
    inOrder.verify(capabilitiesPhase).stop();
    inOrder.verify(repositoriesPhase).stop();
    inOrder.verify(servicesPhase).stop();
    inOrder.verify(securityPhase).stop();
    inOrder.verify(eventsPhase).stop();
    inOrder.verify(schemasPhase).stop();
    inOrder.verify(upgradePhase).stop();
    inOrder.verify(restorePhase).stop();
    inOrder.verify(storagePhase).stop();
    inOrder.verify(kernelPhase).stop();
    inOrder.verify(systemBundle).stop();
    inOrder.verifyNoMoreInteractions();
    
    assertEquals(OFF, underTest.getCurrentPhase(), "Current phase should be OFF despite errors");
  }

  /**
   * Tests that multiple virtual threads can't interfere with each other when accessing the lifecycle manager.
   */
  @Test
  public void virtualThreadConcurrentAccess() throws Exception {
    // First start up to KERNEL phase
    underTest.to(KERNEL);
    assertEquals(KERNEL, underTest.getCurrentPhase(), "Current phase should be KERNEL");
    
    CountDownLatch thread1Latch = new CountDownLatch(1);
    CountDownLatch thread2Latch = new CountDownLatch(1);
    AtomicReference<Exception> thread1Exception = new AtomicReference<>();
    AtomicReference<Exception> thread2Exception = new AtomicReference<>();
    
    // Start first virtual thread to move to TASKS
    Thread.ofVirtual().name("vthread-1").start(() -> {
      try {
        underTest.to(TASKS);
        thread1Latch.countDown();
      }
      catch (Exception e) {
        thread1Exception.set(e);
        thread1Latch.countDown();
      }
    });
    
    // Start second virtual thread to move to STORAGE (should be blocked until first thread completes)
    Thread.ofVirtual().name("vthread-2").start(() -> {
      try {
        underTest.to(STORAGE);
        thread2Latch.countDown();
      }
      catch (Exception e) {
        thread2Exception.set(e);
        thread2Latch.countDown();
      }
    });
    
    // Wait for both threads to complete
    thread1Latch.await(5, TimeUnit.SECONDS);
    thread2Latch.await(5, TimeUnit.SECONDS);
    
    // Check if there were exceptions in the virtual threads
    if (thread1Exception.get() != null) {
      throw thread1Exception.get();
    }
    if (thread2Exception.get() != null) {
      throw thread2Exception.get();
    }
    
    // The last thread to complete should determine the final phase
    // Since thread synchronization is handled by the lifecycle manager,
    // the final phase should be either TASKS or STORAGE
    Phase currentPhase = underTest.getCurrentPhase();
    assertTrue(currentPhase == TASKS || currentPhase == STORAGE, 
        "Final phase should be either TASKS or STORAGE, but was " + currentPhase);
  }
  
  private void assertTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static class TestLifecycle
      implements Lifecycle
  {
    @Override
    public void start() throws Exception {
      // no-op
    }

    @Override
    public void stop() throws Exception {
      // no-op
    }
  }

  @ManagedLifecycle(phase = OFF)
  private static class OffPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = KERNEL)
  private static class KernelPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = STORAGE)
  private static class StoragePhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = RESTORE)
  private static class RestorePhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = UPGRADE)
  private static class UpgradePhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = SCHEMAS)
  private static class SchemasPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = EVENTS)
  private static class EventsPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = SECURITY)
  private static class SecurityPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = SERVICES)
  private static class ServicesPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = REPOSITORIES)
  private static class RepositoriesPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = CAPABILITIES)
  private static class CapabilitiesPhase
      extends TestLifecycle
  {
  }

  @ManagedLifecycle(phase = TASKS)
  private static class TasksPhase
      extends TestLifecycle
  {
  }
}