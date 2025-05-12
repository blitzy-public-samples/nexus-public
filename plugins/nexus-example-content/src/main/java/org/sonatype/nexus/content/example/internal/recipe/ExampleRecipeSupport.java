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
package org.sonatype.nexus.content.example.internal.recipe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.sonatype.nexus.content.example.ExampleContentFacet;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.IndexHtmlForwardHandler;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base support class for Example repository recipes.
 * 
 * @since 21.0
 */
public abstract class ExampleRecipeSupport
    extends RecipeSupport
{
  protected Provider<ExampleSecurityFacet> securityFacet;

  protected Provider<ConfigurableViewFacet> viewFacet;

  protected Provider<ExampleContentFacet> contentFacet;

  protected ExceptionHandler exceptionHandler;

  protected TimingHandler timingHandler;

  protected IndexHtmlForwardHandler indexHtmlForwardHandler;

  protected SecurityHandler securityHandler;

  protected PartialFetchHandler partialFetchHandler;

  protected ExampleContentHandler contentHandler;

  protected ConditionalRequestHandler conditionalRequestHandler;

  protected ContentHeadersHandler contentHeadersHandler;

  protected LastDownloadedHandler lastDownloadedHandler;

  protected HandlerContributor handlerContributor;

  /**
   * Constructor for the recipe support class.
   *
   * @param type   the repository type
   * @param format the repository format
   */
  protected ExampleRecipeSupport(
      final Type type,
      final Format format)
  {
    super(type, format);
  }

  /**
   * Injects all required dependencies for this recipe.
   * Updated for Java 21 compatibility with Guice 7.0.0 and Sisu 0.10.0.
   */
  @Inject
  public final void setDependencies(
      final Provider<ExampleSecurityFacet> securityFacet,
      final Provider<ConfigurableViewFacet> viewFacet,
      final Provider<ExampleContentFacet> contentFacet,
      final ExceptionHandler exceptionHandler,
      final TimingHandler timingHandler,
      final IndexHtmlForwardHandler indexHtmlForwardHandler,
      final SecurityHandler securityHandler,
      final PartialFetchHandler partialFetchHandler,
      final ExampleContentHandler contentHandler,
      final ConditionalRequestHandler conditionalRequestHandler,
      final ContentHeadersHandler contentHeadersHandler,
      final LastDownloadedHandler lastDownloadedHandler,
      final HandlerContributor handlerContributor)
  {
    // Using pattern matching to validate non-null parameters
    this.securityFacet = switch (securityFacet) {
      case null -> throw new NullPointerException("securityFacet")
      case Provider<?> p -> p
    };
    this.viewFacet = switch (viewFacet) {
      case null -> throw new NullPointerException("viewFacet")
      case Provider<?> p -> p
    };
    this.contentFacet = switch (contentFacet) {
      case null -> throw new NullPointerException("contentFacet")
      case Provider<?> p -> p
    };
    
    // Using string templates for any potential error messages
    this.exceptionHandler = checkNotNull(exceptionHandler, STR."\{getClass().getSimpleName()}: exceptionHandler");
    this.timingHandler = checkNotNull(timingHandler);
    this.indexHtmlForwardHandler = checkNotNull(indexHtmlForwardHandler);
    this.securityHandler = checkNotNull(securityHandler);
    this.partialFetchHandler = checkNotNull(partialFetchHandler);
    this.contentHandler = checkNotNull(contentHandler);
    this.conditionalRequestHandler = checkNotNull(conditionalRequestHandler);
    this.contentHeadersHandler = checkNotNull(contentHeadersHandler);
    this.lastDownloadedHandler = checkNotNull(lastDownloadedHandler);
    this.handlerContributor = checkNotNull(handlerContributor);
  }
}