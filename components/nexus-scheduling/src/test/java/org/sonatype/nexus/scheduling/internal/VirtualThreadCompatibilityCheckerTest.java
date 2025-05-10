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
package org.sonatype.nexus.scheduling.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link VirtualThreadCompatibilityChecker}.
 */
public class VirtualThreadCompatibilityCheckerTest
    extends TestSupport
{
  private VirtualThreadCompatibilityChecker underTest;

  @Before
  public void setup() {
    underTest = new VirtualThreadCompatibilityChecker();
  }

  @Test
  public void testInitialIncompatibleTaskTypes() {
    assertThat(underTest.getIncompatibleTaskTypes(), hasSize(2));
    assertThat(underTest.getIncompatibleTaskTypes(), contains("script", "legacy-migration"));
  }

  @Test
  public void testInitialIncompatibleOperations() {
    assertThat(underTest.getIncompatibleTaskOperations(), hasSize(3));
    assertThat(underTest.getIncompatibleTaskOperations(), 
        contains("native-execution", "thread-local-storage", "synchronized-blocks"));
  }

  @Test
  public void testRegisterIncompatibleTaskType() {
    underTest.registerIncompatibleTaskType("custom-task-type");
    
    assertThat(underTest.getIncompatibleTaskTypes(), hasSize(3));
    assertThat(underTest.getIncompatibleTaskTypes(), hasItem("custom-task-type"));
    
    // Registering the same type again should not add a duplicate
    underTest.registerIncompatibleTaskType("custom-task-type");
    assertThat(underTest.getIncompatibleTaskTypes(), hasSize(3));
  }

  @Test
  public void testRegisterIncompatibleOperation() {
    underTest.registerIncompatibleOperation("custom-operation");
    
    assertThat(underTest.getIncompatibleTaskOperations(), hasSize(4));
    assertThat(underTest.getIncompatibleTaskOperations(), hasItem("custom-operation"));
    
    // Registering the same operation again should not add a duplicate
    underTest.registerIncompatibleOperation("custom-operation");
    assertThat(underTest.getIncompatibleTaskOperations(), hasSize(4));
  }

  @Test
  public void testIsCompatibleWithVirtualThreads_CompatibleTask() {
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-task");
    config.setTypeId("compatible-task-type");
    config.setName("Test Task");
    
    assertThat(underTest.isCompatibleWithVirtualThreads(config), is(true));
  }

  @Test
  public void testIsCompatibleWithVirtualThreads_IncompatibleTaskType() {
    TaskConfiguration config = new TaskConfiguration();
    config.setId("script-task");
    config.setTypeId("script"); // This is in the incompatible list
    config.setName("Script Task");
    
    assertThat(underTest.isCompatibleWithVirtualThreads(config), is(false));
  }

  @Test
  public void testIsCompatibleWithVirtualThreads_IncompatibleOperation() {
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-task");
    config.setTypeId("compatible-task-type");
    config.setName("Test Task");
    config.setString("native-execution", "true"); // This is an incompatible operation
    
    assertThat(underTest.isCompatibleWithVirtualThreads(config), is(false));
  }

  @Test
  public void testIsCompatibleWithVirtualThreads_ExplicitCompatibilityFlag() {
    // Explicitly marked as incompatible
    TaskConfiguration incompatibleConfig = new TaskConfiguration();
    incompatibleConfig.setId("test-task-1");
    incompatibleConfig.setTypeId("compatible-task-type");
    incompatibleConfig.setName("Test Task 1");
    incompatibleConfig.setString("virtual-thread-compatible", "false");
    
    assertThat(underTest.isCompatibleWithVirtualThreads(incompatibleConfig), is(false));
    
    // Explicitly marked as compatible
    TaskConfiguration compatibleConfig = new TaskConfiguration();
    compatibleConfig.setId("test-task-2");
    compatibleConfig.setTypeId("compatible-task-type");
    compatibleConfig.setName("Test Task 2");
    compatibleConfig.setString("virtual-thread-compatible", "true");
    
    assertThat(underTest.isCompatibleWithVirtualThreads(compatibleConfig), is(true));
  }
}