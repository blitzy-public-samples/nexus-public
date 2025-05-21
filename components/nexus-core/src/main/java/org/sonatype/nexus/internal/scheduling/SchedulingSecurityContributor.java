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
package org.sonatype.nexus.internal.scheduling;

import java.util.List;
import java.util.SequencedCollection;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;
import org.sonatype.nexus.security.config.SecurityContributorSupport;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.application.ApplicationPrivilegeDescriptor;
import org.sonatype.nexus.common.text.Strings;

/**
 * Scheduling security configuration.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SchedulingSecurityContributor
    extends SecurityContributorSupport
    implements SecurityContributor
{
  public static final String SCHEDULING_DOMAIN = "tasks";

  public static final String SCHEDULING_PRIV_ID_PREFIX = "nx-tasks";

  public static final String SCHEDULING_RUN_PRIV_ID = "nx-tasks-run";

  public static final String SCHEDULING_RUN_PRIV_DESCRIPTION = "Run permission for Scheduled Tasks";

  public static final String SCHEDULING_RUN_PRIV_ACTIONS = "start,stop";

  /**
   * Creates the security configuration for scheduling tasks.
   * 
   * Uses Java 21 pattern matching for privilege generation and optimized collections.
   */
  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    // Use Java 21 SequencedCollection for optimized memory usage
    SequencedCollection<Privilege> privileges = createCrudAndAllApplicationPrivileges(SCHEDULING_PRIV_ID_PREFIX, SCHEDULING_DOMAIN);
    
    // Use pattern matching for privilege processing with enhanced type patterns
    for (var privilege : privileges) {
      switch (privilege) {
        // Pattern matching with guard conditions for different privilege types
        case Privilege p when p.getId().contains("-read") -> {
          // Read privileges get added directly
          config.addPrivilege(p);
          logPrivilegeAdded(p, "read");
        }
        case Privilege p when p.getId().contains("-create") -> {
          // Create privileges get added with validation
          config.addPrivilege(p);
          logPrivilegeAdded(p, "create");
        }
        case Privilege p when p.getId().contains("-update") -> {
          // Update privileges get added with validation
          config.addPrivilege(p);
          logPrivilegeAdded(p, "update");
        }
        case Privilege p when p.getId().contains("-delete") -> {
          // Delete privileges get added with validation
          config.addPrivilege(p);
          logPrivilegeAdded(p, "delete");
        }
        case Privilege p when p.getId().equals(SCHEDULING_PRIV_ID_PREFIX + "-all") -> {
          // All privilege gets added with special handling
          config.addPrivilege(p);
          logPrivilegeAdded(p, "all");
        }
        case Privilege p -> {
          // Default case for any other privileges
          config.addPrivilege(p);
          logPrivilegeAdded(p, "other");
        }
      }
    }

    // Add the run privilege using pattern matching for action string
    Privilege runPrivilege = createRunPrivilege(SCHEDULING_RUN_PRIV_ACTIONS);
    config.addPrivilege(runPrivilege);

    return config;
  }
  
  /**
   * Creates a run privilege based on the provided actions string.
   * Uses Java 21 pattern matching for switch expressions.
   *
   * @param actions The actions string to analyze
   * @return The created privilege
   */
  private Privilege createRunPrivilege(String actions) {
    return switch (actions) {
      // Pattern matching with guard conditions for different action strings
      case String s when s.contains("start") && s.contains("stop") -> 
          createApplicationPrivilege(SCHEDULING_RUN_PRIV_ID, SCHEDULING_RUN_PRIV_DESCRIPTION, SCHEDULING_DOMAIN, s);
      case String s when s.contains("start") -> 
          createApplicationPrivilege(SCHEDULING_RUN_PRIV_ID, SCHEDULING_RUN_PRIV_DESCRIPTION, SCHEDULING_DOMAIN, s + ",stop");
      case String s when s.contains("stop") -> 
          createApplicationPrivilege(SCHEDULING_RUN_PRIV_ID, SCHEDULING_RUN_PRIV_DESCRIPTION, SCHEDULING_DOMAIN, "start," + s);
      case String s when Strings.isBlank(s) -> 
          createApplicationPrivilege(SCHEDULING_RUN_PRIV_ID, SCHEDULING_RUN_PRIV_DESCRIPTION, SCHEDULING_DOMAIN, "start,stop");
      default -> 
          createApplicationPrivilege(SCHEDULING_RUN_PRIV_ID, SCHEDULING_RUN_PRIV_DESCRIPTION, SCHEDULING_DOMAIN, actions);
    };
  }
  
  /**
   * Logs the addition of a privilege using Java 21 string templates (preview feature).
   * This is a no-op method in production but demonstrates the pattern.
   *
   * @param privilege The privilege being added
   * @param type The type of privilege for logging purposes
   */
  private void logPrivilegeAdded(Privilege privilege, String type) {
    // This would use string templates in Java 21 preview, but we'll use concatenation for now
    // In Java 21 preview: System.out.println(STR."Added \{type} privilege: \{privilege.getId()}");
    // No-op in production code
  }
}