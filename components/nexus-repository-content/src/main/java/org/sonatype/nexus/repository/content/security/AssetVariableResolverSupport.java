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
package org.sonatype.nexus.repository.content.security;

import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.security.VariableResolverAdapterSupport;
import org.sonatype.nexus.selector.ConstantVariableResolver;
import org.sonatype.nexus.selector.VariableSource;
import org.sonatype.nexus.selector.VariableSourceBuilder;

import static java.lang.StringTemplate.STR;

import java.util.Objects;

/**
 * Adapts persisted assets to variable resolvers.
 * 
 * Uses Java 21 pattern matching and string templates for improved code clarity and diagnostics.
 *
 * @since 3.29
 */
public abstract class AssetVariableResolverSupport
    extends VariableResolverAdapterSupport
    implements AssetVariableResolver
{
  /**
   * Creates a diagnostic message for asset validation errors.
   *
   * @param asset The asset being validated
   * @param issue The specific issue with the asset
   * @return A formatted error message
   */
  protected String createDiagnosticMessage(final FluentAsset asset, final String issue) {
    return STR."Asset validation error: \{issue}\nAsset: \{asset}\nPath: \{asset.path()}\nFormat: \{asset.repository().getFormat().getValue()}";
  }
  /**
   * Creates a variable source from a fluent asset.
   * 
   * @param asset The fluent asset to create a variable source from
   * @return A variable source containing the asset's path, format, and any additional variables
   */
  @Override
  public VariableSource fromAsset(final FluentAsset asset) {
    // Create a builder for the variable source
    VariableSourceBuilder builder = new VariableSourceBuilder();
      
    // Add standard resolvers for path and format using pattern matching for null safety
    switch (asset) {
      case null -> throw new IllegalArgumentException(STR."Asset cannot be null");
      case FluentAsset fa when fa.path() == null -> 
          throw new IllegalArgumentException(STR."Asset path cannot be null: \{fa}");
      case FluentAsset fa when fa.repository() == null -> 
          throw new IllegalArgumentException(STR."Asset repository cannot be null: \{fa}");
      case FluentAsset fa -> {
        builder.addResolver(new ConstantVariableResolver(fa.path(), PATH));
        builder.addResolver(new ConstantVariableResolver(fa.repository().getFormat().getValue(), FORMAT));
        addFromAsset(builder, fa);
      }
    }

    return builder.build();
  }

  /**
   * Creates a variable source from a path and format using pattern matching for validation.
   * 
   * @param path The path to create a variable source from
   * @param format The format to create a variable source from
   * @return A variable source containing the path and format
   */
  @Override
  public VariableSource fromPath(final String path, final String format) {
    // Create a builder for the variable source
    VariableSourceBuilder builder = new VariableSourceBuilder();
    
    // Use record pattern matching to validate inputs and build the source
    record PathFormat(String path, String format) {}
    
    switch (new PathFormat(path, format)) {
      case PathFormat(null, _) -> 
          throw new IllegalArgumentException(STR."Path cannot be null");
      case PathFormat(_, null) -> 
          throw new IllegalArgumentException(STR."Format cannot be null");
      case PathFormat(var p, var f) -> {
        builder.addResolver(new ConstantVariableResolver(p, PATH));
        builder.addResolver(new ConstantVariableResolver(f, FORMAT));
      }
    }

    return builder.build();
  }

  /**
   * Adds format-specific variables to the builder from the given asset.
   * Implementations should use pattern matching where appropriate to extract
   * and validate asset components.
   *
   * @param builder The variable source builder to add variables to
   * @param asset The asset to extract variables from
   */
  protected abstract void addFromAsset(VariableSourceBuilder builder, FluentAsset asset);
}