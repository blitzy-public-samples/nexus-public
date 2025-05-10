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
package org.sonatype.nexus.repository.security;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SecurityHandlerTest
    extends TestSupport
{
  private SecurityHandler underTest;

  @Mock
  private Context context;

  @Mock
  private Repository repository;

  @Mock
  private SecurityFacet securityFacet;

  @Mock
  private Handler loginsCounterHandler;

  @Mock
  private Handler nextHandler;

  @Mock
  private Response response;

  private AttributesMap attributesMap;

  @BeforeEach
  public void setup() {
    underTest = new SecurityHandler(null);

    attributesMap = new AttributesMap();
    when(repository.facet(SecurityFacet.class)).thenReturn(securityFacet);
    when(context.getRepository()).thenReturn(repository);
    when(context.getAttributes()).thenReturn(attributesMap);
    when(context.proceed()).thenReturn(response);
  }

  @Test
  public void testHandle() throws Exception {
    underTest.handle(context);
    verify(securityFacet).ensurePermitted(any());
  }

  @Test
  public void testHandle_alreadyAuthorized() throws Exception {
    attributesMap.set(SecurityHandler.AUTHORIZED_KEY, true);
    underTest.handle(context);
    verify(securityFacet, never()).ensurePermitted(any());
  }

  @Test
  public void testHandle_loginsCounterHandlerIsNull() throws Exception {
    underTest.handle(context);
    verify(context, never()).insertHandler(loginsCounterHandler);
  }

  @Test
  public void testHandle_loginsCounterHandlerNonNull() throws Exception {
    underTest = new SecurityHandler(loginsCounterHandler);
    underTest.handle(context);
    verify(context, atMostOnce()).insertHandler(loginsCounterHandler);
  }

  @Test
  public void testHandlerChainWithPatternMatching() throws Exception {
    // Setup handler chain with pattern matching
    List<Handler> handlers = new ArrayList<>();
    handlers.add(underTest);
    handlers.add(nextHandler);

    // Use pattern matching to validate handler chain
    for (Handler handler : handlers) {
      if (handler instanceof SecurityHandler securityHandler) {
        // Verify it's our security handler
        assertThat(securityHandler, is(underTest));
        securityHandler.handle(context);
        verify(securityFacet).ensurePermitted(any());
      } else if (handler instanceof Handler nextInChain) {
        // Verify it's our next handler
        assertThat(nextInChain, is(nextHandler));
      }
    }
  }

  @Test
  public void testConcurrentExecutionWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Configure a new context for each thread to avoid concurrency issues
            Context threadContext = createThreadContext();
            underTest.handle(threadContext);
            successCount.incrementAndGet();
          } catch (Exception e) {
            logger.error("Error in virtual thread execution", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify all tasks completed successfully
      assertThat(successCount.get(), is(taskCount));
    } finally {
      executor.shutdown();
    }
  }
  
  private Context createThreadContext() {
    Context threadContext = org.mockito.Mockito.mock(Context.class);
    AttributesMap threadAttributes = new AttributesMap();
    Repository threadRepo = org.mockito.Mockito.mock(Repository.class);
    SecurityFacet threadSecurityFacet = org.mockito.Mockito.mock(SecurityFacet.class);
    
    when(threadRepo.facet(SecurityFacet.class)).thenReturn(threadSecurityFacet);
    when(threadContext.getRepository()).thenReturn(threadRepo);
    when(threadContext.getAttributes()).thenReturn(threadAttributes);
    when(threadContext.proceed()).thenReturn(response);
    
    return threadContext;
  }
}