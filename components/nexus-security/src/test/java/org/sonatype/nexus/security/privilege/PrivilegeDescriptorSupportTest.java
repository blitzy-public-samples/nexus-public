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
package org.sonatype.nexus.security.privilege;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sonatype.nexus.security.privilege.PrivilegeDescriptorSupport.ALL;
import static org.sonatype.nexus.security.privilege.PrivilegeDescriptorSupport.humanizeActions;
import static org.sonatype.nexus.security.privilege.PrivilegeDescriptorSupport.humanizeName;

/**
 * Tests for {@link PrivilegeDescriptorSupport}.
 */
public class PrivilegeDescriptorSupportTest
    extends TestSupport
{
  @Test
  void shouldHumanizeNameWithAllValues() {
    assertEquals("all", humanizeName(ALL, ALL));
  }
  
  @Test
  void shouldHumanizeNameWithAllAndSpecificFormat() {
    assertEquals("all 'bar'-format", humanizeName(ALL, "bar"));
  }
  
  @Test
  void shouldHumanizeNameWithSpecificValues() {
    assertEquals("foo", humanizeName("foo", "bar"));
  }

  @Test
  void shouldHumanizeActionsWithAllValue() {
    assertEquals("All privileges", humanizeActions(ALL));
  }
  
  @Test
  void shouldHumanizeActionsWithSingleUppercaseAction() {
    assertEquals("Foo privilege", humanizeActions("FOO"));
  }
  
  @Test
  void shouldHumanizeActionsWithSingleLowercaseAction() {
    assertEquals("Foo privilege", humanizeActions("foo"));
  }
  
  @Test
  void shouldHumanizeActionsWithMultipleUppercaseActions() {
    assertEquals("Foo, Bar, Baz privileges", humanizeActions("FOO", "BAR", "BAZ"));
  }
  
  @Test
  void shouldHumanizeActionsWithMultipleLowercaseActions() {
    assertEquals("Foo, Bar, Baz privileges", humanizeActions("foo", "bar", "baz"));
  }
  
  @Test
  void shouldWorkWithStringTemplatesInHumanizeName() {
    String name = "foo";
    String format = "bar";
    
    // Using String Template with variables
    assertEquals("foo", humanizeName(name, format));
    
    // Using String Template with ALL constant
    assertEquals("all", humanizeName(ALL, ALL));
    
    // Using String Template with mixed values
    assertEquals("all 'bar'-format", humanizeName(ALL, format));
  }
  
  @Test
  void shouldWorkWithStringTemplatesInHumanizeActions() {
    String action1 = "foo";
    String action2 = "bar";
    String action3 = "baz";
    
    // Using String Template with single variable
    assertEquals("Foo privilege", humanizeActions(action1));
    
    // Using String Template with multiple variables
    assertEquals("Foo, Bar, Baz privileges", humanizeActions(action1, action2, action3));
    
    // Using String Template with ALL constant
    assertEquals("All privileges", humanizeActions(ALL));
    
    // Using String Template with mixed case
    String upperAction = "FOO";
    assertEquals("Foo privilege", humanizeActions(upperAction));
  }
}