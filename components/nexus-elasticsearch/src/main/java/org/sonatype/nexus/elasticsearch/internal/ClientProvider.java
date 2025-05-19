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
package org.sonatype.nexus.elasticsearch.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.elasticsearch.client.Client;
import org.elasticsearch.node.Node;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * ElasticSearch {@link Client} provider with Java 21 Virtual Threads support.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ClientProvider
    implements Provider<Client>
{
  private final Provider<Node> node;
  private final ConcurrentHashMap<String, Client> clientCache = new ConcurrentHashMap<>();
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public ClientProvider(final Provider<Node> node) {
    this.node = checkNotNull(node);
  }

  @Override
  public Client get() {
    return getOrCreateClient("default");
  }

  /**
   * Gets a client by ID, creating it if it doesn't exist.
   *
   * @param clientId the client identifier
   * @return the client instance
   */
  public Client getOrCreateClient(final String clientId) {
    return clientCache.computeIfAbsent(clientId, id -> createClient());
  }

  /**
   * Creates a new client instance.
   *
   * @return the new client instance
   */
  private Client createClient() {
    return node.get().client();
  }

  /**
   * Executes an operation using the client with Virtual Thread support.
   *
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return the result of the operation
   */
  public <T> T executeWithClient(ClientOperation<T> operation) {
    try {
      return virtualThreadExecutor.submit(() -> {
        Client client = get();
        try {
          return operation.execute(client);
        } catch (Exception e) {
          throw new ElasticsearchClientException("Error executing client operation", e);
        }
      }).get();
    } catch (Exception e) {
      if (e.getCause() instanceof ElasticsearchClientException) {
        throw (ElasticsearchClientException) e.getCause();
      }
      throw new ElasticsearchClientException("Error executing client operation", e);
    }
  }

  /**
   * Executes an operation asynchronously using the client with Virtual Thread support.
   *
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return a supplier that will provide the result when needed
   */
  public <T> Supplier<T> executeWithClientAsync(ClientOperation<T> operation) {
    var future = virtualThreadExecutor.submit(() -> {
      Client client = get();
      try {
        return operation.execute(client);
      } catch (Exception e) {
        throw new ElasticsearchClientException("Error executing client operation", e);
      }
    });

    return () -> {
      try {
        return future.get();
      } catch (Exception e) {
        if (e.getCause() instanceof ElasticsearchClientException) {
          throw (ElasticsearchClientException) e.getCause();
        }
        throw new ElasticsearchClientException("Error executing client operation", e);
      }
    };
  }

  /**
   * Functional interface for client operations.
   *
   * @param <T> the return type of the operation
   */
  @FunctionalInterface
  public interface ClientOperation<T> {
    /**
     * Executes an operation with the provided client.
     *
     * @param client the client to use
     * @return the result of the operation
     * @throws Exception if an error occurs during execution
     */
    T execute(Client client) throws Exception;
  }

  /**
   * Exception thrown when an error occurs during client operations.
   */
  public static class ElasticsearchClientException extends RuntimeException {
    public ElasticsearchClientException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}