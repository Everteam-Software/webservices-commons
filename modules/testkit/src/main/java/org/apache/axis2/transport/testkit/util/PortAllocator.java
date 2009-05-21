/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.axis2.transport.testkit.util;

import org.apache.axis2.transport.testkit.tests.Setup;
import org.apache.axis2.transport.testkit.tests.Transient;

public class PortAllocator {
    public static final PortAllocator INSTANCE = new PortAllocator();
    
    private static final int basePort = 9000;
    
    private @Transient boolean[] allocated;
    
    private PortAllocator() {
    }
    
    @Setup @SuppressWarnings("unused")
    private void setUp() {
        allocated = new boolean[16];
    }
    
    public int allocatePort() {
        int len = allocated.length;
        for (int i=0; i<len; i++) {
            if (!allocated[i]) {
                allocated[i] = true;
                return basePort+i;
            }
        }
        
        boolean[] newAllocated = new boolean[len*2];
        System.arraycopy(allocated, 0, newAllocated, 0, len);
        newAllocated[len] = true;
        allocated = newAllocated;
        return basePort+len;
    }
    
    public void releasePort(int port) {
        allocated[port-basePort] = false;
    }
}
