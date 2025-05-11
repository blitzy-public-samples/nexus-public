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
package org.sonatype.nexus.coreui;

import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.sonatype.nexus.validation.group.Create;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.sonatype.nexus.blobstore.BlobStoreSupport.MAX_NAME_LENGTH;
import static org.sonatype.nexus.blobstore.BlobStoreSupport.MIN_NAME_LENGTH;

/**
 * Data transfer object for blob store information.
 * 
 * @since 3.0
 */
public record BlobStoreXO(
  @NotEmpty
  @UniqueBlobStoreName(groups = Create.class)
  @Size(min = MIN_NAME_LENGTH, max = MAX_NAME_LENGTH)
  String name,

  @NotEmpty
  String type,

  boolean isQuotaEnabled,

  String quotaType,

  @Min(0L)
  Long quotaLimit,

  @NotEmpty
  Map<String, Map<String, Object>> attributes,

  @Min(0L)
  long blobCount,

  @Min(0L)
  long totalSize,

  @Min(0L)
  long availableSpace,

  @Min(0L)
  long repositoryUseCount,

  boolean unlimited,

  /**
   * @since 3.19
   */
  boolean unavailable,

  @Min(0L)
  long blobStoreUseCount,

  boolean inUse,

  boolean convertable,

  /**
   * @since 3.29
   */
  int taskUseCount,

  /**
   * The name of the group to which this blob store belongs, or null if not in a group.
   * 
   * @since 3.15
   */
  String groupName
) {
  /**
   * Custom constructor for Jackson deserialization with support for string-to-boolean conversion.
   */
  @JsonCreator
  public static BlobStoreXO create(
      @JsonProperty("name") String name,
      @JsonProperty("type") String type,
      @JsonProperty("isQuotaEnabled") Object isQuotaEnabled,
      @JsonProperty("quotaType") String quotaType,
      @JsonProperty("quotaLimit") Long quotaLimit,
      @JsonProperty("attributes") Map<String, Map<String, Object>> attributes,
      @JsonProperty("blobCount") long blobCount,
      @JsonProperty("totalSize") long totalSize,
      @JsonProperty("availableSpace") long availableSpace,
      @JsonProperty("repositoryUseCount") long repositoryUseCount,
      @JsonProperty("unlimited") boolean unlimited,
      @JsonProperty("unavailable") boolean unavailable,
      @JsonProperty("blobStoreUseCount") long blobStoreUseCount,
      @JsonProperty("inUse") boolean inUse,
      @JsonProperty("convertable") boolean convertable,
      @JsonProperty("taskUseCount") int taskUseCount,
      @JsonProperty("groupName") String groupName) {
    
    // Handle string-to-boolean conversion for isQuotaEnabled
    boolean quotaEnabled = false;
    if (isQuotaEnabled instanceof Boolean) {
      quotaEnabled = (Boolean) isQuotaEnabled;
    } else if (isQuotaEnabled instanceof String) {
      String strValue = (String) isQuotaEnabled;
      quotaEnabled = strValue != null && ("true".equalsIgnoreCase(strValue)
          || "on".equalsIgnoreCase(strValue) || "1".equalsIgnoreCase(strValue));
    }
    
    return new BlobStoreXO(
        name,
        type,
        quotaEnabled,
        quotaType,
        quotaLimit,
        attributes,
        blobCount,
        totalSize,
        availableSpace,
        repositoryUseCount,
        unlimited,
        unavailable,
        blobStoreUseCount,
        inUse,
        convertable,
        taskUseCount,
        groupName);
  }
}
