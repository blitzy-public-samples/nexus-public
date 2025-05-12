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

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.concurrent.Executors;

import org.sonatype.nexus.testsuite.testsupport.FormatClientSupport;

import com.google.common.net.HttpHeaders;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Maven2 Client.
 * <p>
 * This client provides HTTP operations for Maven 2 repositories with support for
 * conditional requests using If-Modified-Since, If-None-Match, If-Unmodified-Since,
 * and If-Match headers.
 * <p>
 * Optimized for Java 21 with Virtual Threads support for improved concurrency and performance.
 */
public class Maven2Client
    extends FormatClientSupport
{
  /**
   * Constructs a new Maven2Client.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context
   * @param repositoryBaseUri the base URI of the repository
   */
  public Maven2Client(final CloseableHttpClient httpClient,
                      final HttpClientContext httpClientContext,
                      final URI repositoryBaseUri)
  {
    super(httpClient, httpClientContext, repositoryBaseUri);
  }

  /**
   * Performs a conditional GET request using the If-Modified-Since header.
   *
   * @param path the path to request
   * @param date the date to use for the If-Modified-Since header
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public HttpResponse getIfNewer(String path, Date date) throws IOException {
    final URI uri = resolve(path);
    final HttpGet get = new HttpGet(uri);
    get.addHeader(HttpHeaders.IF_MODIFIED_SINCE, DateUtils.formatDate(date));
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(get);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }

  /**
   * Performs a conditional GET request using the If-None-Match header.
   *
   * @param path the path to request
   * @param etag the ETag to use for the If-None-Match header
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public HttpResponse getIfNoneMatch(String path, String etag) throws IOException {
    final URI uri = resolve(path);
    final HttpGet get = new HttpGet(uri);
    get.addHeader(HttpHeaders.IF_NONE_MATCH, etag);
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(get);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }

  /**
   * Performs a PUT request to upload content to the repository.
   *
   * @param path the path to upload to
   * @param entity the HTTP entity containing the content to upload
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public CloseableHttpResponse put(String path, HttpEntity entity) throws IOException {
    final URI uri = resolve(path);
    final HttpPut put = new HttpPut(uri);
    put.setEntity(entity);
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(put);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }

  /**
   * Performs a conditional PUT request using the If-Unmodified-Since header.
   *
   * @param path the path to upload to
   * @param date the date to use for the If-Unmodified-Since header
   * @param entity the HTTP entity containing the content to upload
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public HttpResponse putIfUmmodified(String path, Date date, HttpEntity entity) throws IOException {
    final URI uri = resolve(path);
    final HttpPut put = new HttpPut(uri);
    put.addHeader(HttpHeaders.IF_UNMODIFIED_SINCE, DateUtils.formatDate(date));
    put.setEntity(entity);
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(put);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }

  /**
   * Performs a conditional PUT request using the If-Match header.
   *
   * @param path the path to upload to
   * @param etag the ETag to use for the If-Match header
   * @param entity the HTTP entity containing the content to upload
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public HttpResponse putIfMatches(String path, String etag, HttpEntity entity) throws IOException {
    final URI uri = resolve(path);
    final HttpPut put = new HttpPut(uri);
    put.addHeader(HttpHeaders.IF_MATCH, etag);
    put.setEntity(entity);
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(put);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }

  /**
   * Performs a DELETE request to remove content from the repository.
   *
   * @param path the path to delete
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public HttpResponse delete(String path) throws IOException {
    final URI uri = resolve(path);
    final HttpDelete delete = new HttpDelete(uri);
    
    // Use Virtual Threads for I/O-bound operations
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        return execute(delete);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }).join();
  }
}