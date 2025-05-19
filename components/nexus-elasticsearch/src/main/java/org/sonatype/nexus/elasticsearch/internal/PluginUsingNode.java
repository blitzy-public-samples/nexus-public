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
package org.sonatype.nexus.elasticsearch.internal;

import java.nio.file.Path;
import java.util.Collection;

import org.elasticsearch.Version;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;

/**
 * Custom {@link org.elasticsearch.node.Node} implementation to allow {@link Plugin} classes to be passed into the
 * constructor.
 *
 * Updated for Java 21 compatibility with proper module system handling and updated Elasticsearch APIs.
 *
 * @since 3.1
 */
public class PluginUsingNode
    extends Node
{
  /**
   * Creates a new PluginUsingNode with the specified settings and plugins.
   * 
   * This constructor is updated for Java 21 compatibility, replacing the deprecated
   * InternalSettingsPreparer with direct Environment creation to ensure proper module system handling.
   *
   * @param preparedSettings the settings to use for this node
   * @param plugins the collection of plugin classes to load
   */
  public PluginUsingNode(final Settings preparedSettings, Collection<Class<? extends Plugin>> plugins) {
    // Create Environment directly instead of using deprecated InternalSettingsPreparer
    // This approach is compatible with Java 21's stronger module encapsulation
    super(createEnvironment(preparedSettings), Version.CURRENT, plugins);
  }
  
  /**
   * Creates an Environment instance from the provided settings.
   * This method replaces the deprecated InternalSettingsPreparer.prepareEnvironment method
   * with a direct Environment creation that's compatible with Java 21.
   *
   * @param settings the settings to create the environment from
   * @return the created Environment instance
   */
  private static Environment createEnvironment(final Settings settings) {
    // Use the Environment constructor directly, which is compatible with Java 21
    // This avoids using the deprecated InternalSettingsPreparer class
    Path configPath = Environment.configPath(settings);
    return new Environment(settings, configPath);
  }
}