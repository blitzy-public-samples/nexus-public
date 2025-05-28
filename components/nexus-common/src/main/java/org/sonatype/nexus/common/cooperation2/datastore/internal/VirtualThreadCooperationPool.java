package org.sonatype.nexus.common.cooperation2.datastore.internal;

public class VirtualThreadCooperationPool {

	public static boolean isVirtualThreadSupported() {
		try {
			// Check if Thread class has the ofVirtual method (Java 21+)
			Thread.class.getMethod("ofVirtual");
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

}
