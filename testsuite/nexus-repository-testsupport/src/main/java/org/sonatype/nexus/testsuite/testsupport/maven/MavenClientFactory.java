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
package org.sonatype.nexus.testsuite.testsupport.maven;

import java.net.URI;

import org.sonatype.nexus.testsuite.testsupport.NexusClientFactory;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Factory for creation of {@link Maven2Client} instances.
 * <p>
 * This factory creates Maven2 clients for interacting with Maven repositories in Nexus.
 * It leverages Java 21 virtual threads through its parent class implementation to improve
 * I/O performance when creating and using clients.
 *
 * @since 3.16
 */
public class MavenClientFactory
    extends NexusClientFactory<Maven2Client>
{
  /**
   * Creates a Maven2 client with the provided HTTP client, context, and repository URI.
   * <p>
   * This implementation is compatible with Java 21 and benefits from virtual threads
   * when invoked through the parent class methods.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context with authentication settings
   * @param repositoryBaseUri the base URI of the repository
   * @return a new Maven2Client instance
   */
  @Override
  public Maven2Client createClient(final CloseableHttpClient httpClient,
                                   final HttpClientContext httpClientContext,
                                   final URI repositoryBaseUri)
  {
    return new Maven2Client(httpClient, httpClientContext, repositoryBaseUri);
  }
}