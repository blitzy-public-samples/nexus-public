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
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model for group repository requests.
 *
 * @since 3.20
 */
@JsonIgnoreProperties({"type"})
public class GroupRepositoryApiRequest
    extends AbstractRepositoryApiRequest
{
  @ApiModelProperty
  @NotNull
  @Valid
  protected final StorageAttributes storage;

  @ApiModelProperty
  @NotNull
  @Valid
  protected final GroupAttributes group;

  @JsonCreator
  public GroupRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("group") final GroupAttributes group)
  {
    super(name, format, GroupType.NAME, online);
    this.storage = storage;
    this.group = group;
  }

  /**
   * Returns the storage attributes for this repository.
   *
   * @return the storage attributes record
   */
  public StorageAttributes getStorage() {
    return storage;
  }

  /**
   * Returns the group attributes for this repository.
   *
   * @return the group attributes record
   */
  public GroupAttributes getGroup() {
    return group;
  }
  
  /**
   * Utility method to extract storage components using record pattern matching.
   * 
   * @return a tuple containing the blobStoreName and strictContentTypeValidation values
   */
  public Object[] extractStorageComponents() {
    if (storage instanceof StorageAttributes(String blobStoreName, Boolean strictContentTypeValidation)) {
      return new Object[]{blobStoreName, strictContentTypeValidation};
    }
    return new Object[]{storage.blobStoreName(), storage.strictContentTypeValidation()};
  }
  
  /**
   * Utility method to check if this repository uses a specific blob store.
   * Demonstrates the use of record patterns with conditional checks.
   * 
   * @param blobStoreName the blob store name to check
   * @return true if this repository uses the specified blob store
   */
  public boolean usesBlobStore(String blobStoreName) {
    return storage instanceof StorageAttributes(String storageName, var _) && 
           storageName.equals(blobStoreName);
  }
}