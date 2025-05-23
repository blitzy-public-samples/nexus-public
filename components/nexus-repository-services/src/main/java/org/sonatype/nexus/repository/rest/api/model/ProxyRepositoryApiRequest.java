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

import org.sonatype.nexus.repository.types.ProxyType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @since 3.20
 */
public class ProxyRepositoryApiRequest
    extends AbstractRepositoryApiRequest
{
  @NotNull
  @Valid
  private final StorageAttributes storage;

  @Valid
  private final CleanupPolicyAttributes cleanup;

  @NotNull
  @Valid
  private final ProxyAttributes proxy;

  @NotNull
  @Valid
  private final NegativeCacheAttributes negativeCache;

  @NotNull
  @Valid
  private final HttpClientAttributes httpClient;

  private final String routingRule;

  private final ReplicationAttributes replication;

  @SuppressWarnings("squid:S00107") // suppress constructor parameter count
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public ProxyRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRule") final String routingRule,
      @JsonProperty("replication") @JsonInclude(value= Include.NON_EMPTY, content=Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    super(name, format, ProxyType.NAME, online);
    this.storage = storage;
    this.cleanup = cleanup;
    this.proxy = proxy;
    this.negativeCache = negativeCache;
    this.httpClient = httpClient;
    this.routingRule = routingRule;
    this.replication = replication;
  }

  /**
   * Gets the storage attributes using record pattern matching when available.
   * 
   * @return the storage attributes
   */
  public StorageAttributes getStorage() {
    if (storage instanceof StorageAttributes(var blobStoreName, var strictContentTypeValidation)) {
      // Using record pattern to access the components directly
      return storage;
    }
    return storage;
  }

  /**
   * Gets the cleanup policy attributes using record pattern matching when available.
   * 
   * @return the cleanup policy attributes
   */
  public CleanupPolicyAttributes getCleanup() {
    if (cleanup instanceof CleanupPolicyAttributes(var policyNames)) {
      // Using record pattern to access the component directly
      return cleanup;
    }
    return cleanup;
  }

  /**
   * Gets the proxy attributes using record pattern matching when available.
   * 
   * @return the proxy attributes
   */
  public ProxyAttributes getProxy() {
    if (proxy instanceof ProxyAttributes(var remoteUrl, var contentMaxAge, var metadataMaxAge)) {
      // Using record pattern to access the components directly
      return proxy;
    }
    return proxy;
  }

  /**
   * Gets the negative cache attributes using record pattern matching when available.
   * 
   * @return the negative cache attributes
   */
  public NegativeCacheAttributes getNegativeCache() {
    if (negativeCache instanceof NegativeCacheAttributes(var enabled, var timeToLive)) {
      // Using record pattern to access the components directly
      return negativeCache;
    }
    return negativeCache;
  }

  /**
   * Gets the HTTP client attributes using record pattern matching when available.
   * 
   * @return the HTTP client attributes
   */
  public HttpClientAttributes getHttpClient() {
    if (httpClient instanceof HttpClientAttributes(var blocked, var autoBlock, var connection)) {
      // Using record pattern to access the components directly
      return httpClient;
    }
    return httpClient;
  }

  /**
   * Gets the routing rule.
   * 
   * @return the routing rule
   */
  public String getRoutingRule() {
    return routingRule;
  }

  /**
   * Gets the replication attributes using record pattern matching when available.
   * 
   * @return the replication attributes
   */
  public ReplicationAttributes getReplication() { 
    if (replication != null && replication instanceof ReplicationAttributes(var enabled, var url, var credential)) {
      // Using record pattern to access the components directly
      return replication;
    }
    return replication; 
  }

}
