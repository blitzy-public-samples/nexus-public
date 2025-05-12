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
package org.sonatype.nexus.repository.raw.rest;

import org.sonatype.nexus.repository.raw.ContentDisposition;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ComponentAttributes;
import org.sonatype.nexus.repository.rest.api.model.HostedRepositoryApiRequest;
import org.sonatype.nexus.repository.rest.api.model.HostedStorageAttributes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.sonatype.nexus.repository.raw.ContentDisposition.ATTACHMENT;

/**
 * REST API request for Raw hosted repositories.
 * 
 * @since 3.24
 */
@JsonIgnoreProperties({"format", "type"})
public class RawHostedRepositoryApiRequest
    extends HostedRepositoryApiRequest
{
  private final RawAttributes raw;

  /**
   * Creates a new Raw hosted repository API request.
   * 
   * @param name the repository name
   * @param online whether the repository is online
   * @param storage the storage attributes
   * @param cleanup the cleanup policy attributes
   * @param raw the raw attributes, or null to use default
   * @param componentAttributes the component attributes
   */
  @JsonCreator
  public RawHostedRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final HostedStorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("raw") final RawAttributes raw,
      @JsonProperty("component") final ComponentAttributes componentAttributes)
  {
    super(name, RawFormat.NAME, online, storage, cleanup, componentAttributes);
    // Use pattern matching to handle null case and extract content disposition if available
    this.raw = switch (raw) {
      case null -> new RawAttributes(ATTACHMENT);
      case RawAttributes(var contentDisposition) -> raw;
    };
  }

  /**
   * Gets the raw attributes for this repository request.
   * 
   * @return the raw attributes
   */
  public RawAttributes getRaw() {
    return raw;
  }
  
  /**
   * Gets the content disposition directly from the raw attributes.
   * Demonstrates the use of record patterns for direct component access.
   * 
   * @return the content disposition
   */
  public ContentDisposition getContentDisposition() {
    if (raw instanceof RawAttributes(var contentDisposition)) {
      return contentDisposition;
    }
    return ATTACHMENT; // Fallback, though this should never happen
  }
}
