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
package org.sonatype.nexus.common.entity;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntityHelper}
 */
@Category(Java21TestGroup.class)
public class EntityHelperTest
    extends TestSupport
{
  @Test
  public void entityWithoutMetadata() {
    AbstractEntity entity = new AbstractEntity()
    {
    };
    assertFalse(EntityHelper.hasMetadata(entity));

    assertThrows(IllegalStateException.class, () -> EntityHelper.metadata(entity));

    assertThrows(IllegalStateException.class, () -> EntityHelper.isDetached(entity));

    assertThrows(IllegalStateException.class, () -> EntityHelper.id(entity));

    assertThrows(IllegalStateException.class, () -> EntityHelper.version(entity));
  }

  @Test
  public void entityWithMetadata() {
    AbstractEntity entity = new AbstractEntity()
    {
    };
    entity.setEntityMetadata(new DetachedEntityMetadata(new DetachedEntityId("a"), new DetachedEntityVersion("1")));

    assertAll("Entity metadata validation",
        () -> assertTrue(EntityHelper.hasMetadata(entity)),
        () -> assertThat(EntityHelper.metadata(entity), notNullValue()),
        () -> assertTrue(EntityHelper.isDetached(entity)),
        () -> assertThat(EntityHelper.id(entity).getValue(), is("a")),
        () -> assertThat(EntityHelper.version(entity).getValue(), is("1"))
    );
  }
  
  @Test
  public void entityWithMetadataUsingRecordPattern() {
    AbstractEntity entity = new AbstractEntity() {};
    entity.setEntityMetadata(new DetachedEntityMetadata(new DetachedEntityId("a"), new DetachedEntityVersion("1")));
    
    // Using record pattern to extract and validate entity metadata
    if (entity.getEntityMetadata() instanceof DetachedEntityMetadata(DetachedEntityId id, DetachedEntityVersion version)) {
      assertAll("Entity metadata using record pattern",
          () -> assertThat(id.getValue(), is("a")),
          () -> assertThat(version.getValue(), is("1"))
      );
    } else {
      // This should never happen if record pattern matching works correctly
      assertFalse(true, "Record pattern matching failed");
    }
  }
}
