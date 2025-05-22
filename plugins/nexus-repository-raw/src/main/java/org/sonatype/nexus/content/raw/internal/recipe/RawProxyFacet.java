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
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import javax.inject.Named;

import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;

import com.google.common.collect.ImmutableSet;

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
  
  /**
   * Virtual thread executor for handling I/O operations.
   * Using virtual threads improves concurrency for I/O-bound operations without the overhead of platform threads.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  protected Content getCachedContent(final Context context) throws IOException {
    // Using CompletableFuture with virtual threads for non-blocking I/O operations
    try {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return content().get(assetPath(context)).orElse(null);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor).join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  @Override
  protected Content store(final Context context, final Content payload) throws IOException {
    // Using CompletableFuture with virtual threads for non-blocking I/O operations
    try {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return content().put(assetPath(context), payload);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor).join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
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

  private RawContentFacet content() {
    return getRepository().facet(RawContentFacet.class);
  }

  /**
   * Determines what 'asset' this request relates to.
   */
  private String assetPath(final Context context) {
    final TokenMatcher.State tokenMatcherState = context.getAttributes().require(TokenMatcher.State.class);
    return tokenMatcherState.getTokens().get(RawRecipeSupport.PATH_NAME);
  }

  private String removeSlashPrefix(final String url) {
    return url != null && url.startsWith("/") ? url.substring(1) : url;
  }
  
  @Override
  protected void doStop() throws Exception {
    virtualThreadExecutor.close();
    super.doStop();
  }
}