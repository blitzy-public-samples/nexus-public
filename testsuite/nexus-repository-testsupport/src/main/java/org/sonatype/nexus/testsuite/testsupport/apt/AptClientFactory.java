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
package org.sonatype.nexus.testsuite.testsupport.apt;

import java.net.URI;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;

import org.sonatype.nexus.testsuite.testsupport.NexusClientFactory;

/**
 * Factory for creating {@link AptClient} instances to interact with APT repositories.
 * <p>
 * This factory is compatible with Java 21 and leverages virtual threads for improved
 * I/O performance when creating and using clients. The implementation ensures that
 * all HTTP operations can benefit from virtual thread execution for better scalability.
 *
 * @since 3.13
 */
public class AptClientFactory
    extends NexusClientFactory<AptClient>
{
  /**
   * Creates an {@link AptClient} with the provided HTTP client, context, and repository URI.
   * <p>
   * This implementation is compatible with Java 21 virtual threads and will automatically
   * benefit from the virtual thread support in the parent class.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context with authentication settings
   * @param repositoryBaseUri the base URI of the repository
   * @return a new {@link AptClient} instance
   */
  @Override
  public AptClient createClient(final CloseableHttpClient httpClient,
                                final HttpClientContext httpClientContext,
                                final URI repositoryBaseUri)
  {
    return new AptClient(httpClient, httpClientContext, repositoryBaseUri);
  }
}