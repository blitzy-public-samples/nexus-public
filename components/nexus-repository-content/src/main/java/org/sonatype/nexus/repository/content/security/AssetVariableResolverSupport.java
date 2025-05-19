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

/**
 * Adapts persisted assets to variable resolvers.
 *
 * @since 3.29
 */
public abstract class AssetVariableResolverSupport
    extends VariableResolverAdapterSupport
    implements AssetVariableResolver
{
  /**
   * Creates a variable source from a fluent asset.
   * 
   * @param asset the asset to create a variable source from
   * @return the variable source for the asset
   * @throws IllegalArgumentException if the asset is null or invalid
   */
  @Override
  public VariableSource fromAsset(final FluentAsset asset) {
    // Use pattern matching to validate asset and handle different types more elegantly
    if (asset == null) {
      throw new IllegalArgumentException(STR."Asset cannot be null when creating variable source");
    }
    
    try {
      // Create a builder and add the standard resolvers
      var builder = new VariableSourceBuilder();
      
      // Add path resolver - using pattern matching to handle potential null paths
      String path = asset.path();
      if (path != null) {
        builder.addResolver(new ConstantVariableResolver(path, PATH));
      } else {
        throw new IllegalArgumentException(STR."Asset path cannot be null for asset: \{asset}");
      }
      
      // Add format resolver - using pattern matching for repository format
      var repository = asset.repository();
      switch (repository) {
        case null -> throw new IllegalArgumentException(STR."Asset repository cannot be null for asset: \{asset}");
        case var repo when repo.getFormat() == null -> 
            throw new IllegalArgumentException(STR."Repository format cannot be null for asset: \{asset}");
        case var repo -> builder.addResolver(new ConstantVariableResolver(repo.getFormat().getValue(), FORMAT));
      }
      
      // Add format-specific resolvers
      addFromAsset(builder, asset);

      return builder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException(STR."Failed to create variable source from asset: \{asset}", e);
    }
  }

  /**
   * Creates a variable source from a path and format.
   * 
   * @param path the asset path
   * @param format the repository format
   * @return the variable source for the path and format
   * @throws IllegalArgumentException if the path or format is null
   */
  @Override
  public VariableSource fromPath(final String path, final String format) {
    // Validate inputs using pattern matching
    switch (path) {
      case null -> throw new IllegalArgumentException(STR."Path cannot be null when creating variable source");
      case "" -> throw new IllegalArgumentException(STR."Path cannot be empty when creating variable source");
      default -> {}
    }
    
    switch (format) {
      case null -> throw new IllegalArgumentException(STR."Format cannot be null when creating variable source");
      case "" -> throw new IllegalArgumentException(STR."Format cannot be empty when creating variable source");
      default -> {}
    }
    
    // Create and return the variable source
    var builder = new VariableSourceBuilder();
    builder.addResolver(new ConstantVariableResolver(path, PATH));
    builder.addResolver(new ConstantVariableResolver(format, FORMAT));

    return builder.build();
  }

  /**
   * Adds format-specific variables to the builder for the given asset.
   * 
   * @param builder the variable source builder
   * @param asset the asset to add variables for
   */
  protected abstract void addFromAsset(VariableSourceBuilder builder, FluentAsset asset);
}
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

/**
 * Adapts persisted assets to variable resolvers.
 *
 * @since 3.29
 */
public abstract class AssetVariableResolverSupport
    extends VariableResolverAdapterSupport
    implements AssetVariableResolver
{
  /**
   * Creates a variable source from a fluent asset.
   * 
   * @param asset the asset to create a variable source from
   * @return the variable source for the asset
   * @throws IllegalArgumentException if the asset is null or invalid
   */
  @Override
  public VariableSource fromAsset(final FluentAsset asset) {
    // Use pattern matching to validate asset and handle different types more elegantly
    if (asset == null) {
      throw new IllegalArgumentException(STR."Asset cannot be null when creating variable source");
    }
    
    try {
      // Create a builder and add the standard resolvers
      var builder = new VariableSourceBuilder();
      
      // Add path resolver - using pattern matching to handle potential null paths
      String path = asset.path();
      if (path != null) {
        builder.addResolver(new ConstantVariableResolver(path, PATH));
      } else {
        throw new IllegalArgumentException(STR."Asset path cannot be null for asset: \{asset}");
      }
      
      // Add format resolver - using pattern matching for repository format
      var repository = asset.repository();
      switch (repository) {
        case null -> throw new IllegalArgumentException(STR."Asset repository cannot be null for asset: \{asset}");
        case var repo when repo.getFormat() == null -> 
            throw new IllegalArgumentException(STR."Repository format cannot be null for asset: \{asset}");
        case var repo -> builder.addResolver(new ConstantVariableResolver(repo.getFormat().getValue(), FORMAT));
      }
      
      // Add format-specific resolvers
      addFromAsset(builder, asset);

      return builder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException(STR."Failed to create variable source from asset: \{asset}", e);
    }
  }

  /**
   * Creates a variable source from a path and format.
   * 
   * @param path the asset path
   * @param format the repository format
   * @return the variable source for the path and format
   * @throws IllegalArgumentException if the path or format is null
   */
  @Override
  public VariableSource fromPath(final String path, final String format) {
    // Validate inputs using pattern matching
    switch (path) {
      case null -> throw new IllegalArgumentException(STR."Path cannot be null when creating variable source");
      case "" -> throw new IllegalArgumentException(STR."Path cannot be empty when creating variable source");
      default -> {}
    }
    
    switch (format) {
      case null -> throw new IllegalArgumentException(STR."Format cannot be null when creating variable source");
      case "" -> throw new IllegalArgumentException(STR."Format cannot be empty when creating variable source");
      default -> {}
    }
    
    // Create and return the variable source
    var builder = new VariableSourceBuilder();
    builder.addResolver(new ConstantVariableResolver(path, PATH));
    builder.addResolver(new ConstantVariableResolver(format, FORMAT));

    return builder.build();
  }

  /**
   * Adds format-specific variables to the builder for the given asset.
   * 
   * @param builder the variable source builder
   * @param asset the asset to add variables for
   */
  protected abstract void addFromAsset(VariableSourceBuilder builder, FluentAsset asset);
}