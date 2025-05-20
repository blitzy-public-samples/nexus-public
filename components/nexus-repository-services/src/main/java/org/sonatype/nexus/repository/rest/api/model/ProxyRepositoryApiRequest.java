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
 * REST API model for proxy repository requests.
 * 
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
   * Returns the storage attributes for this repository.
   * 
   * @return the storage attributes record
   */
  public StorageAttributes getStorage() {
    return storage;
  }

  /**
   * Returns the cleanup policy attributes for this repository.
   * 
   * @return the cleanup policy attributes
   */
  public CleanupPolicyAttributes getCleanup() {
    return cleanup;
  }

  /**
   * Returns the proxy attributes for this repository.
   * 
   * @return the proxy attributes
   */
  public ProxyAttributes getProxy() {
    return proxy;
  }

  /**
   * Returns the negative cache attributes for this repository.
   * 
   * @return the negative cache attributes
   */
  public NegativeCacheAttributes getNegativeCache() {
    return negativeCache;
  }

  /**
   * Returns the HTTP client attributes for this repository.
   * 
   * @return the HTTP client attributes
   */
  public HttpClientAttributes getHttpClient() {
    return httpClient;
  }

  /**
   * Returns the routing rule for this repository.
   * 
   * @return the routing rule name
   */
  public String getRoutingRule() {
    return routingRule;
  }

  /**
   * Returns the replication attributes for this repository.
   * 
   * @return the replication attributes record
   */
  public ReplicationAttributes getReplication() {
    return replication;
  }

  /**
   * Extracts the blob store name from storage attributes using record pattern matching.
   * 
   * @return the blob store name
   */
  public String getBlobStoreName() {
    if (storage instanceof StorageAttributes(String blobStoreName, Boolean _)) {
      return blobStoreName;
    }
    return storage.blobStoreName();
  }

  /**
   * Extracts the preemptive pull enabled flag from replication attributes using record pattern matching.
   * 
   * @return true if preemptive pull is enabled, false otherwise or if replication is null
   */
  public boolean isPreemptivePullEnabled() {
    if (replication instanceof ReplicationAttributes(Boolean enabled, String _)) {
      return enabled != null && enabled;
    }
    return replication != null && replication.preemptivePullEnabled() != null && replication.preemptivePullEnabled();
  }
}
