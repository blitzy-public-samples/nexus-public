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

import static java.lang.StringTemplate.STR;
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
  void should_humanize_name_correctly() {
    assertEquals("all", humanizeName(ALL, ALL));
    assertEquals("all 'bar'-format", humanizeName(ALL, "bar"));
    assertEquals("foo", humanizeName("foo", "bar"));
  }

  @Test
  void should_humanize_actions_correctly() {
    assertEquals("All privileges", humanizeActions(ALL));
    assertEquals("Foo privilege", humanizeActions("FOO"));
    assertEquals("Foo privilege", humanizeActions("foo"));
    assertEquals("Foo, Bar, Baz privileges", humanizeActions("FOO", "BAR", "BAZ"));
    assertEquals("Foo, Bar, Baz privileges", humanizeActions("foo", "bar", "baz"));
  }
  
  @Test
  void should_humanize_name_with_string_templates() {
    String name = ALL;
    String format = "maven2";
    
    // Using String Templates with the humanizeName method
    String result = STR."Name: \{humanizeName(name, format)}";
    assertEquals("Name: all 'maven2'-format", result);
    
    // Test with different values
    name = "browse";
    result = STR."Name: \{humanizeName(name, format)}";
    assertEquals("Name: browse", result);
  }
  
  @Test
  void should_humanize_actions_with_string_templates() {
    String action1 = "READ";
    String action2 = "WRITE";
    
    // Using String Templates with the humanizeActions method
    String result = STR."Actions: \{humanizeActions(action1)}";
    assertEquals("Actions: Read privilege", result);
    
    // Test with multiple actions
    result = STR."Actions: \{humanizeActions(action1, action2)}";
    assertEquals("Actions: Read, Write privileges", result);
    
    // Test with ALL action
    result = STR."Actions: \{humanizeActions(ALL)}";
    assertEquals("Actions: All privileges", result);
  }
  
  @Test
  void should_combine_multiple_privilege_descriptions_with_string_templates() {
    String name = "repository-admin";
    String format = "maven2";
    String action1 = "READ";
    String action2 = "WRITE";
    
    // Combining both humanizeName and humanizeActions in a single template
    String result = STR."""
        Privilege Information:
        - Name: \{humanizeName(name, format)}
        - Actions: \{humanizeActions(action1, action2)}
        """;
    
    String expected = """
        Privilege Information:
        - Name: repository-admin
        - Actions: Read, Write privileges
        """;
    
    assertEquals(expected, result);
  }
  
  @Test
  void should_handle_conditional_expressions_in_string_templates() {
    String action = "READ";
    boolean isAdmin = true;
    
    // Using conditional expressions within string templates
    String result = STR."User has \{humanizeActions(action)} and is \{isAdmin ? "an admin" : "not an admin"}";
    assertEquals("User has Read privilege and is an admin", result);
    
    // Test with different condition
    isAdmin = false;
    result = STR."User has \{humanizeActions(action)} and is \{isAdmin ? "an admin" : "not an admin"}";
    assertEquals("User has Read privilege and is not an admin", result);
  }
}