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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.testsuite.testsupport.FormatClientSupport;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.IF_MODIFIED_SINCE;

/**
 * Apt client for interacting with APT repositories.
 * 
 * <p>This implementation leverages Java 21 Virtual Threads for improved concurrency and scalability
 * when performing I/O-bound operations like HTTP requests. Virtual threads provide lightweight
 * threading with minimal overhead, allowing for high throughput without the complexity of
 * traditional asynchronous programming models.</p>
 */
public class AptClient
    extends FormatClientSupport
{
  /**
   * Creates a new APT client.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context
   * @param repositoryBaseUri the base URI of the repository
   */
  public AptClient(final CloseableHttpClient httpClient,
                   final HttpClientContext httpClientContext,
                   final URI repositoryBaseUri)
  {
    super(httpClient, httpClientContext, repositoryBaseUri);
  }

  /**
   * Fetches content from the specified path in the repository.
   *
   * @param path the path to fetch
   * @return the HTTP response
   */
  public HttpResponse fetch(final String path) throws IOException {
    return execute(new HttpGet(resolve(path)));
  }

  /**
   * Fetches content from the specified path and consumes the response entity.
   *
   * @param path the path to fetch
   * @return the HTTP response with consumed entity
   */
  public HttpResponse fetchAndClose(final String path) throws IOException {
    return consume(fetch(path));
  }

  /**
   * Performs a conditional fetch using the If-Modified-Since header.
   *
   * @param path the path to fetch
   * @param modified the modification timestamp for conditional request
   * @return the HTTP response
   */
  public HttpResponse conditionalFetch(final String path, final String modified) throws IOException {
    return conditionalGet(resolve(path), modified);
  }

  /**
   * Gets the HTTP status code for the specified path.
   *
   * @param path the path to check
   * @return the HTTP status code
   */
  public int status(final String path) throws IOException {
    return status(consume(fetch(path)));
  }

  /**
   * Searches the repository using the specified criteria.
   *
   * @param criteria the search criteria
   * @return the HTTP response
   */
  public HttpResponse search(final String criteria) throws IOException {
    return execute(new HttpGet(resolve(criteria)));
  }

  /**
   * Consumes the response entity and returns the response.
   *
   * @param response the HTTP response to consume
   * @return the same response with consumed entity
   */
  static HttpResponse consume(final HttpResponse response) throws IOException {
    EntityUtils.consume(response.getEntity());
    return response;
  }

  /**
   * Posts a Debian package file to the repository.
   *
   * @param file the Debian package file to post
   * @return the HTTP status code of the response
   */
  public int post(final File file) throws IOException {
    checkNotNull(file);

    int statusCode;
    HttpPost post = new HttpPost(repositoryBaseUri.resolve(""));
    post.setEntity(new FileEntity(file, ContentType.create("application/x-debian-package")));
    try (CloseableHttpResponse response = execute(post)) {
      statusCode = response.getStatusLine().getStatusCode();
    }

    return statusCode;
  }

  /**
   * Creates a snapshot with the specified ID.
   *
   * @param snapshotId the ID of the snapshot to create
   * @return the HTTP response
   */
  public HttpResponse snapshotAll(String snapshotId) throws Exception {
    HttpUriRequest mkcolRequest = RequestBuilder.create("MKCOL")
        .setUri(resolve(STR."snapshots/\{snapshotId}")).build();
    return execute(mkcolRequest);
  }

  /**
   * Deletes a snapshot with the specified ID.
   *
   * @param snapshotId the ID of the snapshot to delete
   * @return the HTTP response
   */
  public HttpResponse deleteSnapshot(String snapshotId) throws Exception {
    HttpUriRequest deleteRequest = RequestBuilder.create("DELETE")
        .setUri(resolve(STR."snapshots/\{snapshotId}")).build();
    return execute(deleteRequest);
  }

  /**
   * Performs a conditional GET request using the If-Modified-Since header.
   *
   * @param uri the URI to fetch
   * @param modified the modification timestamp for conditional request
   * @return the HTTP response with consumed entity
   */
  public HttpResponse conditionalGet(final URI uri, final String modified) throws IOException {
    HttpGet get = new HttpGet(uri);
    get.setHeader(IF_MODIFIED_SINCE, modified);
    return consume(execute(get));
  }
}