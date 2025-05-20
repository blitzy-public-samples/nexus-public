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
package org.sonatype.nexus.repository.routing.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.entity.DetachedEntityId;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Java21TestGroup
public class RoutingRuleHelperImplTest
    extends TestSupport
{
  private RoutingRuleHelperImpl underTest;

  private RoutingRuleCache cache;

  @Mock
  private RoutingRuleStore routingRuleStore;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private Repository repository;

  @Mock
  RepositoryPermissionChecker repositoryPermissionChecker;

  @BeforeEach
  public void setup() {
    RoutingRuleData block = new RoutingRuleData();
    block.name("block");
    block.description("some description");
    block.mode(RoutingMode.BLOCK);
    block.matchers(Arrays.asList("^/com/sonatype/.*", ".*foobar.*"));


    RoutingRuleData allow = new RoutingRuleData();
    block.name("allow");
    block.description("some description");
    block.mode(RoutingMode.ALLOW);
    block.matchers(Arrays.asList(".*foobar.*", "^/org/apache/.*"));

    when(routingRuleStore.getById("block")).thenReturn(block);
    when(routingRuleStore.getById("allow")).thenReturn(allow);
    when(repository.getName()).thenReturn("test-repo");
    cache = new RoutingRuleCache(routingRuleStore);
    underTest = new RoutingRuleHelperImpl(cache, repositoryManager, repositoryPermissionChecker);
  }

  @Test
  void disabledHandling() throws Exception {
    assertBlocked("block", "/com/sonatype/internal/secrets");
  }

  @Test
  void blockModeHandling() throws Exception {
    assertAllowed("block", "/org/apache/tomcat/catalina");

    assertBlocked("block", "/com/sonatype/internal/secrets");
    assertBlocked("block", "/com/foobar/");
  }

  @Test
  void allowModeHandling() throws Exception {
    assertAllowed("allow", "/org/apache/tomcat/catalina");
    assertAllowed("allow", "/com/foobar/");

    assertBlocked("allow", "/com/sonatype/internal/secrets");
  }

  @Test
  void noRuleAssignedHandling() throws Exception {
    configureRepositoryMock(null);

    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));

    assertThat(underTest.isAllowed(repository, "/com/sonatype/internal/secrets"), is(true));
    assertThat(underTest.calculateAssignedRepositories().size(), is(0));
  }

  @Test
  void nullRepositoryConfigurationHandling() throws Exception {
    Repository repository = mock(Repository.class);
    Configuration configuration = mock(Configuration.class);
    when(repository.getConfiguration()).thenReturn(configuration);
    when(configuration.getRoutingRuleId()).thenReturn(null);
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));

    assertTrue(underTest.isAllowed(repository, "/some/path"));
    assertEquals(0, underTest.calculateAssignedRepositories().size());
  }

  @Test
  void assignedRepositoriesWithSingleRepositoryAssigned() throws Exception {
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository));
    configureRepositoryMock("singleRule");

    Map<EntityId, List<Repository>> assignedRepositoryMap = underTest.calculateAssignedRepositories();
    assertEquals(1, assignedRepositoryMap.size());
    List<Repository> assignedRepositories = assignedRepositoryMap.get(new DetachedEntityId("singleRule"));
    assertEquals(ImmutableList.of(repository), assignedRepositories);
  }

  @Test
  void assignedRepositoriesWithMultipleRulesAndRepositories() throws Exception {
    Repository repository2 = mock(Repository.class);
    Repository repository3 = mock(Repository.class);

    when(repository2.getName()).thenReturn("test-repo-2");
    when(repository3.getName()).thenReturn("test-repo-3");
    when(repositoryManager.browse()).thenReturn(ImmutableList.of(repository, repository2, repository3));

    configureRepositoryMock(repository, "rule-1");
    configureRepositoryMock(repository2, "rule-2");
    configureRepositoryMock(repository3, "rule-2");

    Map<EntityId, List<Repository>> assignedRepositoryMap = underTest.calculateAssignedRepositories();
    assertEquals(2, assignedRepositoryMap.size());
    assertEquals(ImmutableList.of(repository), assignedRepositoryMap.get(new DetachedEntityId("rule-1")));
    assertEquals(ImmutableList.of(repository2, repository3), assignedRepositoryMap.get(new DetachedEntityId("rule-2")));
  }

  @Test
  void concurrentRuleEvaluationWithVirtualThreads() throws Exception {
    // Configure repository with a rule
    configureRepositoryMock("block");

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger blockedCount = new AtomicInteger(0);
    AtomicInteger allowedCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String path = (i % 2 == 0) ? "/com/sonatype/internal/secrets" : "/org/apache/tomcat/catalina";
        
        executor.submit(() -> {
          try {
            boolean allowed = underTest.isAllowed(repository, path);
            if (allowed) {
              allowedCount.incrementAndGet();
            } else {
              blockedCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results - half should be blocked, half allowed
      assertEquals(taskCount / 2, blockedCount.get(), "Half of the paths should be blocked");
      assertEquals(taskCount / 2, allowedCount.get(), "Half of the paths should be allowed");
    } finally {
      executor.shutdown();
    }
  }

  private void assertBlocked(final String ruleId, final String path) throws Exception {
    configureRepositoryMock(ruleId);
    assertFalse(underTest.isAllowed(repository, path));
  }

  private void assertAllowed(final String ruleId, final String path) throws Exception {
    configureRepositoryMock(ruleId);
    assertTrue(underTest.isAllowed(repository, path));
  }

  private void configureRepositoryMock(final String repositoryRuleId) {
    configureRepositoryMock(repository, repositoryRuleId);
  }

  private void configureRepositoryMock(final Repository repo, final String repositoryRuleId) {
    Configuration configuration = mock(Configuration.class);
    when(repo.getConfiguration()).thenReturn(configuration);

    if (repositoryRuleId != null) {
      when(configuration.getRoutingRuleId()).thenReturn(new DetachedEntityId(repositoryRuleId));
    }
  }
}