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
 *
 * This file has been updated as part of the Java 21 migration project to ensure compatibility with Java 21 runtime.
 */
package org.sonatype.nexus.repository.apt.datastore.internal.browse;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.content.browse.store.FormatBrowseModule;

/**
 * Configures the content store bindings for an Apt format.
 * 
 * This class has been verified for compatibility with Java 21 as part of the platform upgrade.
 * The javax.inject annotations used (Named, Singleton) are compatible with Java 21 without changes.
 * No deprecated APIs are used in this implementation.
 *
 * @since 3.31
 */
@Named(AptFormat.NAME)
@Singleton
public class AptBrowseModule
    extends FormatBrowseModule<AptBrowseNodeDAO>
{
}