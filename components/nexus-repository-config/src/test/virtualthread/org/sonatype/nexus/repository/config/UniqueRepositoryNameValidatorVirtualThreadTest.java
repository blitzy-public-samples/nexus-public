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
package org.sonatype.nexus.repository.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Repository names validated by {@link UniqueRepositoryNameValidator}
 * when executed with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
public class UniqueRepositoryNameValidatorVirtualThreadTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @InjectMocks
  private UniqueRepositoryNameValidator validator;

  /**
   * Name is valid when the RepositoryManager says it does not exist,
   * executed in a Virtual Thread context.
   */
  @Test
  public void testIsValidInVirtualThread() throws Exception {
    when(repositoryManager.exists("foo")).thenReturn(true, false);

    // Run validation in a Virtual Thread
    Future<Boolean> validationResult1 = Thread.ofVirtual()
        .name("validation-thread-1")
        .start(() -> validator.isValid("foo", null))
        .join();

    assertFalse(validationResult1.get(), "Repository name should be invalid when it already exists");

    // Run second validation in another Virtual Thread
    Future<Boolean> validationResult2 = Thread.ofVirtual()
        .name("validation-thread-2")
        .start(() -> validator.isValid("foo", null))
        .join();

    assertTrue(validationResult2.get(), "Repository name should be valid when it doesn't exist");
  }

  /**
   * Tests concurrent validation with multiple Virtual Threads to verify thread safety.
   */
  @Test
  public void testConcurrentValidationWithVirtualThreads() throws Exception {
    // Setup repository manager to return different results for different repository names
    when(repositoryManager.exists("existing")).thenReturn(true);
    when(repositoryManager.exists("nonexisting")).thenReturn(false);

    // Create a latch to synchronize thread execution
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Start multiple Virtual Threads for concurrent validation
    Future<Boolean> existingResult = Thread.ofVirtual()
        .name("existing-repo-thread")
        .start(() -> {
          try {
            startLatch.await(); // Wait for signal to start
            return validator.isValid("existing", null);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted", e);
          }
        });

    Future<Boolean> nonExistingResult = Thread.ofVirtual()
        .name("nonexisting-repo-thread")
        .start(() -> {
          try {
            startLatch.await(); // Wait for signal to start
            return validator.isValid("nonexisting", null);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted", e);
          }
        });

    // Signal threads to start validation concurrently
    startLatch.countDown();

    // Verify results
    assertFalse(existingResult.get(1, TimeUnit.SECONDS), 
        "Validation should fail for existing repository even with concurrent execution");
    assertTrue(nonExistingResult.get(1, TimeUnit.SECONDS), 
        "Validation should pass for non-existing repository even with concurrent execution");
  }

  /**
   * Tests error handling in Virtual Thread context.
   */
  @Test
  public void testErrorHandlingInVirtualThread() {
    // Setup repository manager to throw an exception
    when(repositoryManager.exists("error")).thenThrow(new RuntimeException("Simulated error"));

    // Run validation in a Virtual Thread and verify exception propagation
    Future<?> future = Thread.ofVirtual()
        .name("error-thread")
        .start(() -> validator.isValid("error", null));

    assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS),
        "Exceptions should be properly propagated from Virtual Threads");
  }
}