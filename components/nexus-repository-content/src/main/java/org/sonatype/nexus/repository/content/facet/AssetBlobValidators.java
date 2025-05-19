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
package org.sonatype.nexus.repository.content.facet;

import java.io.IOException;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.mime.ContentValidator;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Supplies {@link AssetBlobValidator}s to {@link ContentFacet}s.
 *
 * @since 3.24
 */
@Named
@Singleton
public class AssetBlobValidators
{
  private final Map<String, MimeRulesSource> mimeRulesSources;

  private final Map<String, ContentValidator> contentValidators;

  private final DefaultContentValidator defaultContentValidator;

  @Inject
  public AssetBlobValidators(final Map<String, MimeRulesSource> mimeRulesSources,
                             final Map<String, ContentValidator> contentValidators,
                             final DefaultContentValidator defaultContentValidator)
  {
    this.mimeRulesSources = checkNotNull(mimeRulesSources);
    this.contentValidators = checkNotNull(contentValidators);
    this.defaultContentValidator = checkNotNull(defaultContentValidator);
  }

  /**
   * Selects an appropriate validator for the given repository using pattern matching.
   *
   * @param repository the repository to select a validator for
   * @return an asset blob validator for the repository's format
   */
  public AssetBlobValidator selectValidator(final Repository repository) {
    String format = repository.getFormat().getValue();
    
    // Use pattern matching to select the appropriate validator components
    var validatorComponents = switch (format) {
      case String s when mimeRulesSources.containsKey(s) && contentValidators.containsKey(s) -> 
          new ValidatorComponents(mimeRulesSources.get(s), contentValidators.get(s));
      case String s when mimeRulesSources.containsKey(s) -> 
          new ValidatorComponents(mimeRulesSources.get(s), defaultContentValidator);
      case String s when contentValidators.containsKey(s) -> 
          new ValidatorComponents(MimeRulesSource.NOOP, contentValidators.get(s));
      default -> 
          new ValidatorComponents(MimeRulesSource.NOOP, defaultContentValidator);
    };
    
    return (strict, contentSupplier, assetPath, declaredContentType) -> {
      try {
        return validatorComponents.contentValidator().determineContentType(
            strict,
            contentSupplier,
            validatorComponents.mimeRulesSource(),
            assetPath,
            declaredContentType);
      }
      catch (IOException e) {
        // Enhanced exception handling with more context
        throw new InvalidContentException("Failed to determine content type for asset: " + assetPath, e);
      }
    };
  }
  
  /**
   * Record to hold validator components for cleaner pattern matching.
   */
  private record ValidatorComponents(MimeRulesSource mimeRulesSource, ContentValidator contentValidator) {}
}