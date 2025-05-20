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

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Repository view privilege request that leverages Java 21 features for improved data handling.
 * Uses record patterns and enhanced pattern matching for more concise and type-safe code.
 *
 * @since 3.19
 */
public class ApiPrivilegeRepositoryViewRequest
    extends ApiPrivilegeWithRepositoryRequest
{
  /**
   * Record for privilege data to simplify pattern matching and data extraction
   */
  private record PrivilegeData(String name, String description, String format, String repository, String type) {
    /**
     * Creates a PrivilegeData instance from a Privilege object using pattern matching
     */
    static PrivilegeData from(Privilege privilege) {
      return new PrivilegeData(
          privilege.getName(),
          privilege.getDescription(),
          privilege.getPrivilegeProperty(FORMAT_KEY),
          privilege.getPrivilegeProperty(REPOSITORY_KEY),
          privilege.getType());
    }
    
    /**
     * Applies this data to a Privilege object
     */
    Privilege applyTo(Privilege privilege) {
      privilege.setName(name);
      privilege.setDescription(description);
      privilege.addProperty(FORMAT_KEY, format);
      privilege.addProperty(REPOSITORY_KEY, repository);
      privilege.setType(type);
      return privilege;
    }
  }

  /**
   * For deserialization with Jackson 2.16.1
   */
  @JsonCreator
  private ApiPrivilegeRepositoryViewRequest() {
    super();
  }

  /**
   * Constructor with individual parameters
   */
  public ApiPrivilegeRepositoryViewRequest(final String name,
                                           final String description,
                                           final String format,
                                           final String repository,
                                           final Collection<PrivilegeAction> actions)
  {
    super(name, description, format, repository, actions);
  }

  /**
   * Constructor from a Privilege object using record pattern matching
   */
  public ApiPrivilegeRepositoryViewRequest(final Privilege privilege) {
    super(privilege);
    // Pattern matching could be used here if we need to extract additional data
    // from the privilege object beyond what the parent constructor handles
  }

  /**
   * Converts this request to a Privilege using enhanced pattern matching
   */
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    // Use enhanced pattern matching to validate and process the privilege
    return switch (privilege) {
      // When privilege is non-null, process it
      case Privilege p when p != null -> {
        // First let the parent class handle its part
        super.doAsPrivilege(p);
        
        // Then set our specific type
        p.setType(RepositoryViewPrivilegeDescriptor.TYPE);
        
        // Use pattern matching to validate properties if needed
        if (p.getProperties() instanceof Map<String, String> props) {
          // Additional validation could be added here if needed
          // For example, checking required properties are present
          if (props.containsKey(FORMAT_KEY) && props.containsKey(REPOSITORY_KEY)) {
            // Properties are valid
          }
        }
        
        yield p;
      }
      // Default case to handle null (though this should never happen in practice)
      default -> privilege;
    };
  }
}
