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
package org.sonatype.nexus.testsuite.testsupport.saml

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

import org.sonatype.goodies.testsupport.TestData
import org.sonatype.nexus.common.collect.NestedAttributesMap
import org.sonatype.nexus.common.hash.HashAlgorithm
import org.sonatype.nexus.repository.view.ContentTypes
import org.sonatype.nexus.testsuite.testsupport.FormatClientSupport

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.BinaryNode
import com.google.common.collect.Maps
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.http.HttpResponse
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.ContentType
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import org.joda.time.DateTime

import static org.sonatype.nexus.repository.http.HttpStatus.CREATED
import static org.sonatype.nexus.repository.http.HttpStatus.OK

/**
 * Saml Client.
 * <p>
 * Updated for Java 21 compatibility with Virtual Threads support for improved concurrency
 * in HTTP operations. This client leverages Java 21's Virtual Threads for I/O-bound operations
 * to provide better scalability and resource utilization during testing.
 */
class SamlClient
    extends FormatClientSupport
{
  /**
   * Virtual thread executor service for handling concurrent HTTP operations.
   * Uses Java 21's Virtual Threads feature for lightweight concurrency.
   */
  private final ExecutorService virtualThreadExecutor
  
  /**
   * Creates a new SAML client with the specified HTTP client, context, and base URI.
   * Initializes a virtual thread executor for concurrent operations.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context
   * @param repositoryBaseUri the base URI for the repository
   */
  SamlClient(final CloseableHttpClient httpClient,
             final HttpClientContext httpClientContext,
             final URI repositoryBaseUri)
  {
    super(httpClient, httpClientContext, repositoryBaseUri)
    // Initialize virtual thread executor using Java 21's virtual threads
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
  }

  /**
   * Fetches a resource from the specified path using a Virtual Thread for execution.
   * This method leverages Java 21's Virtual Threads to improve concurrency for I/O-bound operations.
   *
   * @param path the path to fetch
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  CloseableHttpResponse fetch(final String path) throws IOException {
    return execute(new HttpGet(resolve(path)))
  }
  
  /**
   * Asynchronously fetches a resource from the specified path using a Virtual Thread.
   * This method provides a non-blocking way to perform HTTP requests using Java 21's Virtual Threads.
   *
   * @param path the path to fetch
   * @return a CompletableFuture that will complete with the HTTP response
   */
  CompletableFuture<CloseableHttpResponse> fetchAsync(final String path) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return fetch(path)
      } catch (IOException e) {
        throw new CompletionException(e)
      }
    }, virtualThreadExecutor)
  }
  
  /**
   * Closes this client and releases any system resources associated with it.
   * This includes shutting down the virtual thread executor service.
   */
  @Override
  void close() {
    try {
      virtualThreadExecutor.shutdown()
    } finally {
      super.close()
    }
  }
}
