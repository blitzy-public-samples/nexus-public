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
package org.sonatype.nexus.content.raw.internal.recipe;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.inject.Named;

import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher.State;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;

import com.google.common.collect.ImmutableSet;

import static com.google.common.base.Preconditions.checkState;

/**
 * Raw proxy facet.
 *
 * @since 3.24
 */
@Named
public class RawProxyFacet
    extends ContentProxyFacetSupport
{
  private static final ImmutableSet<String> CHARS_TO_ENCODE = ImmutableSet.of("^", "#", "?", "\u202F", "[", "]");
  
  // Virtual thread executor for I/O-bound operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  protected Content getCachedContent(final Context context) throws IOException {
    // Use virtual threads for I/O-bound operations
    try {
      Future<Content> contentFuture = virtualThreadExecutor.submit(() -> content().get(assetPath(context)).orElse(null));
      return contentFuture.get();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."Interrupted while getting cached content: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      throw new IOException(STR."Error getting cached content: \{e.getMessage()}", e);
    }
  }

  @Override
  protected Content store(final Context context, final Content payload) throws IOException {
    // Use virtual threads for I/O-bound operations
    try {
      Future<Content> contentFuture = virtualThreadExecutor.submit(() -> content().put(assetPath(context), payload));
      return contentFuture.get();
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."Interrupted while storing content: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      throw new IOException(STR."Error storing content: \{e.getMessage()}", e);
    }
  }

  @Override
  protected String getUrl(final Context context) {
    return new EscapeHelper().uriSegments(removeSlashPrefix(assetPath(context)));
  }

  @Override
  protected String encodeUrl(final String url) throws UnsupportedEncodingException {
    String encodedUrl = url;
    for (String ch : CHARS_TO_ENCODE) {
      encodedUrl = encodedUrl.replace(ch, URLEncoder.encode(ch, "UTF-8"));
    }
    return encodedUrl;
  }
  
  @Override
  protected Payload getPayload(final Repository proxy, final URI uri) throws IOException {
    // Override to use virtual threads for remote HTTP operations
    try {
      return virtualThreadExecutor.submit(() -> fetchPayloadWithVirtualThread(proxy, uri)).get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."Interrupted while fetching payload from \{uri}: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      throw new IOException(STR."Error fetching payload from \{uri}: \{e.getMessage()}", e);
    }
  }
  
  private Payload fetchPayloadWithVirtualThread(final Repository proxy, final URI uri) throws IOException {
    final HttpClient client = proxy.facet(HttpClientFacet.class).getHttpClient();

    HttpGet request = new HttpGet(uri);
    log.debug(STR."Fetching: \{request}");

    HttpResponse response = client.execute(request);
    StatusLine status = response.getStatusLine();
    log.debug(STR."Response: \{response}, status: \{status}");

    if (status.getStatusCode() == HttpStatus.SC_OK) {
      HttpEntity entity = response.getEntity();
      checkState(entity != null, "No http entity received from remote registry");

      return new org.sonatype.nexus.repository.view.payloads.HttpEntityPayload(response, entity);
    }
    log.warn(STR."Status code \{status.getStatusCode()} contacting \{uri}");
    HttpClientUtils.closeQuietly(response);
    return null;
  }

  private RawContentFacet content() {
    return getRepository().facet(RawContentFacet.class);
  }

  /**
   * Determines what 'asset' this request relates to.
   */
  private String assetPath(final Context context) {
    // Using pattern matching with instanceof for TokenMatcher.State
    var tokenMatcherState = context.getAttributes().require(TokenMatcher.State.class);
    if (tokenMatcherState instanceof State state) {
      return state.getTokens().get(RawRecipeSupport.PATH_NAME);
    }
    return tokenMatcherState.getTokens().get(RawRecipeSupport.PATH_NAME);
  }

  private String removeSlashPrefix(final String url) {
    return url != null && url.startsWith("/") ? url.substring(1) : url;
  }
}