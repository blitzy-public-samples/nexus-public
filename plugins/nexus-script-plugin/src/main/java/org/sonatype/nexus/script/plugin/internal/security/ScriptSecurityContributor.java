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
package org.sonatype.nexus.script.plugin.internal.security;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributor;
import org.sonatype.nexus.security.config.SecurityContributorSupport;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege.MemoryCPrivilegeBuilder;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor.P_ACTIONS;
import static org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor.P_NAME;
import static org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor.TYPE;
import static org.sonatype.nexus.security.privilege.PrivilegeDescriptorSupport.ALL;

/**
 * Script security configuration.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ScriptSecurityContributor
    extends SecurityContributorSupport
    implements SecurityContributor
{
  public static final String SCRIPT_ALL_PREFIX = "nx-script-*-";

  public static final String SCRIPT_ALL_DESCRIPTION_SUFFIX = " permissions for Scripts";

  public static final String ACTION_BROWSE = "browse";

  public static final String ACTION_READ = "read";

  public static final String ACTION_EDIT = "edit";

  public static final String ACTION_ADD = "add";

  public static final String ACTION_DELETE = "delete";

  public static final String ACTION_RUN = "run";

  /**
   * Defines the set of standard script actions.
   * @since 3.45
   */
  private static final record ScriptAction(String name, boolean requiresRead) {}
  
  /**
   * Standard script actions with their read requirements.
   * @since 3.45
   */
  private static final ScriptAction[] SCRIPT_ACTIONS = {
      new ScriptAction(ALL, false),
      new ScriptAction(ACTION_BROWSE, true),
      new ScriptAction(ACTION_READ, false),
      new ScriptAction(ACTION_EDIT, true),
      new ScriptAction(ACTION_ADD, true),
      new ScriptAction(ACTION_DELETE, true),
      new ScriptAction(ACTION_RUN, false)
  };

  @Override
  public SecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();

    // Add all standard script privileges
    for (ScriptAction action : SCRIPT_ACTIONS) {
      config.addPrivilege(createScriptPrivilege(action));
    }

    return config;
  }

  /**
   * Creates a script privilege for the given action.
   *
   * @param action the script action
   * @return the memory privilege
   * @since 3.45
   */
  private MemoryCPrivilege createScriptPrivilege(final ScriptAction action) {
    requireNonNull(action, "Script action cannot be null");
    return createScriptPrivilege(action.name());
  }

  /**
   * Creates a script privilege for the given action name.
   *
   * @param action the action name
   * @return the memory privilege
   */
  private MemoryCPrivilege createScriptPrivilege(final String action) {
    requireNonNull(action, "Action cannot be null");
    
    final String id = SCRIPT_ALL_PREFIX + action;
    final String description = (ALL.equals(action) ? "All" : capitalize(action)) + SCRIPT_ALL_DESCRIPTION_SUFFIX;
    
    // Determine if this action requires read permission to be included
    final String actions = switch (action) {
      case ALL, ACTION_READ, ACTION_RUN -> action;
      default -> String.join(",", action, ACTION_READ);
    };

    return new MemoryCPrivilegeBuilder(id)
        .type(TYPE)
        .readOnly(true)
        .name(id)
        .description(description)
        .property(P_NAME, ALL)
        .property(P_ACTIONS, actions)
        .build();
  }
}
