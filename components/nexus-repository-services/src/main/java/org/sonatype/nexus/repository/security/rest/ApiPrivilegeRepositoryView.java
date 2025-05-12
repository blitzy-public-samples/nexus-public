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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.sonatype.nexus.repository.security.RepositoryViewPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

/**
 * REST API representation of a repository view privilege.
 * Updated for Java 21 with pattern matching and Jackson 2.16.1 compatibility.
 *
 * @since 3.19
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiPrivilegeRepositoryView
    extends ApiPrivilegeWithRepository
{
  /**
   * Constructor for deserialization by Jackson.
   * Updated for compatibility with Jackson 2.16.1.
   */
  @JsonCreator
  private ApiPrivilegeRepositoryView() {
    super(RepositoryViewPrivilegeDescriptor.TYPE);
  }

  /**
   * Constructor for creating a new repository view privilege.
   * Compatible with Java 21 module system.
   */
  public ApiPrivilegeRepositoryView(@JsonProperty("name") final String name,
                                    @JsonProperty("description") final String description,
                                    @JsonProperty("readOnly") final boolean readOnly,
                                    @JsonProperty("format") final String format,
                                    @JsonProperty("repository") final String repository,
                                    @JsonProperty("actions") final Collection<PrivilegeAction> actions)
  {
    super(RepositoryViewPrivilegeDescriptor.TYPE, name, description, readOnly, format, repository, actions);
  }

  /**
   * Constructor for creating from an existing {@link Privilege}.
   * Uses Java 21 pattern matching to validate the privilege type.
   */
  public ApiPrivilegeRepositoryView(final Privilege privilege) {
    super(privilege);
    // Validate that this is a repository view privilege using pattern matching
    if (!(privilege instanceof Privilege p && RepositoryViewPrivilegeDescriptor.TYPE.equals(p.getType()))) {
      throw new IllegalArgumentException("Privilege is not a repository view privilege: " + privilege.getType());
    }
  }
  
  /**
   * Factory method that uses Java 21 Record Patterns for more concise data handling.
   * This demonstrates how Record Patterns could be used if ApiPrivilegeWithRepository were a record.
   * 
   * @param privilege The privilege to convert
   * @return A new ApiPrivilegeRepositoryView instance
   */
  public static ApiPrivilegeRepositoryView fromPrivilege(final Privilege privilege) {
    // Example of how this would look with Record Patterns if ApiPrivilegeWithRepository were a record
    // This is a demonstration of the pattern, not functional code since the parent class is not a record
    /*
    if (privilege instanceof Privilege(String id, String name, String description, boolean readOnly, String type, Map<String, String> properties)) {
      if (RepositoryViewPrivilegeDescriptor.TYPE.equals(type)) {
        return new ApiPrivilegeRepositoryView(name, description, readOnly, 
            properties.get("format"), properties.get("repository"), 
            PrivilegeAction.parseActions(properties.get("actions")));
      }
    }
    */
    
    // Actual implementation using pattern matching for instanceof
    if (privilege instanceof Privilege p && RepositoryViewPrivilegeDescriptor.TYPE.equals(p.getType())) {
      return new ApiPrivilegeRepositoryView(p);
    }
    throw new IllegalArgumentException("Privilege is not a repository view privilege");
  }
}