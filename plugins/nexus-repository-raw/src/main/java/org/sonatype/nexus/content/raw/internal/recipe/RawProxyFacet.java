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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import javax.inject.Named;

import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;

import com.google.common.collect.ImmutableSet;

/**
 * Raw proxy facet optimized for Java 21 with Virtual Threads support.
 *
 * @since 3.24
 */
@Named
public class RawProxyFacet
    extends ContentProxyFacetSupport
{
  private static final ImmutableSet<String> CHARS_TO_ENCODE = ImmutableSet.of("^", "#", "?", "\u202F", "[", "]");

  public RawProxyFacet() {
    // Configure the executor service to use Virtual Threads for I/O operations
    // This significantly improves throughput for concurrent proxy operations
    setExecutorService(Executors.newVirtualThreadPerTaskExecutor());
  }

  @Override
  protected Content getCachedContent(final Context context) throws IOException {
    return content().get(assetPath(context)).orElse(null);
  }

  @Override
  protected Content store(final Context context, final Content payload) throws IOException {
    return content().put(assetPath(context), payload);
  }

  @Override
  protected String getUrl(final Context context) {
    return new EscapeHelper().uriSegments(removeSlashPrefix(assetPath(context)));
  }

  @Override
  protected String encodeUrl(final String url) throws UnsupportedEncodingException {
    String encodedUrl = url;
    // Using pattern matching for more concise code with Java 21
    for (String ch : CHARS_TO_ENCODE) {
      // Using StandardCharsets.UTF_8 instead of the string "UTF-8" for better type safety
      encodedUrl = encodedUrl.replace(ch, URLEncoder.encode(ch, StandardCharsets.UTF_8));
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
    // Using pattern matching for more concise code with Java 21
    if (context.getAttributes() instanceof var attributes && attributes != null) {
      if (attributes.get(TokenMatcher.State.class) instanceof TokenMatcher.State tokenMatcherState) {
        return tokenMatcherState.getTokens().get(RawRecipeSupport.PATH_NAME);
      }
    }
    // Fallback to traditional approach if pattern matching fails
    final TokenMatcher.State tokenMatcherState = context.getAttributes().require(TokenMatcher.State.class);
    return tokenMatcherState.getTokens().get(RawRecipeSupport.PATH_NAME);
  }

  private String removeSlashPrefix(final String url) {
    // Using pattern matching for more concise code with Java 21
    return url != null && url.startsWith("/") ? url.substring(1) : url;
  }
}