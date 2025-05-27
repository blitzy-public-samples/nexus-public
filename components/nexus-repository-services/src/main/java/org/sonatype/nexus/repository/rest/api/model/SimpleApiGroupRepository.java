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

import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.repository.types.GroupType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API Group Repository for simple formats which do not have custom attributes for groups.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiGroupRepository
    extends AbstractApiRepository
{
  @Schema(description = "Storage attributes")
  @NotNull
  protected final StorageAttributesRecord storage;

  @Schema(description = "Group attributes")
  @NotNull
  protected final GroupAttributesRecord group;

  @JsonCreator
  public SimpleApiGroupRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributesRecord storage,
      @JsonProperty("group") final GroupAttributesRecord group)
  {
    super(name, format, GroupType.NAME, url, online);
    this.storage = storage;
    this.group = group;
  }

  /**
   * Constructor that accepts legacy attribute types and converts them to records
   */
  public SimpleApiGroupRepository(
      final String name,
      final String format,
      final String url,
      final Boolean online,
      final StorageAttributes storage,
      final GroupAttributes group)
  {
    super(name, format, GroupType.NAME, url, online);
    this.storage = StorageAttributesRecord.from(storage);
    this.group = GroupAttributesRecord.from(group);
  }

  public StorageAttributesRecord getStorage() {
    return storage;
  }

  public GroupAttributesRecord getGroup() {
    return group;
  }
  
  /**
   * Gets the blob store name using Record Pattern matching
   * 
   * @return the blob store name
   */
  public String getBlobStoreName() {
    if (storage instanceof StorageAttributesRecord(String blobStoreName, var ignored)) {
      return blobStoreName;
    }
    return null;
  }
  
  /**
   * Gets the strict content type validation flag using Record Pattern matching
   * 
   * @return the strict content type validation flag
   */
  public Boolean getStrictContentTypeValidation() {
    if (storage instanceof StorageAttributesRecord(var ignored, Boolean strictContentTypeValidation)) {
      return strictContentTypeValidation;
    }
    return null;
  }
}