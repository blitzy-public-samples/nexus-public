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
package org.sonatype.nexus.repository.apt.api;

import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ComponentAttributes;
import org.sonatype.nexus.repository.rest.api.model.HostedStorageAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiHostedRepository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST API model representing an Apt repository.
 * 
 * This class has been updated for Java 21 compatibility to work with record-based
 * attribute classes and leverage pattern matching for improved type safety and
 * maintainability.
 * 
 * @since 3.20
 * @since 3.60 Updated for Java 21 compatibility
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
@Schema(description = "APT hosted repository configuration")
public class AptHostedApiRepository
    extends SimpleApiHostedRepository
{
  @NotNull
  @Schema(description = "APT repository configuration attributes")
  protected final AptHostedRepositoriesAttributes apt;

  @NotNull
  @Schema(description = "APT signing configuration attributes")
  protected final AptSigningRepositoriesAttributes aptSigning;

  /**
   * Creates a new APT hosted repository configuration.
   * 
   * @param name       Repository name
   * @param url        Repository URL
   * @param online     Whether the repository is online
   * @param storage    Storage attributes
   * @param cleanup    Cleanup policy attributes
   * @param apt        APT repository attributes
   * @param aptSigning APT signing attributes
   * @param component  Component attributes
   */
  @JsonCreator
  public AptHostedApiRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final HostedStorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("apt") final AptHostedRepositoriesAttributes apt,
      @JsonProperty("aptSigning") final AptSigningRepositoriesAttributes aptSigning,
      @JsonProperty("component") final ComponentAttributes component)
  {
    super(name, AptFormat.NAME, url, online, storage, cleanup, component);
    this.apt = apt;
    this.aptSigning = aptSigning;
  }

  /**
   * Returns the APT repository configuration attributes.
   * 
   * @return the APT repository configuration attributes
   */
  public AptHostedRepositoriesAttributes getApt() {
    return apt;
  }

  /**
   * Returns the APT signing configuration attributes.
   * 
   * @return the APT signing configuration attributes
   */
  public AptSigningRepositoriesAttributes getAptSigning() {
    return aptSigning;
  }
}