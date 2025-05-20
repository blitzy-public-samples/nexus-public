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
package org.sonatype.nexus.repository.security.rest;

import java.util.Collection;
import java.util.Map;

import org.sonatype.nexus.repository.security.RepositoryViewPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

/**
 * Repository view privilege API model that leverages Java 21 features like record patterns and pattern matching.
 *
 * @since 3.19
 */
public class ApiPrivilegeRepositoryView
    extends ApiPrivilegeWithRepository
{
  /**
   * Default constructor for deserialization by Jackson
   */
  private ApiPrivilegeRepositoryView() {
    super(RepositoryViewPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructor with individual parameters
   */
  public ApiPrivilegeRepositoryView(final String name,
                                    final String description,
                                    final boolean readOnly,
                                    final String format,
                                    final String repository,
                                    final Collection<PrivilegeAction> actions)
  {
    super(RepositoryViewPrivilegeDescriptor.TYPE, name, description, readOnly, format, repository, actions);
  }

  /**
   * Constructor from a Privilege object using pattern matching for property extraction
   */
  public ApiPrivilegeRepositoryView(final Privilege privilege) {
    // Using pattern matching to validate the privilege object
    if (privilege instanceof Privilege p && RepositoryViewPrivilegeDescriptor.TYPE.equals(p.getType())) {
      // Using record patterns to extract properties more elegantly
      if (p.getProperties() instanceof Map<String, String> properties) {
        super.setType(p.getType());
        super.setName(p.getName());
        super.setDescription(p.getDescription());
        super.setReadOnly(p.isReadOnly());
        super.setFormat(properties.get(FORMAT_KEY));
        super.setRepository(properties.get(REPOSITORY_KEY));
        
        // Extract actions using pattern matching
        String actionsStr = properties.get(ACTIONS_KEY);
        if (actionsStr != null) {
          setActions(parseActions(actionsStr));
        }
      }
    }
    else {
      // Fallback to parent constructor if pattern matching fails
      super(privilege);
    }
  }
  
  /**
   * Parse actions string into collection of PrivilegeAction using pattern matching
   */
  private Collection<PrivilegeAction> parseActions(String actionsStr) {
    return java.util.Arrays.stream(actionsStr.split(","))
        .map(action -> switch (action.trim()) {
          case var s when "create".equals(s) -> PrivilegeAction.ADD;
          case var s when "update".equals(s) -> PrivilegeAction.EDIT;
          case var s when "read".equals(s) -> PrivilegeAction.READ;
          case var s when "delete".equals(s) -> PrivilegeAction.DELETE;
          case var s when "browse".equals(s) -> PrivilegeAction.BROWSE;
          case var s when "*".equals(s) -> PrivilegeAction.ALL;
          default -> null;
        })
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toList());
  }
}
