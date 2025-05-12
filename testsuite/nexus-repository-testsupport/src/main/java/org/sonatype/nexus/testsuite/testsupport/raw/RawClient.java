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
package org.sonatype.nexus.testsuite.testsupport.raw;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.testsuite.testsupport.FormatClientSupport;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.http.HttpMethods.MKCOL;

/**
 * A simple test client for Raw repositories.
 * <p>
 * This implementation leverages Java 21 virtual threads for I/O-bound operations to improve
 * throughput and scalability during testing. Virtual threads provide lightweight concurrency
 * for operations that spend most of their time waiting for I/O, such as HTTP requests.
 * <p>
 * The client inherits virtual thread support from {@link FormatClientSupport} which handles
 * the execution of HTTP requests on virtual threads.
 *
 * @since 3.60 Updated for Java 21 with virtual threads support
 */
public class RawClient
    extends FormatClientSupport
{
  /**
   * Creates a new RawClient instance.
   *
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context
   * @param repositoryBaseUri the base URI of the repository
   */
  public RawClient(final CloseableHttpClient httpClient,
                   final HttpClientContext httpClientContext,
                   final URI repositoryBaseUri)
  {
    super(httpClient, httpClientContext, repositoryBaseUri);
  }

  /**
   * Uploads a file to the specified path in the repository.
   * <p>
   * This method leverages virtual threads for I/O operations.
   *
   * @param path the path where the file should be uploaded
   * @param contentType the content type of the file
   * @param file the file to upload
   * @return the HTTP status code of the response
   * @throws Exception if an error occurs during the upload
   */
  public int put(final String path, final ContentType contentType, final File file) throws Exception {
    checkNotNull(path);
    checkNotNull(file);

    HttpPut put = new HttpPut(repositoryBaseUri.resolve(path));
    put.setEntity(EntityBuilder.create().setContentType(contentType).setFile(file).build());

    return status(execute(put));
  }

  /**
   * Uploads content to the specified path in the repository.
   * <p>
   * This method leverages virtual threads for I/O operations.
   *
   * @param path the path where the content should be uploaded
   * @param entity the HTTP entity containing the content to upload
   * @return the HTTP response
   * @throws IOException if an error occurs during the upload
   */
  public CloseableHttpResponse put(final String path, final HttpEntity entity) throws IOException {
    final URI uri = resolve(path);
    final HttpPut put = new HttpPut(uri);
    put.setEntity(entity);
    return execute(put);
  }

  /**
   * Gets the content at the specified path as a byte array.
   * <p>
   * This method leverages virtual threads for I/O operations.
   *
   * @param path the path to retrieve
   * @return the content as a byte array
   * @throws Exception if an error occurs during the retrieval
   */
  public byte[] getBytes(final String path) throws Exception {
    return bytes(get(path));
  }

  /**
   * Deletes the content at the specified path.
   * <p>
   * This method leverages virtual threads for I/O operations.
   *
   * @param path the path to delete
   * @return the HTTP response
   * @throws Exception if an error occurs during the deletion
   */
  public HttpResponse delete(final String path) throws Exception {
    return execute(new HttpDelete(resolve(path)));
  }

  /**
   * Creates a collection at the specified path using the MKCOL method.
   * <p>
   * This method leverages virtual threads for I/O operations.
   *
   * @param path the path where the collection should be created
   * @return the HTTP response
   * @throws Exception if an error occurs during the collection creation
   */
  public HttpResponse mkcol(final String path) throws Exception {
    HttpUriRequest mkcolRequest = RequestBuilder.create(MKCOL)
        .setUri(resolve(path)).build();
    return execute(mkcolRequest);
  }
}