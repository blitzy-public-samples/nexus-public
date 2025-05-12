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
import org.sonatype.nexus.repository.rest.api.model.HttpClientAttributes;
import org.sonatype.nexus.repository.rest.api.model.NegativeCacheAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ReplicationAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiProxyRepository;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST API model representing an Apt proxy repository.
 *
 * This class extends SimpleApiProxyRepository to provide APT-specific repository configuration.
 * It uses Java 21 features for improved type safety and pattern matching capabilities.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class AptProxyApiRepository
    extends SimpleApiProxyRepository
{
  @NotNull
  @Schema(description = "APT-specific repository configuration", required = true)
  protected final AptProxyRepositoriesAttributes apt;

  /**
   * Creates a new instance of APT proxy repository configuration.
   *
   * @param name            repository name
   * @param url             repository URL
   * @param online          repository online status
   * @param storage         storage attributes
   * @param cleanup         cleanup policy attributes
   * @param apt             APT-specific attributes
   * @param proxy           proxy attributes
   * @param negativeCache   negative cache attributes
   * @param httpClient      HTTP client attributes
   * @param routingRuleName routing rule name
   * @param replication     replication attributes (optional)
   */
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public AptProxyApiRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("apt") final AptProxyRepositoriesAttributes apt,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRuleName") final String routingRuleName,
      @JsonProperty("replication") @JsonInclude(value= Include.NON_EMPTY, content=Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    super(name, AptFormat.NAME, url, online, storage, cleanup, proxy, negativeCache, httpClient, routingRuleName,
        replication);
    this.apt = apt;
  }

  /**
   * Returns the APT-specific repository configuration attributes.
   *
   * @return the APT-specific attributes
   */
  public AptProxyRepositoriesAttributes getApt() {
    return apt;
  }
  
  /**
   * Utility method to extract APT configuration components using Java 21 record patterns.
   * This demonstrates how record patterns can be used to destructure the APT attributes
   * in a single step.
   *
   * @return a string representation of the APT configuration
   */
  public String getAptConfigSummary() {
    if (apt instanceof AptProxyRepositoriesAttributes(String distribution, Boolean flat)) {
      return String.format("APT repository for distribution '%s' (flat: %s)", distribution, flat);
    }
    return "APT repository with unknown configuration";
  }
}