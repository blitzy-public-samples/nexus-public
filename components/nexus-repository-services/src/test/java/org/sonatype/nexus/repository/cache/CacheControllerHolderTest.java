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
package org.sonatype.nexus.repository.cache;

import org.sonatype.nexus.repository.cache.CacheControllerHolder.CacheType;
import org.sonatype.nexus.test.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class CacheControllerHolderTest
{
  private static final CacheType TEST = new CacheType("TEST");

  private CacheController contentCacheController = new CacheController(1000, "content");

  private CacheController metadataCacheController = new CacheController(1000, "metadata");

  private CacheControllerHolder underTest;

  @BeforeEach
  public void setUp() {
    this.underTest = new CacheControllerHolder(contentCacheController, metadataCacheController);
  }

  @Test
  public void shouldGetContentCacheController() {
    assertThat(underTest.getContentCacheController(), is(contentCacheController));
  }

  @Test
  public void shouldGetMetadataCacheController() {
    assertThat(underTest.getMetadataCacheController(), is(metadataCacheController));
  }

  @Test
  public void shouldGetContentCacheControllerViaGet() {
    assertThat(underTest.get(CacheControllerHolder.CONTENT), is(contentCacheController));
  }

  @Test
  public void shouldGetMetadataCacheControllerViaGet() {
    assertThat(underTest.get(CacheControllerHolder.METADATA), is(metadataCacheController));
  }

  @Test
  public void shouldReturnNullForUnknownCacheControllerViaGet() {
    assertThat(underTest.get(TEST), is(nullValue()));
  }

  @Test
  public void shouldGetContentCacheControllerViaRequire() {
    assertThat(underTest.require(CacheControllerHolder.CONTENT), is(contentCacheController));
  }

  @Test
  public void shouldGetMetadataCacheControllerViaRequire() {
    assertThat(underTest.require(CacheControllerHolder.METADATA), is(metadataCacheController));
  }

  @Test
  public void shouldThrowExceptionForUnknownCacheControllerViaRequire() {
    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
      underTest.require(TEST);
    });
    assertTrue(exception.getMessage().contains(TEST.value()));
  }
}
