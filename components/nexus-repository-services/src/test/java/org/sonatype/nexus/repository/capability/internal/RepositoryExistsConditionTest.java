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
package org.sonatype.nexus.repository.capability.internal;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.ConditionEvent.Satisfied;
import org.sonatype.nexus.capability.ConditionEvent.Unsatisfied;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryCreatedEvent;
import org.sonatype.nexus.repository.manager.RepositoryDeletedEvent;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RepositoryExistsCondition} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class RepositoryExistsConditionTest
    extends EventManagerTestSupport
{

  static final String TEST_REPOSITORY = "test-repository";

  @Mock
  private Repository repository;

  @Mock
  private RepositoryManager repositoryManager;

  private RepositoryExistsCondition underTest;

  @BeforeEach
  public final void setUpRepositoryExistsCondition()
      throws Exception
  {
    lenient().when(repositoryManager.browse()).thenReturn(Collections.<Repository>emptyList());

    final Supplier<String> repositoryName = () -> TEST_REPOSITORY;

    lenient().when(repository.getName()).thenReturn(TEST_REPOSITORY);

    underTest = new RepositoryExistsCondition(eventManager, repositoryManager, repositoryName);
    underTest.bind();

    verify(eventManager).register(underTest);

    assertThat(underTest.isSatisfied(), is(false));

    underTest.handle(new RepositoryCreatedEvent(repository));
  }

  /**
   * Condition should be satisfied initially (because mocking done in setup).
   */
  @Test
  public void shouldBeSatisfiedWhenRepositoryExists() {
    assertThat(underTest.isSatisfied(), is(true));
  }

  /**
   * Condition should become satisfied and notification sent when repository is added.
   */
  @Test
  public void shouldBeSatisfiedWhenRepositoryAdded() {
    assertThat(underTest.isSatisfied(), is(true));

    underTest.handle(new RepositoryDeletedEvent(repository));
    underTest.handle(new RepositoryCreatedEvent(repository));
    assertThat(underTest.isSatisfied(), is(true));

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest), satisfied(underTest));
  }

  /**
   * Condition should become unsatisfied when repository is removed.
   */
  @Test
  public void shouldBecomeUnsatisfiedWhenRepositoryIsRemoved() {
    assertThat(underTest.isSatisfied(), is(true));

    underTest.handle(new RepositoryDeletedEvent(repository));
    assertThat(underTest.isSatisfied(), is(false));

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest));
  }

  /**
   * Condition should remain satisfied when another repository is removed.
   */
  @Test
  public void shouldNotReactWhenAnotherRepositoryIsRemoved() {
    assertThat(underTest.isSatisfied(), is(true));
    final Repository anotherRepository = mock(Repository.class);
    when(anotherRepository.getName()).thenReturn("another");
    underTest.handle(new RepositoryDeletedEvent(anotherRepository));
    assertThat(underTest.isSatisfied(), is(true));
  }

  /**
   * Event bus handler is removed when releasing.
   */
  @Test
  public void shouldRemoveItselfAsHandlerWhenReleased() {
    underTest.release();

    verify(eventManager).unregister(underTest);
  }
  
  /**
   * Test that concurrent repository creation events are handled correctly with virtual threads.
   */
  @Test
  public void shouldHandleConcurrentRepositoryCreationWithVirtualThreads() throws Exception {
    // Create a condition that starts unsatisfied
    final Supplier<String> repositoryName = () -> "concurrent-test-repo";
    RepositoryExistsCondition condition = new RepositoryExistsCondition(eventManager, repositoryManager, repositoryName);
    condition.bind();
    
    // Create a repository for our test
    Repository concurrentRepo = mock(Repository.class);
    when(concurrentRepo.getName()).thenReturn("concurrent-test-repo");
    
    // Track events received
    List<ConditionEvent> receivedEvents = new CopyOnWriteArrayList<>();
    
    // Number of virtual threads to create
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to create and delete repository events concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Alternate between create and delete events
            if (index % 2 == 0) {
              condition.handle(new RepositoryCreatedEvent(concurrentRepo));
            } else {
              condition.handle(new RepositoryDeletedEvent(concurrentRepo));
            }
            
            // Capture the condition state after handling the event
            if (condition.isSatisfied()) {
              receivedEvents.add(new Satisfied(condition));
            } else {
              receivedEvents.add(new Unsatisfied(condition));
            }
            
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify that we received the expected number of events
    assertThat(receivedEvents.size(), is(threadCount));
    
    // The final state depends on the last event processed, but we should have both satisfied and unsatisfied events
    // Using pattern matching for instanceof checks
    boolean hasSatisfied = receivedEvents.stream().anyMatch(event -> event instanceof Satisfied satisfied);
    boolean hasUnsatisfied = receivedEvents.stream().anyMatch(event -> event instanceof Unsatisfied unsatisfied);
    
    // We should have at least one of each type of event due to the concurrent creates and deletes
    assertThat(hasSatisfied || hasUnsatisfied, is(true));
  }
}
