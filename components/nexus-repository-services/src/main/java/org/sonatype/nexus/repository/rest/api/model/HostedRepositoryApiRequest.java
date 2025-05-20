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

import java.util.Collection;
import java.util.Optional;

import org.sonatype.nexus.repository.types.HostedType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API model for hosted repository requests.
 * 
 * @since 3.20
 */
@JsonIgnoreProperties({"type"})
public class HostedRepositoryApiRequest
    extends AbstractRepositoryApiRequest
{
  @NotNull
  @Valid
  private final HostedStorageAttributes storage;

  @Valid
  private final CleanupPolicyAttributes cleanup;

  @Valid
  private final ComponentAttributes component;

  @JsonCreator
  public HostedRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final HostedStorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("component") final ComponentAttributes componentAttributes)
  {
    super(name, format, HostedType.NAME, online);
    this.storage = storage;
    this.cleanup = cleanup;
    this.component = componentAttributes;
  }

  /**
   * Returns the storage attributes for this hosted repository.
   * 
   * @return the storage attributes record
   */
  public HostedStorageAttributes getStorage() {
    return storage;
  }
  
  /**
   * Returns the blob store name from the storage attributes.
   * Demonstrates the use of record patterns in Java 21.
   * 
   * @return the blob store name
   */
  public String getBlobStoreName() {
    return switch (storage) {
      case HostedStorageAttributes(String blobStoreName, _, _) -> blobStoreName;
      default -> storage.blobStoreName();
    };
  }
  
  /**
   * Returns the write policy from the storage attributes.
   * Demonstrates the use of record patterns in Java 21.
   * 
   * @return the write policy
   */
  public String getWritePolicy() {
    return switch (storage) {
      case HostedStorageAttributes(_, _, String writePolicy) -> writePolicy;
      default -> storage.writePolicy();
    };
  }
  
  /**
   * Returns whether strict content type validation is enabled.
   * Demonstrates the use of record patterns in Java 21.
   * 
   * @return true if strict content type validation is enabled, false otherwise
   */
  public boolean isStrictContentTypeValidation() {
    return switch (storage) {
      case HostedStorageAttributes(_, Boolean strictContentTypeValidation, _) -> strictContentTypeValidation;
      default -> storage.strictContentTypeValidation();
    };
  }

  /**
   * Returns the cleanup policy attributes for this hosted repository.
   * 
   * @return the cleanup policy attributes
   */
  public CleanupPolicyAttributes getCleanup() {
    return cleanup;
  }
  
  /**
   * Returns the policy names from the cleanup policy attributes.
   * This method is prepared for future conversion of CleanupPolicyAttributes to a record.
   * 
   * @return the policy names or empty collection if cleanup is null
   */
  public Collection<String> getPolicyNames() {
    return Optional.ofNullable(cleanup)
        .map(CleanupPolicyAttributes::getPolicyNames)
        .orElse(null);
  }

  /**
   * Returns the component attributes for this hosted repository.
   * 
   * @return the component attributes
   */
  public ComponentAttributes getComponent() {
    return component;
  }
  
  /**
   * Returns whether components in this repository are proprietary.
   * This method is prepared for future conversion of ComponentAttributes to a record.
   * 
   * @return true if components are proprietary, false otherwise or if component is null
   */
  public Boolean areProprietaryComponents() {
    return Optional.ofNullable(component)
        .map(ComponentAttributes::getProprietaryComponents)
        .orElse(null);
  }
}
