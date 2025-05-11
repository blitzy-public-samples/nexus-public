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
package org.sonatype.nexus.repository.maven.rest;

import java.util.Objects;

import org.sonatype.nexus.repository.rest.api.model.HttpClientConnectionAuthenticationAttributes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST API model for describing authentication for HTTP connections used by a proxy repository supporting preemptive
 * authentication.
 *
 * @since 3.30
 */
public class HttpClientConnectionAuthenticationAttributesWithPreemptive
    extends HttpClientConnectionAuthenticationAttributes
{
  @Schema(description = "Whether to use pre-emptive authentication. Use with caution. Defaults to false.",
      example = "false")
  private final Boolean preemptive;

  /**
   * Creates a new instance with the specified authentication attributes and preemptive flag.
   *
   * @param type the authentication type
   * @param preemptive whether to use preemptive authentication
   * @param username the username for authentication
   * @param password the password for authentication
   * @param ntlmHost the NTLM host
   * @param ntlmDomain the NTLM domain
   * @param bearerToken the bearer token for authentication
   */
  @JsonCreator
  public HttpClientConnectionAuthenticationAttributesWithPreemptive(
      @JsonProperty("type") final String type,
      @JsonProperty("preemptive") final Boolean preemptive,
      @JsonProperty("username") final String username,
      @JsonProperty(value = "password", access = Access.WRITE_ONLY) final String password,
      @JsonProperty("ntlmHost") final String ntlmHost,
      @JsonProperty("ntlmDomain") final String ntlmDomain,
      @JsonProperty("bearerToken") final String bearerToken)
  {
    super(type, username, password, ntlmHost, ntlmDomain, bearerToken);
    this.preemptive = preemptive;
  }

  /**
   * Creates a new instance from existing authentication attributes and a preemptive flag.
   *
   * @param auth the existing authentication attributes
   * @param preemptive whether to use preemptive authentication
   */
  public HttpClientConnectionAuthenticationAttributesWithPreemptive(
      final HttpClientConnectionAuthenticationAttributes auth,
      final Boolean preemptive)
  {
    super(auth.getType(), auth.getUsername(), auth.getPassword(), auth.getNtlmHost(), auth.getNtlmDomain(),
        auth.getBearerToken());
    this.preemptive = preemptive;
  }

  /**
   * Returns whether pre-emptive authentication is enabled.
   *
   * @return true if pre-emptive authentication is enabled, false otherwise
   */
  @Override
  public Boolean isPreemptive() {
    return preemptive;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    
    HttpClientConnectionAuthenticationAttributesWithPreemptive that = 
        (HttpClientConnectionAuthenticationAttributesWithPreemptive) o;
    return Objects.equals(preemptive, that.preemptive);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), preemptive);
  }

  @Override
  public String toString() {
    return "HttpClientConnectionAuthenticationAttributesWithPreemptive{" +
        "type='" + getType() + '\'' +
        ", username='" + getUsername() + '\'' +
        ", ntlmHost='" + getNtlmHost() + '\'' +
        ", ntlmDomain='" + getNtlmDomain() + '\'' +
        ", preemptive=" + preemptive +
        '}'; // Note: password and bearerToken are intentionally excluded for security
  }
}