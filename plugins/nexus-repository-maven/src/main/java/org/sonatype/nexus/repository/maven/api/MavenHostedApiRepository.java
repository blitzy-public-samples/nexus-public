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
package org.sonatype.nexus.repository.maven.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ComponentAttributes;
import org.sonatype.nexus.repository.rest.api.model.HostedStorageAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiHostedRepository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API model for a maven hosted repository.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public record MavenHostedApiRepository(
    @JsonProperty("name") String name,
    @JsonProperty("url") String url,
    @JsonProperty("online") Boolean online,
    @JsonProperty("storage") HostedStorageAttributes storage,
    @JsonProperty("cleanup") CleanupPolicyAttributes cleanup,
    @Valid @NotNull @JsonProperty("maven") MavenAttributes maven,
    @JsonProperty("component") ComponentAttributes component,
    // This field holds the delegate SimpleApiHostedRepository instance
    @JsonIgnore SimpleApiHostedRepository delegate)
{
  /**
   * Creates a new MavenHostedApiRepository with the specified attributes.
   */
  @JsonCreator
  public MavenHostedApiRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final HostedStorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("maven") final MavenAttributes maven,
      @JsonProperty("component") final ComponentAttributes component)
  {
    this(name, url, online, storage, cleanup, maven, component,
        new SimpleApiHostedRepository(name, Maven2Format.NAME, url, online, storage, cleanup, component));
  }
  
  /**
   * @return the format of the repository
   */
  public String getFormat() {
    return delegate.getFormat();
  }
  
  /**
   * @return the type of the repository
   */
  public String getType() {
    return delegate.getType();
  }
}