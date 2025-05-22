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
package org.sonatype.nexus.email.virtualthread;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.email.internal.EmailStringTemplates;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link EmailStringTemplates} with Java 21 Virtual Threads.
 * 
 * This test class specifically verifies that the EmailStringTemplates class
 * works correctly in the context of virtual threads, which are a key feature
 * of Java 21 used for asynchronous email operations.
 */
public class EmailStringTemplatesVirtualThreadTest
    extends TestSupport
{
  /**
   * Test that string templates work correctly when executed on a virtual thread.
   */
  @Test
  public void testStringTemplatesInVirtualThread() throws ExecutionException, InterruptedException {
    // Create a CompletableFuture that will be completed by a virtual thread
    CompletableFuture<String> future = CompletableFuture.supplyAsync(
        () -> EmailStringTemplates.verificationMessage("user@example.com", "smtp.example.com"),
        Thread.ofVirtual().factory()
    );
    
    // Get the result from the virtual thread
    String message = future.get();
    
    // Verify the message was correctly formatted
    assertThat(message, notNullValue());
    assertThat(message, containsString("user@example.com"));
    assertThat(message, containsString("smtp.example.com"));
  }
  
  /**
   * Test that virtual thread specific templates work correctly.
   */
  @Test
  public void testVirtualThreadSpecificTemplates() throws ExecutionException, InterruptedException {
    // Create a CompletableFuture that will be completed by a virtual thread
    CompletableFuture<String> future = CompletableFuture.supplyAsync(
        () -> EmailStringTemplates.virtualThreadOperationMessage("sendVerification", "Testing virtual threads"),
        Thread.ofVirtual().factory()
    );
    
    // Get the result from the virtual thread
    String message = future.get();
    
    // Verify the message was correctly formatted
    assertThat(message, notNullValue());
    assertThat(message, containsString("Async email operation"));
    assertThat(message, containsString("sendVerification"));
    assertThat(message, containsString("virtual thread"));
  }
  
  /**
   * Test that completion messages work correctly with virtual threads.
   */
  @Test
  public void testAsyncCompletionMessage() throws ExecutionException, InterruptedException {
    // Create a CompletableFuture that will be completed by a virtual thread
    CompletableFuture<String> future = CompletableFuture.supplyAsync(
        () -> {
          // Simulate some work
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return EmailStringTemplates.asyncCompletionMessage("sendVerification", 50, true);
        },
        Thread.ofVirtual().factory()
    );
    
    // Get the result from the virtual thread
    String message = future.get();
    
    // Verify the message was correctly formatted
    assertThat(message, notNullValue());
    assertThat(message, containsString("completed successfully"));
    assertThat(message, containsString("50ms"));
  }
}