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

import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.HttpClientAttributes;
import org.sonatype.nexus.repository.rest.api.model.NegativeCacheAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ProxyRepositoryApiRequest;
import org.sonatype.nexus.repository.rest.api.model.ReplicationAttributes;
import org.sonatype.nexus.repository.rest.api.model.StorageAttributes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;

import static org.sonatype.nexus.repository.raw.ContentDisposition.ATTACHMENT;

/**
 * Raw proxy repository API request.
 *
 * @since 3.24
 */
@ApiModel("RawProxyRepositoryApiRequest")
@JsonIgnoreProperties({"format", "type"})
public class RawProxyRepositoryApiRequest
    extends ProxyRepositoryApiRequest
{
  private final RawAttributes raw;

  /**
   * Constructor using Java 21 pattern matching for default values.
   */
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  @SuppressWarnings("squid:S00107") // suppress constructor parameter count
  public RawProxyRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRule") final String routingRule,
      @JsonProperty("raw") final RawAttributes raw,
      @JsonProperty("replication") @JsonInclude(value = Include.NON_EMPTY, content = Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    super(name, RawFormat.NAME, online, storage, cleanup, proxy, negativeCache, httpClient, routingRule, replication);
    // Using pattern matching for null check with Java 21
    this.raw = switch (raw) {
      case null -> new RawAttributes(ATTACHMENT);
      case RawAttributes attributes -> attributes;
    };
  }

  /**
   * Returns the raw attributes for this repository.
   *
   * @return the raw attributes
   */
  public RawAttributes getRaw() {
    return raw;
  }
}