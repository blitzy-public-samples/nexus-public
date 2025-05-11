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
package org.sonatype.nexus.content.maven.internal;

import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.search.elasticsearch.DefaultSearchDocumentProducer;
import org.sonatype.nexus.repository.content.search.elasticsearch.SearchDocumentExtension;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.search.MavenVersionNormalizer;

/**
 * Maven implementation of {@link DefaultSearchDocumentProducer}
 *
 * <p>Updated for Java 21 compatibility with improved null checking and dependency injection.</p>
 *
 * @since 3.26
 */
@Singleton
@Named(Maven2Format.NAME)
public class MavenSearchDocumentProducer
    extends DefaultSearchDocumentProducer
{
  private final MavenVersionNormalizer versionNormalizer;

  private final MavenPreReleaseEvaluator preReleaseEvaluator;

  /**
   * Constructor with dependency injection for required components.
   *
   * @param documentExtensions extensions for search document production
   * @param versionNormalizer normalizes Maven version strings
   * @param preReleaseEvaluator evaluates if a component is a pre-release
   */
  @Inject
  public MavenSearchDocumentProducer(
      final Set<SearchDocumentExtension> documentExtensions,
      final MavenVersionNormalizer versionNormalizer,
      final MavenPreReleaseEvaluator preReleaseEvaluator)
  {
    super(documentExtensions);
    this.versionNormalizer = Objects.requireNonNull(versionNormalizer, "Version normalizer cannot be null");
    this.preReleaseEvaluator = Objects.requireNonNull(preReleaseEvaluator, "Pre-release evaluator cannot be null");
  }

  @Override
  protected boolean isPrerelease(final FluentComponent component) {
    return preReleaseEvaluator.isPreRelease(component);
  }

  @Override
  protected String getNormalizedVersion(final FluentComponent component) {
    return versionNormalizer.getNormalizedVersion(component.version());
  }
}