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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.formfields.FormField;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Test for {@link RestoreMetadataTaskDescriptor} that runs in a Virtual Thread context to verify
 * compatibility with Java 21's Virtual Threads.
 */
@Category(VirtualThreadTestGroup.class)
public class RestoreMetadataTaskDescriptorTest
    extends TestSupport
{
  RestoreMetadataTaskDescriptor underTest;

  @Mock
  ApplicationVersion applicationVersion;

  @Before
  public void setup() {
    when(applicationVersion.getEdition())
        .thenReturn("RPO");

    underTest = new RestoreMetadataTaskDescriptor(true, applicationVersion);
  }

  @Test
  public void testGetFormFields() throws ExecutionException, InterruptedException {
    // Create and start a virtual thread to run the test
    Future<List<FormField>> future = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      // Verify we're running in a virtual thread
      Thread currentThread = Thread.currentThread();
      log.info("Running test in thread: {}, isVirtual: {}", currentThread.getName(), currentThread.isVirtual());
      assertThat("Test should run in a virtual thread", currentThread.isVirtual(), is(true));
      
      // Get the form fields from the descriptor
      List<FormField> fields = underTest.getFormFields();
      
      // Return the fields for verification outside the virtual thread
      return fields;
    });
    
    // Get the result from the virtual thread
    List<FormField> formFields = future.get();
    
    // Verify the form fields - same as the original test
    assertThat(formFields, hasSize(6));
    
    // Additional verification for any virtual thread specific configuration
    // In a real implementation, we might check for fields that configure virtual thread behavior
    // For example, thread pool settings or concurrency limits that might be exposed in the UI
    // For now, we're just verifying the same behavior as the regular test
  }
}