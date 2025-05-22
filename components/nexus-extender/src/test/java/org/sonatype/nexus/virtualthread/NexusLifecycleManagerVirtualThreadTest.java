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
package org.sonatype.nexus.virtualthread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.inject.Named;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;
import org.sonatype.nexus.extender.NexusLifecycleManager;

import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.inject.BeanLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.*;

/**
 * Tests {@link NexusLifecycleManager} behavior when operating with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class NexusLifecycleManagerVirtualThreadTest
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

    assertThat("One or more phases is not mocked", phases.size(), is(Phase.values().length));

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
   * Tests that lifecycle phase transitions work correctly when triggered from a virtual thread.
   */
  @Test
  public void lifecycleTransitionsFromVirtualThread() throws Exception {
    // Create and start a virtual thread to execute lifecycle transitions
    Thread virtualThread = Thread.ofVirtual().name("lifecycle-virtual-thread").start(() -> {
      try {
        // Transition through phases
        underTest.to(KERNEL);
        underTest.to(STORAGE);
        underTest.to(TASKS); // Go all the way to TASKS
        underTest.to(OFF);   // Then back to OFF
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the correct sequence of phase transitions
    InOrder inOrder = verifyPhases();
    
    // Verify start sequence
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
    
    // Verify stop sequence
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
  }

  /**
   * Tests that error handling works properly when lifecycle operations are triggered from virtual threads.
   */
  @Test
  public void errorHandlingInVirtualThread() throws Exception {
    // Simulate an error in a phase
    doThrow(new Exception("Virtual thread test error")).when(schemasPhase).start();
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("error-virtual-thread").start(() -> {
      try {
        // Try to transition to TASKS, which should fail at SCHEMAS phase
        underTest.to(TASKS);
        fail("Expected exception was not thrown");
      }
      catch (Exception e) {
        // Expected exception
        assertThat(underTest.getCurrentPhase(), is(UPGRADE)); // Should stop at UPGRADE phase
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the phases that were started before the error
    InOrder inOrder = verifyPhases();
    inOrder.verify(kernelPhase).start();
    inOrder.verify(storagePhase).start();
    inOrder.verify(restorePhase).start();
    inOrder.verify(upgradePhase).start();
    inOrder.verify(schemasPhase).start(); // This should have thrown an exception
    
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests that task errors don't stop startup when running in virtual threads.
   */
  @Test
  public void taskErrorsDontStopStartupInVirtualThread() throws Exception {
    // Simulate an error in the tasks phase
    doThrow(new Exception("Virtual thread tasks error")).when(tasksPhase).start();
    
    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("tasks-error-virtual-thread").start(() -> {
      try {
        // Try to transition to TASKS, which should have an error but not fail
        underTest.to(TASKS);
      }
      catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify we reached the TASKS phase despite the error
    assertThat(underTest.getCurrentPhase(), is(TASKS));
    
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
    inOrder.verify(tasksPhase).start(); // This had an error but didn't stop startup
    
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests that errors don't stop shutdown when running in virtual threads.
   */
  @Test
  public void errorsDontStopShutdownInVirtualThread() throws Exception {
    // First transition to TASKS phase
    underTest.to(TASKS);
    assertThat(underTest.getCurrentPhase(), is(TASKS));
    
    // Simulate errors in all stop methods
    doThrow(new Exception("Virtual thread stop error")).when(tasksPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(capabilitiesPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(repositoriesPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(servicesPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(securityPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(eventsPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(schemasPhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(upgradePhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(restorePhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(storagePhase).stop();
    doThrow(new Exception("Virtual thread stop error")).when(kernelPhase).stop();
    
    // Create and start a virtual thread to shut down
    Thread virtualThread = Thread.ofVirtual().name("shutdown-virtual-thread").start(() -> {
      try {
        // Try to transition to OFF, which should have errors but not fail
        underTest.to(OFF);
      }
      catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify we reached the OFF phase despite the errors
    assertThat(underTest.getCurrentPhase(), is(OFF));
    
    // Verify all phases were stopped
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
  }

  /**
   * Tests concurrent lifecycle operations with multiple virtual threads.
   */
  @Test
  public void concurrentLifecycleOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Start with KERNEL phase
      underTest.to(KERNEL);
      assertThat(underTest.getCurrentPhase(), is(KERNEL));
      
      // Create a latch to synchronize threads
      CountDownLatch latch = new CountDownLatch(1);
      
      // Submit multiple concurrent tasks to bounce between phases
      Future<?> future1 = executor.submit(() -> {
        try {
          latch.await(); // Wait for signal to start
          underTest.bounce(STORAGE); // Bounce the STORAGE phase
        }
        catch (Exception e) {
          fail("Exception in virtual thread 1: " + e.getMessage());
        }
      });
      
      Future<?> future2 = executor.submit(() -> {
        try {
          latch.await(); // Wait for signal to start
          underTest.to(EVENTS); // Go to EVENTS phase
        }
        catch (Exception e) {
          fail("Exception in virtual thread 2: " + e.getMessage());
        }
      });
      
      // Signal threads to start
      latch.countDown();
      
      // Wait for all tasks to complete
      future1.get(5, TimeUnit.SECONDS);
      future2.get(5, TimeUnit.SECONDS);
      
      // Verify we ended up at the EVENTS phase (the last operation)
      assertThat(underTest.getCurrentPhase(), is(EVENTS));
    }
  }

  /**
   * Tests that sync() method works correctly when called from a virtual thread.
   */
  @Test
  public void syncFromVirtualThread() throws Exception {
    // Start with KERNEL phase
    underTest.to(KERNEL);
    assertThat(underTest.getCurrentPhase(), is(KERNEL));
    
    // Create and start a virtual thread to call sync()
    Thread virtualThread = Thread.ofVirtual().name("sync-virtual-thread").start(() -> {
      try {
        underTest.sync();
      }
      catch (Exception e) {
        fail("Exception in virtual thread: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify the phase hasn't changed
    assertThat(underTest.getCurrentPhase(), is(KERNEL));
  }

  @ManagedLifecycle(phase = OFF)
  private static class OffPhase
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

  @ManagedLifecycle(phase = KERNEL)
  private static class KernelPhase
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

  @ManagedLifecycle(phase = STORAGE)
  private static class StoragePhase
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

  @ManagedLifecycle(phase = RESTORE)
  private static class RestorePhase
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

  @ManagedLifecycle(phase = UPGRADE)
  private static class UpgradePhase
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

  @ManagedLifecycle(phase = SCHEMAS)
  private static class SchemasPhase
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

  @ManagedLifecycle(phase = EVENTS)
  private static class EventsPhase
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

  @ManagedLifecycle(phase = SECURITY)
  private static class SecurityPhase
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

  @ManagedLifecycle(phase = SERVICES)
  private static class ServicesPhase
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

  @ManagedLifecycle(phase = REPOSITORIES)
  private static class RepositoriesPhase
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

  @ManagedLifecycle(phase = CAPABILITIES)
  private static class CapabilitiesPhase
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

  @ManagedLifecycle(phase = TASKS)
  private static class TasksPhase
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
}