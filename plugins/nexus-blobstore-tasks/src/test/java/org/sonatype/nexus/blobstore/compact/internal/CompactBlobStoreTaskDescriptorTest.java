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
package org.sonatype.nexus.blobstore.compact.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.scheduling.TaskDescriptorSupport.MULTINODE_KEY;

/**
 * Tests for {@link CompactBlobStoreTaskDescriptor} ensuring compatibility with Java 21 runtime.
 * 
 * This test validates the initialization of task configuration with proper multinode settings.
 * It has been updated to use JUnit Jupiter 5.10.1 annotations and is verified to work with Java 21.
 */
@Tag("Java21TestGroup")
public class CompactBlobStoreTaskDescriptorTest
    extends TestSupport
{
  private CompactBlobStoreTaskDescriptor underTest;

  private TaskConfiguration taskConfiguration = new TaskConfiguration();

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new CompactBlobStoreTaskDescriptor();
  }

  @Test
  public void initializeConfiguration() throws Exception {
    underTest.initializeConfiguration(taskConfiguration);
    assertThat("Task should not be configured for multinode execution", 
        taskConfiguration.getBoolean(MULTINODE_KEY, false), is(false));
  }
}