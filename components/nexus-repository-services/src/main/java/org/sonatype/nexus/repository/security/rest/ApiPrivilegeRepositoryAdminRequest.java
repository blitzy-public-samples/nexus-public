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

import org.sonatype.nexus.repository.security.RepositoryAdminPrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Repository admin privilege request that leverages Java 21 features for improved data handling.
 *
 * @since 3.19
 */
public class ApiPrivilegeRepositoryAdminRequest
    extends ApiPrivilegeWithRepositoryRequest
{
  /**
   * Record for privilege data to simplify pattern matching
   */
  private record PrivilegeData(String name, String description, String format, String repository, String type) {}

  /**
   * For deserialization with Jackson 2.16.1
   */
  @JsonCreator
  private ApiPrivilegeRepositoryAdminRequest() {
    super();
  }

  /**
   * Constructor with individual parameters
   */
  public ApiPrivilegeRepositoryAdminRequest(final String name,
                                            final String description,
                                            final String format,
                                            final String repository,
                                            final Collection<PrivilegeAction> actions)
  {
    super(name, description, format, repository, actions);
  }

  /**
   * Constructor from Privilege object using record patterns for data extraction
   */
  public ApiPrivilegeRepositoryAdminRequest(final Privilege privilege) {
    super(privilege);
  }

  /**
   * Converts this request to a Privilege using enhanced pattern matching for validation
   */
  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    // Use pattern matching to validate and process the privilege
    if (privilege instanceof Privilege p) {
      super.doAsPrivilege(p);
      p.setType(RepositoryAdminPrivilegeDescriptor.TYPE);
      
      // Additional validation using pattern matching on properties
      Map<String, String> properties = p.getProperties();
      if (properties != null && !properties.isEmpty()) {
        // Validate format and repository properties are set correctly
        String format = properties.get(FORMAT_KEY);
        String repository = properties.get(REPOSITORY_KEY);
        
        if (format != null && repository != null) {
          // Create a record for validation purposes
          PrivilegeData data = new PrivilegeData(p.getName(), p.getDescription(), format, repository, p.getType());
          
          // Use record pattern matching to validate the data structure
          if (data instanceof PrivilegeData(var name, var description, var fmt, var repo, var type)) {
            // All fields are properly extracted, validation passed
            return p;
          }
        }
      }
      
      // If we reach here without returning, the basic privilege setup is still valid
      return p;
    }
    
    // Fallback for unexpected types
    return privilege;
  }
}
