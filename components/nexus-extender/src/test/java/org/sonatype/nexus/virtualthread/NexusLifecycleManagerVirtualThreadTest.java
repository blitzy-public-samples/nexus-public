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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Named;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.goodies.testsupport.TestSupport;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.*;

/**
 * Tests {@link NexusLifecycleManager} behavior with Java 21 Virtual Threads.
 * 
 * This test class validates that the NexusLifecycleManager correctly handles lifecycle
 * phase transitions when triggered from virtual threads, ensuring proper ordering,
 * error handling, and concurrency behavior. Virtual threads are lightweight threads
 * introduced in Java 21 that enable high-throughput concurrent applications without
 * the overhead of traditional platform threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
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
  void setUp() throws Exception {
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
   * 
   * This test verifies that the NexusLifecycleManager properly handles phase transitions
   * when the transitions are triggered from a Java 21 virtual thread. It ensures that
   * all phases are started and stopped in the correct order, and that the current phase
   * is correctly updated after each transition.
   */
  @Test
  void lifecycleOrderingFromVirtualThread() throws Exception {
    InOrder inOrder = verifyPhases();

    // Use a CompletableFuture to capture any exceptions from the virtual thread
    CompletableFuture<Void> future = new CompletableFuture<>();
    
    // Start a virtual thread to execute the lifecycle transitions
    Thread.ofVirtual().name("lifecycle-virtual-thread").start(() -> {
      try {
        // Transition through all phases in order
        underTest.to(KERNEL);
        assertThat(underTest.getCurrentPhase(), is(KERNEL));
        
        underTest.to(STORAGE);
        assertThat(underTest.getCurrentPhase(), is(STORAGE));
        
        underTest.to(RESTORE);
        assertThat(underTest.getCurrentPhase(), is(RESTORE));
        
        underTest.to(UPGRADE);
        assertThat(underTest.getCurrentPhase(), is(UPGRADE));
        
        underTest.to(SCHEMAS);
        assertThat(underTest.getCurrentPhase(), is(SCHEMAS));
        
        underTest.to(EVENTS);
        assertThat(underTest.getCurrentPhase(), is(EVENTS));
        
        underTest.to(SECURITY);
        assertThat(underTest.getCurrentPhase(), is(SECURITY));
        
        underTest.to(SERVICES);
        assertThat(underTest.getCurrentPhase(), is(SERVICES));
        
        underTest.to(REPOSITORIES);
        assertThat(underTest.getCurrentPhase(), is(REPOSITORIES));
        
        underTest.to(CAPABILITIES);
        assertThat(underTest.getCurrentPhase(), is(CAPABILITIES));
        
        underTest.to(TASKS);
        assertThat(underTest.getCurrentPhase(), is(TASKS));
        
        // Now go back down through the phases
        underTest.to(CAPABILITIES);
        assertThat(underTest.getCurrentPhase(), is(CAPABILITIES));
        
        underTest.to(REPOSITORIES);
        assertThat(underTest.getCurrentPhase(), is(REPOSITORIES));
        
        underTest.to(SERVICES);
        assertThat(underTest.getCurrentPhase(), is(SERVICES));
        
        underTest.to(SECURITY);
        assertThat(underTest.getCurrentPhase(), is(SECURITY));
        
        underTest.to(EVENTS);
        assertThat(underTest.getCurrentPhase(), is(EVENTS));
        
        underTest.to(SCHEMAS);
        assertThat(underTest.getCurrentPhase(), is(SCHEMAS));
        
        underTest.to(UPGRADE);
        assertThat(underTest.getCurrentPhase(), is(UPGRADE));
        
        underTest.to(RESTORE);
        assertThat(underTest.getCurrentPhase(), is(RESTORE));
        
        underTest.to(STORAGE);
        assertThat(underTest.getCurrentPhase(), is(STORAGE));
        
        underTest.to(KERNEL);
        assertThat(underTest.getCurrentPhase(), is(KERNEL));
        
        underTest.to(OFF);
        assertThat(underTest.getCurrentPhase(), is(OFF));
        
        future.complete(null);
      }
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get(5, TimeUnit.SECONDS);
    
    // Verify the correct order of phase transitions
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
   * Tests that non-task errors properly stop startup when triggered from a virtual thread.
   * 
   * This test verifies that when a non-task phase throws an exception during startup
   * from a virtual thread, the lifecycle manager correctly stops the startup process
   * and settles at the phase just before the failing phase. This ensures that error
   * handling works properly even when lifecycle operations are triggered from virtual threads.
   */
  @Test
  void nonTaskErrorsStopStartupFromVirtualThread() throws Exception {
    InOrder inOrder = verifyPhases();

    // Find a phase that's not OFF or TASKS to make fail
    Lifecycle badPhase = randomPhases.stream()
        .filter(phase -> !(phase.equals(offPhase) || phase.equals(tasksPhase)))
        .findFirst()
        .get();

    doThrow(new Exception("testing")).when(badPhase).start();

    // Use a reference to capture the exception from the virtual thread
    AtomicReference<Exception> caughtException = new AtomicReference<>();
    AtomicReference<Phase> finalPhase = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a virtual thread to execute the lifecycle transition
    Thread.ofVirtual().name("error-virtual-thread").start(() -> {
      try {
        // Attempt to reach the last phase in the defined lifecycle
        underTest.to(Phase.values()[Phase.values().length - 1]);
        fail("Expected startup error to propagate");
      }
      catch (Exception e) {
        caughtException.set(e);
        finalPhase.set(underTest.getCurrentPhase());
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat("Virtual thread should complete in time", completed, is(true));
    assertThat("Exception should be caught", caughtException.get() != null, is(true));
    
    // Lifecycle should have settled at the phase just before the bad phase
    assertThat(finalPhase.get(), is(Phase.values()[phases.indexOf(badPhase) - 1]));

    // Verify phases after OFF up to including bad phase attempted to start
    for (Lifecycle phase : phases.subList(1, phases.indexOf(badPhase) + 1)) {
      inOrder.verify(phase).start();
    }
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests that task errors don't stop startup when triggered from a virtual thread.
   * 
   * This test verifies that when the TASKS phase throws an exception during startup
   * from a virtual thread, the lifecycle manager correctly continues the startup process
   * and reaches the TASKS phase despite the error. This is important because task errors
   * should not prevent the system from starting up, even when using virtual threads.
   */
  @Test
  void taskErrorsDontStopStartupFromVirtualThread() throws Exception {
    InOrder inOrder = verifyPhases();

    doThrow(new Exception("testing")).when(tasksPhase).start();

    // Use a CompletableFuture to capture any exceptions from the virtual thread
    CompletableFuture<Void> future = new CompletableFuture<>();
    
    // Start a virtual thread to execute the lifecycle transition
    Thread.ofVirtual().name("task-error-virtual-thread").start(() -> {
      try {
        underTest.to(TASKS);
        future.complete(null);
      }
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get(5, TimeUnit.SECONDS);

    assertThat(underTest.getCurrentPhase(), is(TASKS));

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
   * Tests that errors don't stop shutdown when triggered from a virtual thread.
   * 
   * This test verifies that when phases throw exceptions during shutdown from a virtual thread,
   * the lifecycle manager correctly continues the shutdown process through all phases
   * until reaching the OFF phase. This ensures that the system can always be properly
   * shut down, even when errors occur and operations are triggered from virtual threads.
   */
  @Test
  void errorsDontStopShutdownFromVirtualThread() throws Exception {
    // First, bring the system up to TASKS phase
    CompletableFuture<Void> startupFuture = new CompletableFuture<>();
    Thread.ofVirtual().name("startup-virtual-thread").start(() -> {
      try {
        underTest.to(TASKS);
        startupFuture.complete(null);
      }
      catch (Exception e) {
        startupFuture.completeExceptionally(e);
      }
    });
    
    // Wait for startup to complete
    startupFuture.get(5, TimeUnit.SECONDS);
    assertThat(underTest.getCurrentPhase(), is(TASKS));

    // Configure all phases to throw exceptions during stop
    doThrow(new Exception("testing")).when(tasksPhase).stop();
    doThrow(new Exception("testing")).when(capabilitiesPhase).stop();
    doThrow(new Exception("testing")).when(repositoriesPhase).stop();
    doThrow(new Exception("testing")).when(servicesPhase).stop();
    doThrow(new Exception("testing")).when(securityPhase).stop();
    doThrow(new Exception("testing")).when(eventsPhase).stop();
    doThrow(new Exception("testing")).when(schemasPhase).stop();
    doThrow(new Exception("testing")).when(upgradePhase).stop();
    doThrow(new Exception("testing")).when(restorePhase).stop();
    doThrow(new Exception("testing")).when(storagePhase).stop();
    doThrow(new Exception("testing")).when(kernelPhase).stop();

    InOrder inOrder = verifyPhases();

    // Now shut down from a virtual thread
    CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
    Thread.ofVirtual().name("shutdown-virtual-thread").start(() -> {
      try {
        underTest.to(OFF);
        shutdownFuture.complete(null);
      }
      catch (Exception e) {
        shutdownFuture.completeExceptionally(e);
      }
    });
    
    // Wait for shutdown to complete
    shutdownFuture.get(5, TimeUnit.SECONDS);

    assertThat(underTest.getCurrentPhase(), is(OFF));

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
   * Tests concurrent lifecycle operations from multiple virtual threads.
   * 
   * This test verifies that the NexusLifecycleManager can handle concurrent lifecycle
   * operations from multiple virtual threads without deadlocks or synchronization issues.
   * It launches multiple virtual threads that attempt to transition to different phases
   * simultaneously, ensuring that the lifecycle manager properly synchronizes these
   * operations and maintains a consistent state.
   */
  @Test
  void concurrentLifecycleOperationsFromVirtualThreads() throws Exception {
    // Use a virtual thread executor for concurrent operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Start with KERNEL phase
      underTest.to(KERNEL);
      assertThat(underTest.getCurrentPhase(), is(KERNEL));
      
      // Create a barrier to synchronize all threads
      int threadCount = 5;
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      AtomicBoolean failed = new AtomicBoolean(false);
      
      // Launch multiple virtual threads that try to transition to different phases
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            barrier.await(5, TimeUnit.SECONDS);
            
            // Each thread tries to transition to a different phase
            Phase targetPhase;
            switch (index % 5) {
              case 0: targetPhase = STORAGE; break;
              case 1: targetPhase = RESTORE; break;
              case 2: targetPhase = UPGRADE; break;
              case 3: targetPhase = SCHEMAS; break;
              default: targetPhase = EVENTS; break;
            }
            
            // Perform the transition
            underTest.to(targetPhase);
          }
          catch (Exception e) {
            failed.set(true);
            log.error("Thread {} failed", index, e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No virtual threads should fail", failed.get(), is(false));
      
      // The final phase should be one of the target phases
      Phase finalPhase = underTest.getCurrentPhase();
      assertThat("Final phase should be one of the target phases",
          finalPhase == STORAGE || finalPhase == RESTORE || finalPhase == UPGRADE || 
          finalPhase == SCHEMAS || finalPhase == EVENTS, is(true));
    }
  }

  /**
   * Tests that the lifecycle manager can handle a high number of concurrent virtual threads
   * without deadlocks or synchronization issues.
   * 
   * This test verifies that the NexusLifecycleManager can handle a high volume of concurrent
   * virtual threads (100) performing lifecycle operations without deadlocks or synchronization
   * issues. It uses Java 21's Executors.newVirtualThreadPerTaskExecutor() to create a large
   * number of virtual threads that perform bounce operations on different phases simultaneously.
   * This test is particularly important for validating the system's behavior under high concurrency
   * scenarios that are now possible with virtual threads.
   */
  @Test
  void highConcurrencyVirtualThreads() throws Exception {
    // Start with KERNEL phase
    underTest.to(KERNEL);
    assertThat(underTest.getCurrentPhase(), is(KERNEL));
    
    // Use a virtual thread executor for high concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int threadCount = 100; // High number of virtual threads
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      AtomicBoolean failed = new AtomicBoolean(false);
      
      // Launch many virtual threads that perform lifecycle operations
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Each thread performs a bounce operation on a phase
            Phase bouncePhase = Phase.values()[1 + (index % (Phase.values().length - 1))];
            underTest.bounce(bouncePhase);
          }
          catch (Exception e) {
            failed.set(true);
            log.error("Thread {} failed", index, e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete with a generous timeout
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete in time", completed, is(true));
      assertThat("No virtual threads should fail", failed.get(), is(false));
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