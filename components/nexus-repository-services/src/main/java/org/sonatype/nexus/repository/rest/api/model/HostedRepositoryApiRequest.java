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
   * @return the storage attributes as a record
   */
  public HostedStorageAttributes getStorage() {
    return storage;
  }

  /**
   * Returns the cleanup policy attributes for this hosted repository.
   * 
   * @return the cleanup policy attributes
   */
  public CleanupPolicyAttributes getCleanup() {
    // Using pattern matching for records when CleanupPolicyAttributes becomes a record
    return cleanup;
  }

  /**
   * Returns the component attributes for this hosted repository.
   * 
   * @return the component attributes
   */
  public ComponentAttributes getComponent() {
    // Using pattern matching for records when ComponentAttributes becomes a record
    return component;
  }
}
