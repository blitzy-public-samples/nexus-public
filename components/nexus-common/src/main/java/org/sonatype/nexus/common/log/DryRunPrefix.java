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
package org.sonatype.nexus.common.log;

import java.lang.StringTemplate;
import java.lang.StringTemplate.Processor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Prefix for use when logging actions while in dry-run mode (i.e. log actions, but make no changes).
 * <p>
 * This class provides support for both traditional string concatenation and Java 21 String Templates.
 * <p>
 * Example usage with traditional string concatenation:
 * <pre>
 * log.info("{}Performing action", dryRunPrefix.get());
 * </pre>
 * <p>
 * Example usage with Java 21 String Templates:
 * <pre>
 * // Using the processor (most efficient)
 * log.info(STR."\{dryRunPrefix.asProcessor()}Performing action");
 * 
 * // Using direct string reference (simpler)
 * log.info(STR."\{dryRunPrefix.forTemplate()}Performing action");
 * </pre>
 *
 * @since 3.6
 */
@Named
@Singleton
public class DryRunPrefix
{
  private final String prefix;

  @Inject
  public DryRunPrefix(@Named("${nexus.log.dryrun.prefix:-::DRY RUN:: }") final String prefix) {
    this.prefix = checkNotNull(prefix);
  }

  /**
   * Returns the dry-run prefix string.
   * <p>
   * For more efficient message formatting when using with log messages and Java 21 String Templates,
   * consider using {@link #asProcessor()} instead.
   *
   * @return the dry-run prefix string
   */
  public String get() {
    return prefix;
  }

  /**
   * Returns a StringTemplate processor that prepends the dry-run prefix to the template.
   * <p>
   * This method is designed to be used directly in String Template expressions to avoid
   * unnecessary string concatenation and improve memory efficiency.
   * <p>
   * Example usage:
   * <pre>
   * log.info(STR."\{dryRunPrefix.asProcessor()}Performing action on \{target}");
   * </pre>
   *
   * @return a StringTemplate processor that prepends the dry-run prefix
   * @since 3.60
   */
  public Processor<String> asProcessor() {
    return template -> {
      StringBuilder result = new StringBuilder(prefix);
      int fragmentCount = template.fragments().size();
      
      for (int i = 0; i < fragmentCount; i++) {
        result.append(template.fragments().get(i));
        if (i < template.values().size()) {
          result.append(template.values().get(i));
        }
      }
      
      return result.toString();
    };
  }
  
  /**
   * Convenience method that returns this prefix as a string for use in String Templates.
   * <p>
   * This method is functionally equivalent to {@link #get()} but makes the intent clearer
   * when used in String Template expressions.
   * <p>
   * Example usage:
   * <pre>
   * log.info(STR."\{dryRunPrefix.forTemplate()}Performing action");
   * </pre>
   *
   * @return the dry-run prefix string
   * @since 3.60
   */
  public String forTemplate() {
    return prefix;
  }
}