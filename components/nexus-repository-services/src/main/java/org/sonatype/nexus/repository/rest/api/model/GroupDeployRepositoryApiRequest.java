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
package org.sonatype.nexus.repository.rest.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API model for group repository requests with deployment capabilities.
 *
 * @since 3.28
 */
@JsonIgnoreProperties({"type"})
public class GroupDeployRepositoryApiRequest
    extends GroupRepositoryApiRequest
{
  public GroupDeployRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final Object storage,
      @JsonProperty("group") final Object group)
  {
    super(name, format, online, storage, group);
  }

  @Override
  public GroupDeployAttributesRecord getGroup() {
    // Use pattern matching to handle the group object appropriately
    return switch (super.getGroup()) {
      case GroupDeployAttributesRecord record -> record;
      case GroupAttributesRecord record -> {
        // If it's a regular GroupAttributesRecord, we need to convert it to a GroupDeployAttributesRecord
        // with a null writableMember
        yield new GroupDeployAttributesRecord(record.memberNames(), null);
      }
      default -> throw new IllegalArgumentException("Invalid group attributes type: " + 
          super.getGroup().getClass().getName());
    };
  }
}