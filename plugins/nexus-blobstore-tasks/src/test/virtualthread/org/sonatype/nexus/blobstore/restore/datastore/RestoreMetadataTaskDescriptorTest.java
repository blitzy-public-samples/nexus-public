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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.formfields.FormField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Test for {@link RestoreMetadataTaskDescriptor} that runs in a Virtual Thread context.
 * This test ensures that the task descriptor correctly provides the expected form fields
 * when running under Java 21's Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
class RestoreMetadataTaskDescriptorTest
    extends TestSupport
{
  private RestoreMetadataTaskDescriptor underTest;

  @Mock
  private ApplicationVersion applicationVersion;

  @BeforeEach
  void setup() {
    when(applicationVersion.getEdition())
        .thenReturn("RPO");

    underTest = new RestoreMetadataTaskDescriptor(true, applicationVersion);
  }

  /**
   * Test that verifies the form fields of the RestoreMetadataTaskDescriptor when running in a Virtual Thread.
   * This ensures that the descriptor functions correctly in the Java 21 Virtual Thread environment.
   */
  @Test
  void testGetFormFieldsInVirtualThread() throws Exception {
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create atomic references to hold the results and any exception
    AtomicReference<List<FormField>> formFieldsRef = new AtomicReference<>();
    AtomicReference<Boolean> isVirtualThreadRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();
    
    // Create and start a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("virtual-restore-metadata-test").start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThreadRef.set(Thread.currentThread().isVirtual());
        
        // Get the form fields from the descriptor
        List<FormField> formFields = underTest.getFormFields();
        formFieldsRef.set(formFields);
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    
    // Check if an exception occurred
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }
    
    // Verify the thread was actually a virtual thread
    assertTrue(isVirtualThreadRef.get(), "Test should run in a virtual thread");
    
    // Verify the form fields
    List<FormField> formFields = formFieldsRef.get();
    assertThat(formFields, hasSize(6));
    
    // Additional verification for any virtual thread specific configuration
    // This would be added if the descriptor has any virtual thread specific options
    // For now, we just verify the basic functionality works in a virtual thread context
  }
}