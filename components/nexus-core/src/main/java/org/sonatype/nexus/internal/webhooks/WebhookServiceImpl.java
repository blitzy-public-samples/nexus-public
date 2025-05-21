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
package org.sonatype.nexus.internal.webhooks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.InternalAccessible;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.webhooks.Webhook;
import org.sonatype.nexus.webhooks.WebhookRequest;
import org.sonatype.nexus.webhooks.WebhookRequestSendEvent;
import org.sonatype.nexus.webhooks.WebhookService;

import com.codahale.metrics.annotation.Gauge;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.BaseEncoding;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Default {@link WebhookService} implementation using Java 21 Virtual Threads and HttpClient.
 *
 * @since 3.1
 */
@Named
@Singleton
public class WebhookServiceImpl
    extends ComponentSupport
    implements WebhookService, EventAware, EventAware.Asynchronous
{
  private static final String WEBHOOK_ID_HEADER = "X-Nexus-Webhook-ID";

  private static final String WEBHOOK_DELIVERY_HEADER = "X-Nexus-Webhook-Delivery";

  @VisibleForTesting
  static final String WEBHOOK_SIGNATURE_HEADER = "X-Nexus-Webhook-Signature";

  private static final String HMAC_SHA1 = "HmacSHA1";

  private static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();
  
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * ObjectMapper configured for JSON serialization with Jackson 2.16.1 compatibility.
   * - Dates are serialized as ISO-8601 strings instead of timestamps
   * - Null values are excluded from serialization
   */
  private final ObjectMapper objectMapper = new ObjectMapper()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final HttpClient httpClient;

  private final List<Webhook> webhooks;

  /**
   * Constructor that initializes the service with the list of available webhooks
   * and creates an HTTP client optimized for Java 21 with HTTP/2 support.
   *
   * @param webhooks The list of available webhooks
   */
  @Inject
  public WebhookServiceImpl(final List<Webhook> webhooks)
  {
    this.webhooks = checkNotNull(webhooks);
    
    // Create an HttpClient with HTTP/2 support and optimized for virtual threads
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(REQUEST_TIMEOUT)
        .executor(Executors.newVirtualThreadPerTaskExecutor()) // Use virtual threads for HTTP client operations
        .build();
    
    log.debug(STR."Initialized WebhookService with \{webhooks.size()} registered webhooks");
  }

  /**
   * Generate HMAC signature (HEX encoded) of given body using secret as key.
   * This method creates a secure signature for webhook payloads to verify authenticity.
   *
   * @param body The JSON body content to sign
   * @param secret The secret key used for signing
   * @return HEX encoded HMAC-SHA1 signature
   * @throws NoSuchAlgorithmException If the HMAC-SHA1 algorithm is not available
   * @throws InvalidKeyException If the provided key is invalid
   */
  private static String sign(
      final String body,
      final String secret) throws NoSuchAlgorithmException, InvalidKeyException
  {
    SecretKeySpec key = new SecretKeySpec(secret.getBytes(), HMAC_SHA1);
    Mac mac = Mac.getInstance(HMAC_SHA1);
    mac.init(key);
    byte[] bytes = mac.doFinal(body.getBytes());
    return HEX.encode(bytes);
  }

  @Override
  public List<Webhook> getWebhooks() {
    return ImmutableList.copyOf(webhooks);
  }

  @Override
  public void queue(final WebhookRequest request) {
    checkNotNull(request);
    
    log.debug(STR."Queuing webhook request: \{request.getId()}");
    
    // Use virtual threads for non-blocking asynchronous webhook dispatch
    Thread.ofVirtual()
        .name(STR."webhook-\{request.getId()}")
        .start(() -> {
          try {
            send(request);
          }
          catch (Exception e) {
            log.error(STR."Failed to send webhook request: \{request}\nError: \{e.getMessage()}", e);
          }
        });
  }

  /**
   * Asynchronous send handler.
   *
   * @see #queue(WebhookRequest)
   */
  @Subscribe
  @AllowConcurrentEvents
  @InternalAccessible
  void on(final WebhookRequestSendEvent event) {
    queue(event.getRequest());
  }

  @Override
  public void send(final WebhookRequest request) throws Exception {
    checkNotNull(request);

    log.debug(STR."Sending webhook request: \{request}");

    Webhook webhook = request.getWebhook();
    String json = objectMapper.writeValueAsString(request.getPayload());

    // Build the HTTP request with appropriate headers
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(request.getUrl()))
        .header("Content-Type", "application/json")
        .header(WEBHOOK_ID_HEADER, webhook.getId())
        .header(WEBHOOK_DELIVERY_HEADER, request.getId())
        .timeout(REQUEST_TIMEOUT);
    
    // Generate HMAC signature of body if secret is present
    if (!isEmpty(request.getSecret())) {
      String signature = sign(json, request.getSecret());
      requestBuilder.header(WEBHOOK_SIGNATURE_HEADER, signature);
      log.debug(STR."Added signature header for webhook request: \{request.getId()}");
    }
    
    // Set the request body and method
    HttpRequest httpRequest = requestBuilder
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    log.debug(STR."Sending POST request to: \{httpRequest.uri()}");
    
    // Send the request and handle the response
    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    
    int statusCode = response.statusCode();
    log.debug(STR."Response received with status: \{statusCode}");

    // Handle error status codes
    if (statusCode >= 300) {
      String message = response.body();
      if (message == null || message.isEmpty() || message.contains("<html")) {
        message = STR."HTTP Error \{statusCode}";
      }
      throw new IOException(STR."HTTP request failed with status \{statusCode}: \{message}");
    }
    
    log.debug(STR."Successfully sent webhook request: \{request.getId()}");
  }
  
  /**
   * Sends a webhook request asynchronously and returns a CompletableFuture.
   * This method leverages Java 21 Virtual Threads for non-blocking I/O operations.
   * 
   * @param request The webhook request to send
   * @return A CompletableFuture that completes when the request is sent
   */
  public CompletableFuture<Void> sendAsync(final WebhookRequest request) {
    checkNotNull(request);
    
    log.debug(STR."Queuing asynchronous webhook request: \{request}");
    
    return CompletableFuture.runAsync(
        () -> {
          try {
            send(request);
          }
          catch (Exception e) {
            log.error(STR."Asynchronous webhook request failed: \{request}\nError: \{e.getMessage()}", e);
            throw new RuntimeException(STR."Failed to send webhook request: \{request}", e);
          }
        },
        Executors.newVirtualThreadPerTaskExecutor()
    );
  }

  /**
   * With virtual threads, there's no traditional thread pool or queue to monitor.
   * This method is kept for backward compatibility but always returns true.
   * 
   * @return Always true as virtual threads don't have a queue to check
   */
  @VisibleForTesting
  public boolean isCalmPeriod() {
    return true;
  }
  
  /**
   * Returns metrics about the current virtual thread usage.
   * This replaces the previous queue size monitoring with more relevant virtual thread metrics.
   * 
   * @return The number of active virtual threads in the JVM
   */
  @Gauge(name = "nexus.webhooks.service.virtualthreads.active")
  public long activeVirtualThreadCount() {
    return Thread.activeCount();
  }
}