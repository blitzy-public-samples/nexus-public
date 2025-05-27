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
package org.sonatype.nexus.coreui.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.repository.upload.UploadResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UploadServiceTest
    extends TestSupport
{
  private static final String REPO_NAME = "repo";

  private UploadService component;

  @Mock
  private UploadManager uploadManager;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  @Mock
  private Repository repo;

  @Mock
  private HttpServletRequest request;

  @BeforeEach
  public void setup() throws IOException {
    when(repositoryManager.get(REPO_NAME)).thenReturn(repo);

    UploadResponse uploadResponse = new UploadResponse(Collections.singletonList("foo"));
    when(uploadManager.handle(repo, request)).thenReturn(uploadResponse);

    component = new UploadService(
        repositoryManager, uploadManager, repositoryCacheInvalidationService);
  }

  @Test
  public void uploadShouldThrowExceptionForUnknownRepository() {
    NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      component.upload("foo", request);
    });
    assertThat(exception.getMessage()).isEqualTo("Specified repository is missing");
  }

  @Test
  public void uploadShouldReturnExpectedResult() throws IOException {
    Format format = mock(Format.class);
    when(repo.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn(null);
    assertThat(component.upload(REPO_NAME, request)).isEqualTo("foo");
  }

  @Test
  public void uploadShouldInvalidateCacheForNpmFormat() throws IOException {
    Format format = mock(Format.class);
    when(repo.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn("npm");
    assertThat(component.upload(REPO_NAME, request)).isEqualTo("foo");
    verify(repositoryManager).findContainingGroups(REPO_NAME);
  }

  @Test
  public void createSearchTermShouldReturnCommonPath() {
    String result = component
        .createSearchTerm(Arrays.asList("foo-x.z/bar/bar", "foo-x.z/bar/foo", "foo-x.z/bar/foo/bar"));

    assertThat(result).isEqualTo("foo-x.z/bar");
  }
  
  @Test
  public void concurrentUploadsShouldWorkWithVirtualThreads() throws Exception {
    // Setup for virtual thread test
    Format format = mock(Format.class);
    when(repo.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn(null);
    
    // Create virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent upload tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            String result = component.upload(REPO_NAME, request);
            if ("foo".equals(result)) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Count failures by not incrementing successCount
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat(successCount.get()).isEqualTo(taskCount);
    } finally {
      executor.shutdown();
    }
  }
}