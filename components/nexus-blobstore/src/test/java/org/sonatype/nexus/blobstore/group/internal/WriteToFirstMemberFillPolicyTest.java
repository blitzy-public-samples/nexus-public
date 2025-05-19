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
package org.sonatype.nexus.blobstore.group.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.common.test.Java21TestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Java21TestGroup
public class WriteToFirstMemberFillPolicyTest
    extends TestSupport
{
  private final WriteToFirstMemberFillPolicy underTest = new WriteToFirstMemberFillPolicy();

  record TestParams(boolean available, boolean writable, String chosenBlobStoreName) {}

  static Stream<TestParams> testConditions() {
    return Stream.of(
        new TestParams(false, false, "three"),
        new TestParams(false, true, "three"),
        new TestParams(true, false, "three"),
        new TestParams(true, true, "one")
    );
  }

  @ParameterizedTest
  @MethodSource("testConditions")
  public void itShouldSkipNonAvailableAndNonWritableMembers(TestParams params) {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> mockedMembers =
        Arrays.asList(mockMember("one", params.available(), params.writable()), mockMember("two", params.available(), params.writable()),
            mockMember("three", true, true));
    when(blobStoreGroup.getMembers()).thenReturn(mockedMembers);
    assertThat(underTest.chooseBlobStore(blobStoreGroup, new HashMap<>()).getBlobStoreConfiguration().getName(),
        is(params.chosenBlobStoreName()));
  }

  private BlobStore mockMember(final String name, final boolean available, final boolean writable) {
    BlobStore member = mock(BlobStore.class);
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(member.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn(name);
    when(member.isStorageAvailable()).thenReturn(available);
    when(member.isWritable()).thenReturn(writable);
    return member;
  }
}