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
package org.sonatype.nexus.testsuite.testsupport.saml;

import java.net.URI;

import org.sonatype.nexus.testsuite.testsupport.NexusClientFactory;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Factory for creating SAML clients for test scenarios.
 * <p>
 * This factory leverages Java 21 Virtual Threads for improved concurrency in test scenarios,
 * enabling more efficient testing of SAML authentication flows with reduced resource utilization.
 * 
 * @since 3.13
 */
public class SamlClientFactory
    extends NexusClientFactory<SamlClient>
{
  /**
   * Creates a new {@link SamlClient} instance with the provided HTTP client, context, and repository URI.
   * <p>
   * When invoked through the parent class's {@code createClient(URL, String, String)} method,
   * this operation will be executed using a Virtual Thread for improved I/O performance.
   *
   * @param httpClient the HTTP client to use for SAML-related requests
   * @param httpClientContext the HTTP client context with authentication settings
   * @param repositoryBaseUri the base URI of the repository
   * @return a new {@link SamlClient} instance configured for SAML authentication testing
   */
  @Override
  public SamlClient createClient(final CloseableHttpClient httpClient,
                                final HttpClientContext httpClientContext,
                                final URI repositoryBaseUri)
  {
    return new SamlClient(httpClient, httpClientContext, repositoryBaseUri);
  }
}