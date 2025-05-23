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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.repository.types.GroupType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @since 3.20
 */
@JsonIgnoreProperties({"type"})
public class GroupRepositoryApiRequest
    extends AbstractRepositoryApiRequest
{
  @Schema(description = "Storage attributes for the repository")
  @NotNull
  @Valid
  protected final StorageAttributesRecord storage;

  @Schema(description = "Group attributes for the repository")
  @NotNull
  @Valid
  protected final GroupAttributesRecord group;

  @JsonCreator
  public GroupRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final Object storageObj,
      @JsonProperty("group") final Object groupObj)
  {
    super(name, format, GroupType.NAME, online);
    
    // Handle both legacy and record-based attribute objects using pattern matching
    this.storage = switch (storageObj) {
      case StorageAttributesRecord record -> record;
      case StorageAttributes attributes -> StorageAttributesRecord.from(attributes);
      default -> throw new IllegalArgumentException("Invalid storage attributes type: " + storageObj.getClass().getName());
    };
    
    this.group = switch (groupObj) {
      case GroupAttributesRecord record -> record;
      case GroupAttributes attributes -> GroupAttributesRecord.from(attributes);
      default -> throw new IllegalArgumentException("Invalid group attributes type: " + groupObj.getClass().getName());
    };
  }

  public StorageAttributesRecord getStorage() {
    return storage;
  }

  public GroupAttributesRecord getGroup() {
    return group;
  }
}