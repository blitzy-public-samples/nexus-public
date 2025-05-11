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
import org.sonatype.nexus.repository.maven.rest.HttpClientAttributesWithPreemptiveAuth;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.NegativeCacheAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ReplicationAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiProxyRepository;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API model for a maven proxy repository.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public record MavenProxyApiRepository(
    @JsonProperty("name") String name,
    @JsonProperty("url") String url,
    @JsonProperty("online") Boolean online,
    @JsonProperty("storage") StorageAttributes storage,
    @JsonProperty("cleanup") CleanupPolicyAttributes cleanup,
    @JsonProperty("proxy") ProxyAttributes proxy,
    @JsonProperty("negativeCache") NegativeCacheAttributes negativeCache,
    @JsonProperty("httpClient") HttpClientAttributesWithPreemptiveAuth httpClient,
    @JsonProperty("routingRuleName") String routingRuleName,
    @Valid @NotNull @JsonProperty("maven") MavenAttributes maven,
    @JsonProperty("replication") @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
    ReplicationAttributes replication,
    // This field holds the delegate SimpleApiProxyRepository instance
    @JsonIgnore SimpleApiProxyRepository delegate)
{
  /**
   * Creates a new MavenProxyApiRepository with the specified attributes.
   */
  @JsonCreator
  public MavenProxyApiRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributesWithPreemptiveAuth httpClient,
      @JsonProperty("routingRuleName") final String routingRuleName,
      @JsonProperty("maven") final MavenAttributes maven,
      @JsonProperty("replication") @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    this(name, url, online, storage, cleanup, proxy, negativeCache, httpClient, routingRuleName, maven, replication,
        new SimpleApiProxyRepository(name, Maven2Format.NAME, url, online, storage, cleanup, proxy, negativeCache, 
            httpClient, routingRuleName, replication));
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