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

import javax.validation.constraints.NotNull;

import org.sonatype.nexus.repository.types.ProxyType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * API Proxy Repository for simple formats which do not have custom attributes for proxies.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiProxyRepository
    extends AbstractApiRepository
{
  @NotNull
  protected final StorageAttributes storage;

  protected final CleanupPolicyAttributes cleanup;

  @NotNull
  protected final ProxyAttributes proxy;

  @NotNull
  protected final NegativeCacheAttributes negativeCache;

  @NotNull
  protected final HttpClientAttributes httpClient;

  @ApiModelProperty(value = "The name of the routing rule assigned to this repository")
  protected final String routingRuleName;

  protected final ReplicationAttributes replication;

  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public SimpleApiProxyRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRuleName") final String routingRuleName,
      @JsonProperty("replication") @JsonInclude(value= Include.NON_EMPTY, content=Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    super(name, format, ProxyType.NAME, url, online);
    this.storage = storage;
    this.cleanup = cleanup;
    this.proxy = proxy;
    this.negativeCache = negativeCache;
    this.httpClient = httpClient;
    this.routingRuleName = routingRuleName;
    this.replication = replication;
 }

  /**
   * Get storage attributes using record pattern matching.
   *
   * @return the storage attributes
   */
  public StorageAttributes getStorage() {
    return storage;
  }

  /**
   * Get cleanup policy attributes.
   *
   * @return the cleanup policy attributes
   */
  public CleanupPolicyAttributes getCleanup() {
    return cleanup;
  }

  /**
   * Get proxy attributes using record pattern matching.
   * 
   * @return the proxy attributes
   */
  public ProxyAttributes getProxy() {
    if (proxy instanceof ProxyAttributes(var remoteUrl, var contentMaxAge, var metadataMaxAge)) {
      // Using record pattern to access components directly if needed
      return proxy;
    }
    return proxy;
  }

  /**
   * Get negative cache attributes using record pattern matching.
   *
   * @return the negative cache attributes
   */
  public NegativeCacheAttributes getNegativeCache() {
    if (negativeCache instanceof NegativeCacheAttributes(var enabled, var timeToLive)) {
      // Using record pattern to access components directly if needed
      return negativeCache;
    }
    return negativeCache;
  }

  /**
   * Get HTTP client attributes using record pattern matching.
   *
   * @return the HTTP client attributes
   */
  public HttpClientAttributes getHttpClient() {
    if (httpClient instanceof HttpClientAttributes(var blocked, var autoBlock, var connection, var authentication)) {
      // Using record pattern to access components directly if needed
      return httpClient;
    }
    return httpClient;
  }

  /**
   * Get the routing rule name.
   *
   * @return the routing rule name
   */
  public String getRoutingRuleName() {
    return routingRuleName;
  }

  /**
   * Get replication attributes using record pattern matching.
   *
   * @return the replication attributes
   */
  public ReplicationAttributes getReplication() { 
    if (replication instanceof ReplicationAttributes(var preemptivePullEnabled, var assetPathRegex)) {
      // Using record pattern to access components directly if needed
      return replication;
    }
    return replication; 
  }
}
