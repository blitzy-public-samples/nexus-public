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
package org.sonatype.nexus.security.authc;

import java.io.Serializable;

/**
 * Event record representing a user logout.
 * Extends the base SecurityEvent record with logout-specific context.
 *
 * @since 3.0
 */
public final class LogoutEvent extends SecurityEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String principal;
    private final String realm;

    public LogoutEvent(String principal, String realm) {
        super(principal, realm);
        this.principal = principal;
        this.realm = realm;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getRealm() {
        return realm;
    }
}
