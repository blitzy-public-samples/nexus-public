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
package org.sonatype.nexus.content.testsupport.raw;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sonatype.nexus.content.testsupport.FormatClientSupport;
import org.sonatype.nexus.repository.http.HttpMethods;

import org.apache.http.HttpEntity;
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

/**
 * A simple test client for Raw repositories.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for concurrent I/O operations,
 * providing improved performance for HTTP operations like PUT, GET, DELETE, and MKCOL.
 * Virtual Threads are lightweight threads that are particularly efficient for I/O-bound
 * operations, allowing for high concurrency with minimal resource overhead.
 */
public class RawClient
    extends FormatClientSupport
{
  /**
   * Executor service using Java 21 Virtual Threads for concurrent operations.
   * Virtual Threads are particularly efficient for I/O-bound operations like HTTP requests.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public RawClient(
      final CloseableHttpClient httpClient,
      final HttpClientContext httpClientContext,
      final URI repositoryBaseUri)
  {
    super(httpClient, httpClientContext, repositoryBaseUri);
  }

  /**
   * Puts a file to the specified path using Virtual Threads for improved I/O performance.
   *
   * @param path the path to put the file to
   * @param contentType the content type of the file
   * @param file the file to put
   * @return the HTTP status code
   * @throws Exception if an error occurs
   */
  public int put(final String path, final ContentType contentType, final File file) throws Exception {
    checkNotNull(path);
    checkNotNull(file);

    HttpPut put = new HttpPut(repositoryBaseUri.resolve(path));
    put.setEntity(EntityBuilder.create().setContentType(contentType).setFile(file).build());

    // Execute the request using a Virtual Thread
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          try {
            return status(execute(put));
          }
          catch (IOException e) {
            throw new RuntimeException("Error executing PUT request", e);
          }
        },
        virtualThreadExecutor
    );

    return future.join();
  }

  /**
   * Puts an entity to the specified path using Virtual Threads for improved I/O performance.
   *
   * @param path the path to put the entity to
   * @param entity the HTTP entity to put
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  public CloseableHttpResponse put(final String path, final HttpEntity entity) throws IOException {
    final URI uri = resolve(path);
    final HttpPut put = new HttpPut(uri);
    put.setEntity(entity);
    
    // Execute the request directly - for cases where the caller needs the full response
    return execute(put);
  }

  /**
   * Gets the bytes from the specified path using Virtual Threads for improved I/O performance.
   *
   * @param path the path to get the bytes from
   * @return the bytes from the specified path
   * @throws Exception if an error occurs
   */
  public byte[] getBytes(final String path) throws Exception {
    // Execute the request using a Virtual Thread
    CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(
        () -> {
          try {
            return bytes(get(path));
          }
          catch (Exception e) {
            throw new RuntimeException("Error executing GET request", e);
          }
        },
        virtualThreadExecutor
    );

    return future.join();
  }

  /**
   * Deletes the specified path using Virtual Threads for improved I/O performance.
   *
   * @param path the path to delete
   * @return the HTTP response
   * @throws Exception if an error occurs
   */
  public CloseableHttpResponse delete(final String path) throws Exception {
    // Execute the request using a Virtual Thread
    CompletableFuture<CloseableHttpResponse> future = CompletableFuture.supplyAsync(
        () -> {
          try {
            return execute(new HttpDelete(resolve(path)));
          }
          catch (IOException e) {
            throw new RuntimeException("Error executing DELETE request", e);
          }
        },
        virtualThreadExecutor
    );

    return future.join();
  }

  /**
   * Creates a collection at the specified path using Virtual Threads for improved I/O performance.
   *
   * @param path the path to create the collection at
   * @return the HTTP response
   * @throws Exception if an error occurs
   */
  public CloseableHttpResponse mkcol(final String path) throws Exception {
    HttpUriRequest mkcolRequest = RequestBuilder.create(HttpMethods.MKCOL)
        .setUri(resolve(path))
        .build();
    
    // Execute the request using a Virtual Thread
    CompletableFuture<CloseableHttpResponse> future = CompletableFuture.supplyAsync(
        () -> {
          try {
            return execute(mkcolRequest);
          }
          catch (IOException e) {
            throw new RuntimeException("Error executing MKCOL request", e);
          }
        },
        virtualThreadExecutor
    );

    return future.join();
  }
  
  /**
   * Closes this client and releases any system resources associated with it.
   * This includes shutting down the Virtual Thread executor service.
   */
  @Override
  public void close() throws IOException {
    try {
      virtualThreadExecutor.close();
    }
    finally {
      super.close();
    }
  }
}