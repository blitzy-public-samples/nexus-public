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

package org.sonatype.nexus.repository.rest.internal.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.rest.api.IncompatibleRepositoryException;
import org.sonatype.nexus.repository.rest.api.RepositoryNotFoundException;
import org.sonatype.nexus.repository.search.index.RebuildIndexTaskDescriptor;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;

import org.apache.shiro.authz.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.BreadActions.EDIT;

@ExtendWith(MockitoExtension.class)
@Tag("Java21")
@org.junit.Category(Java21TestGroup.class)
public class AuthorizingRepositoryManagerTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RepositoryPermissionChecker repositoryPermissionChecker;

  @Mock
  private TaskScheduler taskScheduler;

  @Mock
  private Repository repository;

  @Mock
  private EventManager eventManager;

  private AuthorizingRepositoryManagerImpl authorizingRepositoryManager;

  @BeforeEach
  public void setUp() {
    // Use lenient mode for Java 21 compatibility
    lenient().when(repository.getName()).thenReturn("repository");
    lenient().when(repositoryManager.get(anyString())).thenReturn(repository);
    lenient().when(repositoryManager.get(eq("absent"))).thenReturn(null);

    RepositoryCacheInvalidationService repositoryCacheInvalidationService =
        new RepositoryCacheInvalidationService(repositoryManager, eventManager);
    authorizingRepositoryManager = new AuthorizingRepositoryManagerImpl(
        repositoryManager, repositoryPermissionChecker, taskScheduler, repositoryCacheInvalidationService);
  }

  @Test
  void deleteShouldDeleteRepositoryIfExists() throws Exception {
    authorizingRepositoryManager.delete("repository");

    verify(repositoryManager).get(eq("repository"));
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq("delete"), eq(repository));
    verify(repositoryManager).delete(eq("repository"));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker);
  }

  @Test
  void deleteShouldDoNothingIfRepositoryIsAbsent() throws Exception {
    authorizingRepositoryManager.delete("absent");

    verify(repositoryManager).get(eq("absent"));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker);
  }

  @Test
  void deleteShouldThrowExceptionIfInsufficientPermissions() throws Exception {
    doThrow(new AuthorizationException("User is not permitted."))
        .when(repositoryPermissionChecker)
        .ensureUserCanAdmin(any(), any());
    
    assertThrows(AuthorizationException.class, () -> {
      authorizingRepositoryManager.delete("repository");
    });
  }

  @Test
  void rebuildIndexShouldThrowExceptionIfRepositoryDoesNotExist() throws Exception {
    assertThrows(RepositoryNotFoundException.class, () -> {
      authorizingRepositoryManager.rebuildSearchIndex("absent");
    });

    verify(repositoryManager).get(eq("absent"));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void rebuildIndexShouldThrowExceptionIfRepositoryTypeIsNotHostedOrProxy() throws Exception {
    when(repository.getType()).thenReturn(new GroupType());
    
    assertThrows(IncompatibleRepositoryException.class, () -> {
      authorizingRepositoryManager.rebuildSearchIndex("repository");
    });

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void rebuildIndexShouldThrowExceptionIfInsufficientPermissions() throws Exception {
    when(repository.getType()).thenReturn(new HostedType());
    doThrow(new AuthorizationException("User is not permitted."))
        .when(repositoryPermissionChecker)
        .ensureUserCanAdmin(any(), any());
    
    assertThrows(AuthorizationException.class, () -> {
      authorizingRepositoryManager.rebuildSearchIndex("repository");
    });

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq(EDIT), eq(repository));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void rebuildIndexShouldTriggerTask() throws Exception {
    TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
    when(taskScheduler.createTaskConfigurationInstance(any())).thenReturn(taskConfiguration);
    when(repository.getType()).thenReturn(new HostedType());

    authorizingRepositoryManager.rebuildSearchIndex("repository");

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq(EDIT), eq(repository));
    verify(taskScheduler).createTaskConfigurationInstance(RebuildIndexTaskDescriptor.TYPE_ID);
    verify(taskScheduler).submit(any());
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void invalidateCacheShouldThrowExceptionIfRepositoryDoesNotExist() throws Exception {
    assertThrows(RepositoryNotFoundException.class, () -> {
      authorizingRepositoryManager.invalidateCache("absent");
    });

    verify(repositoryManager).get(eq("absent"));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void invalidateCacheShouldThrowExceptionIfRepositoryTypeIsNotProxyOrGroup() throws Exception {
    when(repository.getType()).thenReturn(new HostedType());
    
    assertThrows(IncompatibleRepositoryException.class, () -> {
      authorizingRepositoryManager.invalidateCache("repository");
    });

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void invalidateCacheShouldThrowExceptionIfInsufficientPermissions() throws Exception {
    when(repository.getType()).thenReturn(new GroupType());
    doThrow(new AuthorizationException("User is not permitted."))
        .when(repositoryPermissionChecker)
        .ensureUserCanAdmin(any(), any());
    
    assertThrows(AuthorizationException.class, () -> {
      authorizingRepositoryManager.invalidateCache("repository");
    });

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq(EDIT), eq(repository));
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, taskScheduler);
  }

  @Test
  void invalidateCacheProxyRepository() throws Exception {
    when(repository.getType()).thenReturn(new ProxyType());
    ProxyFacet proxyFacet = mock(ProxyFacet.class);
    when(repository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    NegativeCacheFacet negativeCacheFacet = mock(NegativeCacheFacet.class);
    when(repository.facet(NegativeCacheFacet.class)).thenReturn(negativeCacheFacet);

    authorizingRepositoryManager.invalidateCache("repository");

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq(EDIT), eq(repository));
    verify(repository).facet(ProxyFacet.class);
    verify(proxyFacet).invalidateProxyCaches();
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, proxyFacet);
  }

  @Test
  void invalidateCacheGroupRepository() throws Exception {
    when(repository.getType()).thenReturn(new GroupType());
    GroupFacet groupFacet = mock(GroupFacet.class);
    when(repository.facet(GroupFacet.class)).thenReturn(groupFacet);

    authorizingRepositoryManager.invalidateCache("repository");

    verify(repositoryManager).get(eq("repository"));
    verify(repository).getType();
    verify(repositoryPermissionChecker).ensureUserCanAdmin(eq(EDIT), eq(repository));
    verify(repository).facet(GroupFacet.class);
    verify(groupFacet).invalidateGroupCaches();
    verifyNoMoreInteractions(repositoryManager, repositoryPermissionChecker, groupFacet);
  }

  @Test
  @Tag("VirtualThread")
  @org.junit.Category(VirtualThreadTestGroup.class)
  void concurrentRepositoryOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();
    
    // Set up mock for proxy repository
    when(repository.getType()).thenReturn(new ProxyType());
    ProxyFacet proxyFacet = mock(ProxyFacet.class);
    when(repository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            authorizingRepositoryManager.invalidateCache("repository");
          } catch (Exception e) {
            errorCount.incrementAndGet();
            exceptions.add(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All tasks should complete within timeout");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
      
      // Verify the proxy facet was called the expected number of times
      verify(repositoryManager, lenient().times(taskCount)).get(eq("repository"));
      verify(proxyFacet, lenient().times(taskCount)).invalidateProxyCaches();
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @Tag("VirtualThread")
  @org.junit.Category(VirtualThreadTestGroup.class)
  void concurrentRepositoryOperationsWithCompletableFuture() throws Exception {
    // Set up mock for proxy repository
    when(repository.getType()).thenReturn(new ProxyType());
    ProxyFacet proxyFacet = mock(ProxyFacet.class);
    when(repository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
    
    int taskCount = 50;
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    // Create tasks using CompletableFuture with virtual threads
    for (int i = 0; i < taskCount; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          authorizingRepositoryManager.invalidateCache("repository");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      futures.add(future);
    }
    
    // Wait for all futures to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    allFutures.join();
    
    // Verify the proxy facet was called the expected number of times
    verify(repositoryManager, lenient().times(taskCount)).get(eq("repository"));
    verify(proxyFacet, lenient().times(taskCount)).invalidateProxyCaches();
  }